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
import java.util.Objects;

/**
 * An experimental opening relay for the investigation of jakartaee/cdi#1015.
 *
 * <p>This class is added to a local copy of the real CDI API jar for the
 * experiment. It is not an API defined by CDI. Its own module must be
 * {@code jakarta.cdi}, so a library's {@code opens ... to jakarta.cdi}
 * declaration authorizes the call to {@link Module#addOpens} made here.</p>
 *
 * <p>The factory uses the CDI SE API's provider selection and authenticates
 * the requesting provider module. The caller must use the returned
 * {@linkplain #initializer() initializer} to start the experiment's container.
 * The standard selection rejects multiple available CDI SE implementations;
 * this experiment does not add a selection policy for that case.</p>
 *
 * <p>Authorization is at module granularity. An opening is shared by every
 * container using those same {@link Module} objects and survives container
 * shutdown. This helper neither isolates container instances nor revokes
 * openings. Delegation to an extension requires a separate, explicit
 * application authorization enforced by the container adapter.</p>
 */
public final class ExperimentalPackageOpener {
    private static final StackWalker CALLERS =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final SeContainerInitializer initializer;
    private final Module providerModule;

    private ExperimentalPackageOpener(SeContainerInitializer initializer) {
        this.initializer = initializer;
        this.providerModule = initializer.getClass().getModule();
    }

    /**
     * Selects the CDI SE provider and creates a relay usable by that module.
     *
     * @param providerLookup a full privilege lookup created inside the provider
     * @return the relay bound to the selected initializer and its actual module
     * @throws SecurityException if the lookup or immediate caller does not
     *         belong to the selected provider module
     * @throws IllegalStateException if CDI SE cannot select one provider
     */
    public static ExperimentalPackageOpener forProvider(MethodHandles.Lookup providerLookup) {
        Objects.requireNonNull(providerLookup, "providerLookup");
        Module callerModule = CALLERS.getCallerClass().getModule();
        var selected = SeContainerInitializer.newInstance();
        Module selectedModule = selected.getClass().getModule();
        requireProvider(providerLookup, callerModule, selectedModule);
        return new ExperimentalPackageOpener(selected);
    }

    /**
     * Returns the exact initializer selected when this relay was created.
     *
     * @return the initializer to configure and initialize
     */
    public SeContainerInitializer initializer() {
        return initializer;
    }

    /**
     * Returns the actual selected provider module, rather than a module name.
     *
     * @return the module receiving openings
     */
    public Module providerModule() {
        return providerModule;
    }

    /**
     * Relays an opening available to the CDI API to the selected provider.
     *
     * <p>Caller authentication and source validation precede the module graph
     * mutation. This method grants no access to an extension module and does
     * not change either module's read edges.</p>
     *
     * @param providerLookup a full privilege lookup created inside the provider
     * @param source the module containing the bean package
     * @param packageName the exact package to open
     * @throws SecurityException if the lookup or immediate caller is not from
     *         the selected provider module, or the package is not open to CDI
     * @throws IllegalArgumentException if the source does not contain the package
     */
    public void openPackage(MethodHandles.Lookup providerLookup, Module source, String packageName) {
        Objects.requireNonNull(providerLookup, "providerLookup");
        requireProvider(providerLookup, CALLERS.getCallerClass().getModule(), providerModule);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(packageName, "packageName");
        if (!source.getPackages().contains(packageName)) {
            throw new IllegalArgumentException("Source module does not contain package " + packageName);
        }
        if (!source.isOpen(packageName, ExperimentalPackageOpener.class.getModule())) {
            throw new SecurityException("Source package is not open to jakarta.cdi: " + packageName);
        }
        source.addOpens(packageName, providerModule);
    }

    private static void requireProvider(MethodHandles.Lookup lookup, Module callerModule, Module selectedModule) {
        if (!selectedModule.isNamed()
                || !lookup.hasFullPrivilegeAccess()
                || lookup.lookupClass().getModule() != selectedModule
                || callerModule != selectedModule) {
            throw new SecurityException("Only the selected CDI provider module may request an opening");
        }
    }
}
