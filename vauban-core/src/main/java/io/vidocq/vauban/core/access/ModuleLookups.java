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
package io.vidocq.vauban.core.access;

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.api.access.ModuleLookupGrants;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Full-privilege lookups of the classes a container manages, for the extensions that need to
 * reach a member the application did not open (for instance a {@code private}
 * {@code @Fallback(fallbackMethod = ...)} method of MicroProfile Fault Tolerance).
 *
 * <h2>Trust model</h2>
 * <ul>
 *   <li><b>Where a lookup comes from.</b> Only from the generated {@code _VaubanComponents} of the
 *       class's own package — written by the Vauban processor, or by the Maven plugin into the
 *       package of the dependency it completes. The provider hands over
 *       {@code MethodHandles.lookup()} taken in itself
 *       ({@link VaubanComponentProvider#grantModuleLookup}), and only through a grant the
 *       container alone can create; a lookup whose lookup class is not that provider is refused.
 *       The application declares nothing and opens nothing: shipping the generated provider is
 *       the consent, as it already is for in-module instantiation and injection.</li>
 *   <li><b>What is handed out.</b> {@code privateLookupIn(managedClass, providerLookup)}: a lookup
 *       on one class that the running container (or the container being built) manages, never on
 *       an arbitrary class, and nothing once that container is closed. A class whose package has
 *       no generated provider gets nothing; the caller keeps its own fallback (an
 *       {@code opens}).</li>
 *   <li><b>Who may ask.</b> This package is exported only to the extension modules trusted with
 *       such lookups (see {@code module-info}); a new consumer is added there, by name, in review.
 *       On the class path every module is unnamed and nothing is encapsulated anyway.</li>
 * </ul>
 *
 * <p>{@link #register} is the container's side: the builder registers a deployment as soon as it
 * has loaded the providers (so build compatible extensions can already ask, during
 * {@code @Enhancement}), and the container closes the registration when it shuts down.</p>
 */
public final class ModuleLookups {

    private static final List<Deployment> DEPLOYMENTS = new CopyOnWriteArrayList<>();

    private ModuleLookups() {
    }

    /**
     * A full-privilege lookup on {@code managedClass}, supplied by the generated provider of its
     * package.
     *
     * @param managedClass a class that a running container, or the container being built, manages
     * @return the lookup, or empty if no container manages the class or its package has no
     *         generated provider granting one
     */
    public static Optional<MethodHandles.Lookup> lookupFor(Class<?> managedClass) {
        for (var deployment : DEPLOYMENTS) {
            var lookup = deployment.lookupFor(managedClass);
            if (lookup.isPresent()) {
                return lookup;
            }
        }
        return Optional.empty();
    }

    /**
     * Registers a deployment: its generated providers and the classes it manages. Container side
     * only; close the returned registration when the container shuts down or fails to build.
     */
    public static Registration register(List<VaubanComponentProvider> providers, Collection<Class<?>> managedClasses) {
        var deployment = new Deployment(List.copyOf(providers), managedClasses);
        DEPLOYMENTS.add(deployment);
        return deployment;
    }

    /** A registered deployment; closing it withdraws its lookups. */
    public sealed interface Registration extends AutoCloseable permits Deployment {

        /** A class the deployment manages from now on (one a build compatible extension added). */
        void addManagedClass(Class<?> managedClass);

        @Override
        void close();
    }

    private static final class Deployment implements Registration {

        /** Marks a provider that granted no usable lookup. */
        private static final MethodHandles.Lookup NONE = MethodHandles.publicLookup();

        private final List<VaubanComponentProvider> providers;
        private final Set<Class<?>> managedClasses;
        private final Map<VaubanComponentProvider, MethodHandles.Lookup> granted = new ConcurrentHashMap<>();

        Deployment(List<VaubanComponentProvider> providers, Collection<Class<?>> managedClasses) {
            this.providers = providers;
            this.managedClasses = ConcurrentHashMap.newKeySet();
            this.managedClasses.addAll(managedClasses);
        }

        @Override
        public void addManagedClass(Class<?> managedClass) {
            managedClasses.add(managedClass);
        }

        @Override
        public void close() {
            DEPLOYMENTS.remove(this);
        }

        Optional<MethodHandles.Lookup> lookupFor(Class<?> managedClass) {
            if (!managedClasses.contains(managedClass)) {
                return Optional.empty();
            }
            for (var provider : providers) {
                var type = provider.getClass();
                if (type.getModule() != managedClass.getModule()
                        || type.getClassLoader() != managedClass.getClassLoader()
                        || !type.getPackageName().equals(managedClass.getPackageName())) {
                    continue;
                }
                var lookup = granted.computeIfAbsent(provider, Deployment::grantOf);
                if (lookup == NONE) {
                    continue;
                }
                try {
                    // Same module as the provider's lookup: needs no read edge and no opens.
                    return Optional.of(MethodHandles.privateLookupIn(managedClass, lookup));
                } catch (IllegalAccessException unexpected) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }

        private static MethodHandles.Lookup grantOf(VaubanComponentProvider provider) {
            var received = new MethodHandles.Lookup[1];
            try {
                provider.grantModuleLookup(ModuleLookupGrants.newGrant(lookup -> received[0] = lookup));
            } catch (RuntimeException | LinkageError misbehaving) {
                return NONE;
            }
            var lookup = received[0];
            // Only the provider's own lookup: anything else would let a provider hand out
            // another class's access under its package's name.
            if (lookup == null || lookup.lookupClass() != provider.getClass() || !lookup.hasFullPrivilegeAccess()) {
                return NONE;
            }
            return lookup;
        }
    }
}
