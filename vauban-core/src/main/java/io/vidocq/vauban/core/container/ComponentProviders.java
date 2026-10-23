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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.api.VaubanComponentProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Resolves component instances through the module-supplied {@link VaubanComponentProvider}s
 * (loaded via {@link ServiceLoader}) so the container can instantiate application components
 * without reflection and without {@code opens … to io.vidocq.vauban.core}.
 *
 * <p>The container consults this resolver first; only when no provider owns a class does it
 * fall back to reflective instantiation ({@link VaubanLookup}). This keeps backward
 * compatibility (class path, unnamed modules, jars not yet APT-processed) while removing the
 * need for a qualified {@code opens} whenever a generated provider is present.
 */
record ComponentProviders(List<VaubanComponentProvider> providers) {

    /**
     * Keeps an immutable copy of {@code providers}.
     *
     * @param providers the providers this deployment loaded, for the parts of the container that consult them
     *                  directly
     */
    ComponentProviders {
        providers = List.copyOf(providers);
    }

    /**
     * Combines explicitly-registered providers (priority order) with every
     * {@link VaubanComponentProvider} discovered via {@link ServiceLoader} from {@code cl}.
     * A misconfigured service declaration is non-fatal — the container falls back to reflection.
     *
     * @param cl    class loader scanned for service providers
     * @param extra programmatically-registered providers, consulted before the discovered ones
     */
    static ComponentProviders load(ClassLoader cl, List<VaubanComponentProvider> extra) {
        var all = new ArrayList<>(extra);
        try {
            ServiceLoader.load(VaubanComponentProvider.class, cl)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .forEach(all::add);
        } catch (ServiceConfigurationError | LinkageError | RuntimeException _) {
            // misconfigured/absent services — keep the explicit providers, fall back otherwise
        }
        all = new ArrayList<>(withTestCompanions(all));
        // A trampoline re-layering, as when an IDE launches the application through Vidocq,
        // resolves the application BOTH in the boot layer and in the Vauban layer: ServiceLoader
        // then yields each generated provider twice, the layer one (woven world) and its
        // boot-layer twin (un-woven world). Deduplicate by class name, preferring the layer's
        // copy; providers that exist only once (runtime extensions, explicit ones) are kept.
        var layerLoader = layerLoaderOf(cl);
        if (layerLoader != null) {
            var byName = new java.util.LinkedHashMap<String, VaubanComponentProvider>();
            for (var provider : all) {
                var name = provider.getClass().getName();
                var existing = byName.putIfAbsent(name, provider);
                if (existing != null && definedUnderLayer(provider, layerLoader)
                        && !definedUnderLayer(existing, layerLoader)) {
                    byName.put(name, provider);
                }
            }
            all = new ArrayList<>(byName.values());
        }
        return new ComponentProviders(all);
    }

    private static final String MAIN_PROVIDER = "_VaubanComponents";

    /**
     * {@code providers}, each {@code _VaubanComponents} followed by the {@code _VaubanComponentsTests} of its
     * package when there is one (vauban#101). That provider is what a module's test compilation emits; no
     * {@code provides} clause can name it, since {@code module-info} belongs to the main sources, so it is found by
     * name through the lookup the main provider grants. Added once, and not when it is already in the list.
     */
    static List<VaubanComponentProvider> withTestCompanions(List<VaubanComponentProvider> providers) {
        var present = new java.util.HashSet<String>();
        providers.forEach(provider -> present.add(provider.getClass().getName()));
        var result = new ArrayList<VaubanComponentProvider>(providers.size());
        for (var provider : providers) {
            result.add(provider);
            var type = provider.getClass();
            if (type.getSimpleName().equals(MAIN_PROVIDER)) {
                addTestCompanion(provider, type.getName() + "Tests", present, result);
            }
        }
        return result;
    }

    /** Adds the {@code companionName} provider to {@code result} once, when it exists and is not already present. */
    private static void addTestCompanion(VaubanComponentProvider provider, String companionName,
            Set<String> present, List<VaubanComponentProvider> result) {
        if (!present.add(companionName)) {
            return;
        }
        var companion = testCompanionOf(provider, companionName);
        if (companion != null) {
            result.add(companion);
        }
    }

