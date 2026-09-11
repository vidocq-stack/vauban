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

import java.lang.classfile.ClassFile;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * package. It carries no scope annotation, so it is treated as weavable regardless — the marker
 * goes in at its definition, and the placed proxy, transformed against the same archive context,
 * is retargeted onto it, so the third-party constructor never runs for the proxy. When the type's
 * superclass has only a business constructor, that superclass is woven as well, as long as this
 * loader defines it; otherwise no side-effect-free chain exists and a WARNING says so.
 *
 * <p>One gate, used everywhere: {@code superChain} treats a candidate superclass as "will be
 * woven", so the candidate set is exactly what {@link #transform} weaves — a candidate left
 * unwoven would leave its subclass chaining a constructor that does not exist.
 *
 * <p>Documented limit (study §8): a custom {@code @NormalScope} annotation is only
 * resolved when its bytes are reachable through the {@link ArchiveContext} — the
 * cross-archive custom-scope case stays covered by the build tier and deployment
 * validation.
 */
public final class CdiProxifierTransformer implements ClassTransformerPlugin {

    public static final String NAME = "cdi-proxifier";
    private static final String CLIENT_PROXY_SUFFIX = "_ClientProxy";
    private static final System.Logger LOG = System.getLogger(CdiProxifierTransformer.class.getName());
    /** Guard against a malformed hierarchy when walking superclasses. */
    private static final int MAX_DEPTH = 64;

