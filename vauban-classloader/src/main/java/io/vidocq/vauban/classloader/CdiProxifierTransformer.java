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

import java.util.HashMap;
import java.util.HashSet;
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
 * <p>Placed produced types (issue #42, Stage 4): a type named in a placement manifest
 * ({@link ArchiveContext#placedProxyTypes()}) is a third-party class produced by a
 * normal-scoped {@code @Produces} whose client proxy the loader defines into the type's own
 * package. It carries no scope annotation, so the pre-filter and the scope check would both
 * pass it by; it is treated as weavable regardless — the marker goes in at its definition, and
 * the placed proxy, transformed against the same archive context, is retargeted onto it. That
 * closes the #24 double construction for third-party produced types, whose build-time proxy
 * otherwise chains the external constructor.
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
        var placed = archive.placedProxyTypes();
        if (placed.contains(className)
                || (className.endsWith(CLIENT_PROXY_SUFFIX) && placed.contains(beanOf(className)))) {
            return true; // a placed produced type, or its placed proxy
        }
        var beans = archive.beansList();
        if (beans.isEmpty()) {
            // Not an APT-processed archive: no cheap pre-filter, decide in transform()
            return !className.contains("$$");
        }
        if (beans.get().contains(className)) return true;
        return className.endsWith(CLIENT_PROXY_SUFFIX) && beans.get().contains(beanOf(className));
    }

    @Override
    public byte[] transform(String className, byte[] bytes, ArchiveContext archive) {
        if (className.endsWith(CLIENT_PROXY_SUFFIX)) {
            return retargetIfBeanWoven(className, bytes, archive);
        }
        var scopeCache = scopeCaches.computeIfAbsent(archive, a -> new ConcurrentHashMap<>());
        BeanWeavingAnalysis.ByteResolver resolver = archive::classBytes;
        try {
            if (ProxyLinkWeaver.hasMarkerConstructor(bytes)) {
                return null; // already woven (build tier, or an earlier definition)
            }
            var placed = archive.placedProxyTypes().contains(className);
            if (!placed) {
                // A declared bean keeps the load-time gate: only a bean without a usable
                // no-arg constructor needs the marker here.
                if (!BeanWeavingAnalysis.needsMarker(bytes)
                        || !BeanWeavingAnalysis.isNormalScopedBeanClass(bytes, resolver, scopeCache)) {
                    return null;
                }
            }
            // A placed produced type gets the marker whenever it lacks one: its constructor is
            // third-party code, and a placed proxy must never run it, no-arg or not (#24).
            // A superclass that itself needs weaving will be woven at its own definition:
            // declare the archive's beans (and every placed type) as weavable candidates so
            // the chain resolves.
            var chain = BeanWeavingAnalysis.superChain(bytes, resolver, weavable(archive),
                    new HashMap<>());
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
        var beanName = beanOf(proxyName);
        var beanBytes = archive.classBytes(beanName);
        if (beanBytes == null) return null;
        try {
            var placed = archive.placedProxyTypes().contains(beanName);
            var beanWillHaveMarker = ProxyLinkWeaver.hasMarkerConstructor(beanBytes)
                    || ((placed || (BeanWeavingAnalysis.needsMarker(beanBytes)
                            && BeanWeavingAnalysis.isNormalScopedBeanClass(beanBytes,
                                    archive::classBytes,
                                    scopeCaches.computeIfAbsent(archive, a -> new ConcurrentHashMap<>()))))
                            && BeanWeavingAnalysis.superChain(beanBytes, archive::classBytes,
                                    weavable(archive), new HashMap<>()) != null);
            if (!beanWillHaveMarker) return null;
            return ProxyLinkWeaver.retargetProxyConstructor(proxyBytes);
        } catch (IllegalArgumentException unparseable) {
            return null;
        }
    }

    /** The archive's declared beans plus every placed produced type: all may be woven. */
    private static Set<String> weavable(ArchiveContext archive) {
        var beans = archive.beansList().orElse(Set.of());
        var placed = archive.placedProxyTypes();
        if (placed.isEmpty()) return beans;
        var all = new HashSet<>(beans);
        all.addAll(placed);
        return all;
    }

    private static String beanOf(String proxyName) {
        return proxyName.substring(0, proxyName.length() - CLIENT_PROXY_SUFFIX.length());
    }
}