    /** The {@code companionName} provider, built through {@code provider}'s own lookup; {@code null} when absent. */
    private static VaubanComponentProvider testCompanionOf(VaubanComponentProvider provider, String companionName) {
        var received = new java.lang.invoke.MethodHandles.Lookup[1];
        try {
            provider.grantModuleLookup(io.vidocq.vauban.api.access.ModuleLookupGrants.newGrant(
                    lookup -> received[0] = lookup));
        } catch (RuntimeException | LinkageError _) {
            return null;
        }
        var lookup = received[0];
        // Only the provider's own lookup, as ModuleLookups requires: another would build a class elsewhere.
        if (lookup == null || lookup.lookupClass() != provider.getClass() || !lookup.hasFullPrivilegeAccess()) {
            return null;
        }
        try {
            var type = lookup.findClass(companionName);
            if (!VaubanComponentProvider.class.isAssignableFrom(type)) {
                return null;
            }
            var constructor = lookup.findConstructor(type, java.lang.invoke.MethodType.methodType(void.class));
            return (VaubanComponentProvider) constructor.invoke();
        } catch (ClassNotFoundException _) {
            return null;
        } catch (Throwable broken) {
            System.getLogger(ComponentProviders.class.getName()).log(System.Logger.Level.WARNING,
                    "Cannot build the test provider " + companionName + " (" + broken.getClass().getName()
                            + "); the beans of the module's test sources are not available.", broken);
            return null;
        }
    }

    private static ClassLoader layerLoaderOf(ClassLoader cl) {
        for (ClassLoader l = cl; l != null; l = l.getParent()) {
            if (l instanceof io.vidocq.vauban.classloader.VaubanClassLoader) return l;
        }
        return null;
    }

    private static boolean definedUnderLayer(VaubanComponentProvider provider, ClassLoader layerLoader) {
        for (ClassLoader l = provider.getClass().getClassLoader(); l != null; l = l.getParent()) {
            if (l == layerLoader) return true;
        }
        return false;
    }

    /**
     * Returns a fresh instance of {@code className} from the first provider that owns it, or
     * {@code null} if none does (so the caller falls back to reflection). A provider that
     * throws is skipped rather than failing startup.
     */
    Object create(String className) {
        for (var provider : providers) {
            try {
                var instance = provider.create(className);
                if (instance != null) return instance;
            } catch (RuntimeException _) {
                // a misbehaving provider must not break container startup — try the next one
            }
        }
        return null;
    }

    /**
     * Returns a fresh instance of {@code className} from the first provider that owns it,
     * passing the container-resolved constructor {@code args} (in declared order) so the
     * {@code new X(args…)} call happens in-module; {@code null} if no provider owns it (the
     * caller then falls back to reflection). A provider that throws is skipped.
     */
    Object create(String className, Object[] args) {
        for (var provider : providers) {
            try {
                var instance = provider.create(className, args);
                if (instance != null) return instance;
            } catch (RuntimeException _) {
                // a misbehaving provider must not break container startup — try the next one
            }
        }
        return null;
    }

    /**
     * Asks each provider to write {@code value} into the {@code fieldName} field of {@code bean}
     * in-module (declaring class {@code className}), returning {@code true} as soon as one performs
     * the assignment, or {@code false} if none owns it (so the caller falls back to reflective field
     * injection). A provider that throws is skipped rather than failing injection.
     */
    boolean injectField(Object bean, String className, String fieldName, Object value) {
        for (var provider : providers) {
            try {
                if (provider.injectField(bean, className, fieldName, value)) return true;
            } catch (RuntimeException _) {
                // a misbehaving provider must not break injection — try the next one
            }
        }
        return false;
    }

    /**
     * Creates the {@code <Bean>_ClientProxy} of a normal-scoped bean in-module (and wires its
     * {@code delegate}) through the first provider that owns it, or {@code null} if none does (so the
     * caller falls back to runtime proxy generation + reflective instantiation). Lets a strict Java Modules
     * app keep its bean package closed — no {@code opens}, no {@code exports}. A provider that throws
     * is skipped rather than failing proxy creation.
     */
    Object createClientProxy(String proxyClassName, java.util.function.Supplier<?> delegate) {
        for (var provider : providers) {
            try {
                var proxy = provider.createClientProxy(proxyClassName, delegate);
                if (proxy != null) return proxy;
            } catch (RuntimeException _) {
                // a misbehaving provider must not break proxy creation — try the next one
            }
        }
        return null;
    }

    /**
     * Invokes a component method through the first provider that owns it, returning its result, or
     * {@link VaubanComponentProvider#NOT_INVOKED} if none does (so the caller falls back to reflective
     * invocation). Unlike {@link #create}/{@link #injectField}, exceptions are NOT swallowed: the
     * owning provider performs a direct call, so a thrown exception is the target method's own and
     * must propagate as-is (CDI producer/observer semantics).
     */
    Object invoke(Object target, String className, String methodId, Object[] args) {
        for (var provider : providers) {
            var result = provider.invoke(target, className, methodId, args);
            if (result != VaubanComponentProvider.NOT_INVOKED) return result;
        }
        return VaubanComponentProvider.NOT_INVOKED;
    }

    boolean isEmpty() {
        return providers.isEmpty();
    }
}
