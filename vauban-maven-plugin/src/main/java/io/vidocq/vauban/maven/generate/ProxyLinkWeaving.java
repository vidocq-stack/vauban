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
package io.vidocq.vauban.maven.generate;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.core.proxy.ProxyLinkWeaver;
import io.vidocq.vauban.core.proxy.ProxyLinkWeaver.SuperChain;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 of Vidocq/vauban#24 — decides, per normal-scoped bean of the module being built,
 * whether the synthetic {@code (ProxyLink)} client-proxy entry constructor can be woven
 * into its compiled class, and applies {@link ProxyLinkWeaver}. The application's source
 * never declares the marker.
 *
 * <p>Superclass chaining: {@code Object} (or a superclass with a non-private no-arg
 * constructor) is chained with {@code super()}; a superclass compiled by this same module
 * is woven recursively and chained with {@code super((ProxyLink) null)}; a superclass from
 * a dependency jar is accepted when it already carries the marker (its own build wove it)
 * or a no-arg constructor — anything else leaves the bean unpatched, with a warning, and
 * the deployment validator reports it at container start.
 */
final class ProxyLinkWeaving {

    /** Weaving outcome for one class (memoized — a superclass is decided once). */
    private enum Entry { CARRIES_MARKER, NOT_PATCHABLE }

    private final Path classesDir;
    private final VaubanIndex index;
    private final ClassLoader classLoader;
    private final List<String> warnings;
    private final Map<String, Entry> decided = new HashMap<>();
    private final List<String> patched = new ArrayList<>();

    private ProxyLinkWeaving(Path classesDir, VaubanIndex index, ClassLoader classLoader,
            List<String> warnings) {
        this.classesDir = classesDir;
        this.index = index;
        this.classLoader = classLoader;
        this.warnings = warnings;
    }

    /**
     * Weaves the marker into every eligible normal-scoped managed bean compiled into
     * {@code classesDir}.
     *
     * @return the fully-qualified names of the classes that were patched (beans that
     *         already carried the marker are not listed)
     */
    static List<String> weave(Path classesDir, VaubanIndex index, ClassLoader classLoader,
            List<BeanDescriptor> beans, List<String> warnings) {
        var weaving = new ProxyLinkWeaving(classesDir, index, classLoader, warnings);
        for (var bean : beans) {
            if (bean.kind() != BeanDescriptor.BeanKind.MANAGED || !bean.scope().isNormal()) continue;
            var fqn = bean.beanClass().value();
            if (fqn.contains("$")) continue; // nested beans keep the runtime fallback
            weaving.ensureMarker(fqn);
        }
        return List.copyOf(weaving.patched);
    }

    /**
     * Rewrites the {@code <init>} of {@code <fqn>_ClientProxy} (when present on disk) to
     * chain to the marker. Called after proxy generation, for beans known to carry the
     * marker — idempotent on proxies that already target it.
     */
    static void retargetProxies(Path classesDir, List<String> beanFqns, List<String> warnings) {
        for (var fqn : beanFqns) {
            var proxyFile = classesDir.resolve(
                    (fqn + ClientProxyShape.PROXY_SUFFIX).replace('.', '/') + ".class");
            if (!Files.isRegularFile(proxyFile)) continue;
            try {
                Files.write(proxyFile, ProxyLinkWeaver.retargetProxyConstructor(Files.readAllBytes(proxyFile)));
            } catch (IOException e) {
                warnings.add("Failed to retarget proxy of " + fqn + ": " + e.getMessage());
            }
        }
    }

    /** Ensures {@code fqn} carries the marker constructor, weaving it (and its supers) if needed. */
    private Entry ensureMarker(String fqn) {
        var known = decided.get(fqn);
        if (known != null) return known;
        decided.put(fqn, Entry.NOT_PATCHABLE); // cycle guard; overwritten on success

        var classFile = classesDir.resolve(fqn.replace('.', '/') + ".class");
        if (!Files.isRegularFile(classFile)) {
            return decideExternal(fqn);
        }

        var info = index.getClassByName(DotName.of(fqn)).orElse(null);
        if (info != null && hasMarker(info)) {
            return remember(fqn, Entry.CARRIES_MARKER);
        }

        SuperChain chain = superChain(info == null ? null : info.superName(), fqn);
        if (chain == null) return Entry.NOT_PATCHABLE;

        try {
            var patchedBytes = ProxyLinkWeaver.addMarkerConstructor(Files.readAllBytes(classFile), chain);
            if (patchedBytes != null) {
                Files.write(classFile, patchedBytes);
                patched.add(fqn);
            }
            return remember(fqn, Entry.CARRIES_MARKER); // patched now, or already carried it on disk
        } catch (IOException | IllegalArgumentException e) {
            warnings.add("Failed to weave ProxyLink constructor into " + fqn + ": " + e.getMessage());
            return Entry.NOT_PATCHABLE;
        }
    }

    /** Chain for {@code fqn}'s marker toward {@code superName} — {@code null} when unreachable. */
    private SuperChain superChain(DotName superName, String fqn) {
        if (superName == null || "java.lang.Object".equals(superName.value())) {
            return SuperChain.NO_ARG;
        }
        var superEntry = ensureMarker(superName.value());
        if (superEntry == Entry.CARRIES_MARKER) return SuperChain.MARKER;

        var superInfo = index.getClassByName(superName).orElse(null);
        if (superInfo != null && hasNonPrivateNoArg(superInfo)) return SuperChain.NO_ARG;
        if (superInfo == null && externalHasNoArg(superName.value())) return SuperChain.NO_ARG;

        warnings.add("Cannot weave ProxyLink constructor into " + fqn + ": superclass "
                + superName.value() + " offers neither a marker nor a non-private no-arg constructor");
        return null;
    }

    /** A class outside this module: marker or no-arg means its hierarchy is chainable. */
    private Entry decideExternal(String fqn) {
        var info = index.getClassByName(DotName.of(fqn)).orElse(null);
        if (info != null) {
            return remember(fqn, hasMarker(info) ? Entry.CARRIES_MARKER : Entry.NOT_PATCHABLE);
        }
        if (classLoader != null) {
            try {
                var clazz = Class.forName(fqn, false, classLoader);
                for (var ctor : clazz.getDeclaredConstructors()) {
                    if (ClientProxyShape.isProxyLinkConstructor(ctor)) {
                        return remember(fqn, Entry.CARRIES_MARKER);
                    }
                }
            } catch (ClassNotFoundException | NoClassDefFoundError _) {
                // Not loadable here — treated as not patchable.
            }
        }
        return Entry.NOT_PATCHABLE;
    }

    private boolean externalHasNoArg(String fqn) {
        if (classLoader == null) return false;
        try {
            for (var ctor : Class.forName(fqn, false, classLoader).getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 0
                        && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                    return true;
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError _) {
            // Not loadable here — no chainable constructor known.
        }
        return false;
    }

    private Entry remember(String fqn, Entry entry) {
        decided.put(fqn, entry);
        return entry;
    }

    private static boolean hasMarker(ClassInfo info) {
        return info.methods().stream().anyMatch(ClientProxyShape::isProxyLinkConstructor);
    }

    private static boolean hasNonPrivateNoArg(ClassInfo info) {
        boolean hasExplicit = info.methods().stream().anyMatch(m -> m.isConstructor());
        return !hasExplicit || info.methods().stream()
                .anyMatch(m -> m.isConstructor() && !m.isPrivate() && m.parameters().isEmpty());
    }
}
