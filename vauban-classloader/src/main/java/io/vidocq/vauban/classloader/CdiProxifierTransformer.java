/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.classloader;

import io.vidocq.vauban.classloader.spi.ArchiveContext;
import io.vidocq.vauban.classloader.spi.ClassTransformerPlugin;
import io.vidocq.vauban.weaver.BeanWeavingAnalysis;
import io.vidocq.vauban.weaver.ProxyLinkWeaver;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vauban#24 client-proxy weaving as a load-time class transformer: an unwoven
 * normal-scoped bean gains its synthetic {@code (ProxyLink)} entry constructor when the
 * Vauban class loader defines it, and its {@code <Bean>_ClientProxy} companion is
 * retargeted onto it — the very transformation the javac plugin and the Maven plugin
 * apply at build time ({@link ProxyLinkWeaver} is the single shared implementation), so a
 * woven archive passes through untouched (idempotence) and AOT/JVM stay equivalent.
 *
 * <p>Pre-filter: the archive's {@code META-INF/vauban-beans.list} when present (O(1) per
 * class); an archive without the list (not APT-processed — e.g. a third-party jar) is
 * inspected class by class at the byte level.
 *
 * <p>Documented limit (study §8): a custom {@code @NormalScope} annotation is only
 * resolved when its bytes are reachable through the {@link ArchiveContext} — the
 * cross-archive custom-scope case stays covered by the build tier and deployment
 * validation.
 */
public final class CdiProxifierTransformer implements ClassTransformerPlugin {

    public static final String NAME = "cdi-proxifier";
    private static final String CLIENT_PROXY_SUFFIX = "_ClientProxy";

    /** Per-archive memo for custom-scope annotation lookups. */
    private final Map<ArchiveContext, Map<String, Boolean>> scopeCaches = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean interested(String className, ArchiveContext archive) {
        var beans = archive.beansList();
        if (beans.isEmpty()) {
            // Not an APT-processed archive: no cheap pre-filter, decide in transform()
            return !className.contains("$$");
        }
        if (beans.get().contains(className)) return true;
        return className.endsWith(CLIENT_PROXY_SUFFIX)
                && beans.get().contains(
                        className.substring(0, className.length() - CLIENT_PROXY_SUFFIX.length()));
    }

    @Override
    public byte[] transform(String className, byte[] bytes, ArchiveContext archive) {
        if (className.endsWith(CLIENT_PROXY_SUFFIX)) {
            return retargetIfBeanWoven(className, bytes, archive);
        }
        var scopeCache = scopeCaches.computeIfAbsent(archive, a -> new ConcurrentHashMap<>());
        BeanWeavingAnalysis.ByteResolver resolver = archive::classBytes;
        try {
            if (!BeanWeavingAnalysis.isNormalScopedBeanClass(bytes, resolver, scopeCache)
                    || !BeanWeavingAnalysis.needsMarker(bytes)) {
                return null;
            }
            // A superclass that itself needs weaving will be woven at its own definition:
            // declare the archive's beans as weavable candidates so the chain resolves.
            var chain = BeanWeavingAnalysis.superChain(bytes, resolver,
                    archive.beansList().orElse(Set.of()), new java.util.HashMap<>());
            if (chain == null) {
                // No side-effect-free chain: leave the class alone, deployment validation
                // will produce the regular vauban#24 diagnostic
                return null;
            }
            return ProxyLinkWeaver.addMarkerConstructor(bytes, chain);
        } catch (IllegalArgumentException unparseable) {
            return null;
        }
    }

    /**
     * A proxy is only retargeted when its bean superclass carries (or will carry once
     * woven by this transformer) the marker — an already-retargeted proxy comes out
     * identical (idempotence).
     */
    private byte[] retargetIfBeanWoven(String proxyName, byte[] proxyBytes, ArchiveContext archive) {
        var beanName = proxyName.substring(0, proxyName.length() - CLIENT_PROXY_SUFFIX.length());
        var beanBytes = archive.classBytes(beanName);
        if (beanBytes == null) return null;
        try {
            var beanWillHaveMarker = ProxyLinkWeaver.hasMarkerConstructor(beanBytes)
                    || (BeanWeavingAnalysis.needsMarker(beanBytes)
                            && BeanWeavingAnalysis.isNormalScopedBeanClass(beanBytes,
                                    archive::classBytes,
                                    scopeCaches.computeIfAbsent(archive, a -> new ConcurrentHashMap<>()))
                            && BeanWeavingAnalysis.superChain(beanBytes, archive::classBytes,
                                    archive.beansList().orElse(Set.of()),
                                    new java.util.HashMap<>()) != null);
            if (!beanWillHaveMarker) return null;
            return ProxyLinkWeaver.retargetProxyConstructor(proxyBytes);
        } catch (IllegalArgumentException unparseable) {
            return null;
        }
    }
}
