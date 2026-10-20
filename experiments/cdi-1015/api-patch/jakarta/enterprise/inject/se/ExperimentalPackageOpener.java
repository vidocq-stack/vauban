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
package jakarta.enterprise.inject.se;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Experimental CDI #1015 relay bound by the API to its selected SE initializer.
 *
 * <p>The provider seals the application policy at startup. This relay opens a
 * consented package only to that provider and returns the additional recipients
 * authorized by the application. The provider performs those further relays.
 * No read edge or export is changed here.</p>
 *
 * <p>Policy belongs to one initializer, while resulting openings belong to
 * modules: they are shared by containers using the same module objects and are
 * not revoked at shutdown. This is not a sandbox against code allowed to start
 * another container and configure its own policy.</p>
 */
public final class ExperimentalPackageOpener {
    private static final StackWalker CALLERS =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final SeContainerInitializer initializer;
    private final Module providerModule;
    private final Set<Module> generalRecipients = new HashSet<>();
    private final Map<SourcePackage, Set<Module>> packageRecipients = new HashMap<>();
    private boolean sealed;

    // Only code inside the CDI API's package can bind an opener.
    ExperimentalPackageOpener(SeContainerInitializer initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer");
        this.providerModule = initializer.getClass().getModule();
    }

    synchronized void authorize(Module recipient) {
        requireUnsealed();
        generalRecipients.add(Objects.requireNonNull(recipient, "recipient"));
    }

    synchronized void authorize(Module recipient, Module source, String... packages) {
        requireUnsealed();
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(packages, "packages");
        if (packages.length == 0) {
            throw new IllegalArgumentException("At least one package must be authorized");
        }
        Set<SourcePackage> requested = new HashSet<>();
        for (String packageName : packages) {
            Objects.requireNonNull(packageName, "packageName");
            if (packageName.isEmpty() || !source.getPackages().contains(packageName)) {
                throw new IllegalArgumentException("Source module does not contain package " + packageName);
            }
            requested.add(new SourcePackage(source, packageName));
        }
        // Validate the entire request before recording any part of it.
        for (SourcePackage requestedPackage : requested) {
            packageRecipients.computeIfAbsent(requestedPackage, ignored -> new HashSet<>()).add(recipient);
        }
    }

    synchronized void seal(SeContainerInitializer requester) {
        if (requester != initializer) {
            throw new SecurityException("Opening policy belongs to a different CDI initializer");
        }
        sealed = true;
    }

    /**
     * Returns the actual module of the initializer selected by CDI.
     *
     * @return the provider module
     */
    public Module providerModule() {
        return providerModule;
    }

    /**
     * Opens a consented package to the provider and returns authorized delegates.
     *
     * <p>The immediate caller and full privilege lookup must both belong to the
     * selected provider module. The package must already be open to the actual
     * {@code jakarta.cdi} module containing this class. Additional recipients
     * receive no opening until the provider relays it itself.</p>
     *
     * @param providerLookup a full privilege lookup created inside the provider
     * @param source the module containing the bean package
     * @param packageName the exact package to open
     * @return an immutable set of application-authorized recipient modules
     * @throws IllegalStateException if the provider has not sealed the policy
     * @throws SecurityException if the caller is unauthorized or the source
     *         package is not open to CDI
     * @throws IllegalArgumentException if source does not contain the package
     * @throws NullPointerException if an argument is null
     */
    public synchronized Set<Module> openPackage(MethodHandles.Lookup providerLookup, Module source,
            String packageName) {
        if (!sealed) {
            throw new IllegalStateException("The CDI provider must seal its access policy before requesting openings");
        }
        Objects.requireNonNull(providerLookup, "providerLookup");
        Module callerModule = CALLERS.getCallerClass().getModule();
        if (!providerModule.isNamed()
                || !providerLookup.hasFullPrivilegeAccess()
                || providerLookup.lookupClass().getModule() != providerModule
                || callerModule != providerModule) {
            throw new SecurityException("Only the selected CDI provider module may request an opening");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(packageName, "packageName");
        if (!source.getPackages().contains(packageName)) {
            throw new IllegalArgumentException("Source module does not contain package " + packageName);
        }
        if (!source.isOpen(packageName, ExperimentalPackageOpener.class.getModule())) {
            throw new SecurityException("Source package is not open to jakarta.cdi: " + packageName);
        }
        Set<Module> recipients = new HashSet<>(generalRecipients);
        recipients.addAll(packageRecipients.getOrDefault(new SourcePackage(source, packageName), Set.of()));
        Set<Module> result = Set.copyOf(recipients);
        source.addOpens(packageName, providerModule);
        return result;
    }

    private void requireUnsealed() {
        if (sealed) {
            throw new IllegalStateException("Package access policy is sealed after CDI initialization starts");
        }
    }

    private record SourcePackage(Module module, String name) {
    }
}
