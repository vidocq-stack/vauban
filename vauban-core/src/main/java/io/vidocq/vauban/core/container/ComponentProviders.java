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
import java.util.ServiceLoader;

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
final class ComponentProviders {

    private final List<VaubanComponentProvider> providers;

    ComponentProviders(List<VaubanComponentProvider> providers) {
        this.providers = List.copyOf(providers);
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
        } catch (Throwable _) {
            // misconfigured/absent services — keep the explicit providers, fall back otherwise
        }
        return new ComponentProviders(all);
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