    /** Per-archive memo for custom-scope annotation lookups. */
    private final Map<ArchiveContext, Map<String, Boolean>> scopeCaches = new ConcurrentHashMap<>();
    /** Per-archive memo of the placed types plus the superclasses they need woven. */
    private final Map<ArchiveContext, Set<String>> placedChains = new ConcurrentHashMap<>();
    /** Per-archive memo of every class {@link #transform} will weave. */
    private final Map<ArchiveContext, Set<String>> candidateSets = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean interested(String className, ArchiveContext archive) {
        var placed = placedChain(archive);
        if (placed.contains(className)
                || (className.endsWith(CLIENT_PROXY_SUFFIX) && placed.contains(beanOf(className)))) {
            return true; // a placed produced type (or a superclass it needs woven), or its placed proxy
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
        try {
            if (ProxyLinkWeaver.hasMarkerConstructor(bytes)) {
                return null; // already woven (build tier, or an earlier definition)
            }
            boolean placed = placedChain(archive).contains(className);
            if (!placed && !isWovenBean(bytes, archive)) {
                return null;
            }
            // A placed type gets the marker whenever it lacks one: its constructor is third-party
            // code that a placed proxy must never run, no-arg or not (#24).
            var chain = BeanWeavingAnalysis.superChain(bytes, archive::classBytes,
                    candidates(archive), new HashMap<>());
            if (chain == null) {
                if (placed) {
                    LOG.log(System.Logger.Level.WARNING, () -> className + ": no side-effect-free "
                            + "constructor chain — a superclass offers neither the (ProxyLink) marker "
                            + "nor a non-private no-arg constructor, and is not defined by the Vauban "
                            + "class loader. Its placed client proxy will run the business constructor "
                            + "on a throwaway instance (vauban#24).");
                }
                // For a declared bean, deployment validation produces the regular vauban#24
                // diagnostic.
                return null;
            }
            return ProxyLinkWeaver.addMarkerConstructor(bytes, chain);
        } catch (IllegalArgumentException unparseable) {
            return null;
        }
    }

    /**
     * A proxy is only retargeted when its bean superclass carries, or will carry once woven by
     * this transformer, the marker — decided by the same gate as {@link #transform}, so a proxy is
     * never retargeted onto a constructor that will not exist. An already-retargeted proxy comes
     * out identical (idempotence).
     */
    private byte[] retargetIfBeanWoven(String proxyName, byte[] proxyBytes, ArchiveContext archive) {
        var beanName = beanOf(proxyName);
        var beanBytes = archive.classBytes(beanName);
        if (beanBytes == null) return null;
        try {
            if (!willHaveMarker(beanName, beanBytes, archive)) return null;
            return ProxyLinkWeaver.retargetProxyConstructor(proxyBytes);
        } catch (IllegalArgumentException unparseable) {
            return null;
        }
    }

    /** Whether {@code name} carries the marker, or will once {@link #transform} has seen it. */
    private boolean willHaveMarker(String name, byte[] bytes, ArchiveContext archive) {
        if (ProxyLinkWeaver.hasMarkerConstructor(bytes)) return true;
        if (!placedChain(archive).contains(name) && !isWovenBean(bytes, archive)) return false;
        return BeanWeavingAnalysis.superChain(bytes, archive::classBytes, candidates(archive),
                new HashMap<>()) != null;
    }

    /** The load-time gate for a declared bean: normal-scoped, without a usable no-arg constructor. */
    private boolean isWovenBean(byte[] bytes, ArchiveContext archive) {
        return BeanWeavingAnalysis.needsMarker(bytes)
                && BeanWeavingAnalysis.isNormalScopedBeanClass(bytes, archive::classBytes, scopeCache(archive));
    }

    /**
     * Every class {@link #transform} weaves, as seen from {@code archive}: the placed chain, plus the
     * listed beans that pass {@link #isWovenBean}. This is what {@code superChain} may assume will
     * carry the marker — nothing more, or a subclass would chain a constructor that never appears.
     */
    private Set<String> candidates(ArchiveContext archive) {
        return candidateSets.computeIfAbsent(archive, a -> {
            var set = new LinkedHashSet<>(placedChain(a));
            for (var bean : a.beansList().orElse(Set.of())) {
                var beanBytes = a.classBytes(bean);
                if (beanBytes == null) continue;
                try {
                    if (!ProxyLinkWeaver.hasMarkerConstructor(beanBytes) && isWovenBean(beanBytes, a)) {
                        set.add(bean);
                    }
                } catch (IllegalArgumentException unparseable) {
                    // not a candidate
                }
            }
            return Set.copyOf(set);
        });
    }

    /**
     * The placed types plus the superclasses of their chain that must be woven for a
     * side-effect-free chain to exist: classes this loader defines (their bytes resolve through
     * the archive context) that carry neither the marker nor a non-private no-arg constructor. The
     * walk stops at the first superclass that ends the chain on its own, or that this loader does
     * not define.
     */
    private Set<String> placedChain(ArchiveContext archive) {
        var placed = archive.placedProxyTypes();
        if (placed.isEmpty()) return placed;
        return placedChains.computeIfAbsent(archive, a -> {
            var set = new LinkedHashSet<>(placed);
            for (var type : placed) {
                var current = type;
                for (int depth = 0; depth < MAX_DEPTH; depth++) {
                    var currentBytes = a.classBytes(current);
                    if (currentBytes == null) break;
                    String superName;
                    try {
                        superName = ClassFile.of().parse(currentBytes).superclass()
                                .map(s -> s.asInternalName().replace('/', '.'))
                                .orElse(null);
                    } catch (IllegalArgumentException unparseable) {
                        break;
                    }
                    if (superName == null || "java.lang.Object".equals(superName)) break;
                    var superBytes = a.classBytes(superName);
                    if (superBytes == null) break; // not defined by this loader
                    if (ProxyLinkWeaver.hasMarkerConstructor(superBytes)
                            || ProxyLinkWeaver.hasNonPrivateNoArgConstructor(superBytes)) {
                        break; // the chain ends here without weaving anything more
                    }
                    set.add(superName);
                    current = superName;
                }
            }
            return Set.copyOf(set);
        });
    }

    private Map<String, Boolean> scopeCache(ArchiveContext archive) {
        return scopeCaches.computeIfAbsent(archive, a -> new ConcurrentHashMap<>());
    }

    private static String beanOf(String proxyName) {
        return proxyName.substring(0, proxyName.length() - CLIENT_PROXY_SUFFIX.length());
    }
}
