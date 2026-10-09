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
package io.vidocq.vauban.api.access;

import io.vidocq.vauban.api.ModuleLookupGrant;
import java.lang.invoke.MethodHandles;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Creates {@link ModuleLookupGrant}s. This package is exported to {@code io.vidocq.vauban.core}
 * only, so the container is the one module that can ask a generated provider for its lookup.
 */
public final class ModuleLookupGrants {

    private static volatile Function<Consumer<MethodHandles.Lookup>, ModuleLookupGrant> factory;

    private ModuleLookupGrants() {
    }

    /**
     * Called once, by {@link ModuleLookupGrant}'s static initializer, to hand over its private
     * constructor.
     */
    public static void install(Function<Consumer<MethodHandles.Lookup>, ModuleLookupGrant> constructor) {
        if (factory != null) {
            throw new IllegalStateException("ModuleLookupGrant is already installed");
        }
        factory = constructor;
    }

    /** A grant that passes the lookup a provider accepts to {@code receiver}. */
    public static ModuleLookupGrant newGrant(Consumer<MethodHandles.Lookup> receiver) {
        if (factory == null) {
            try {
                // Runs ModuleLookupGrant's static initializer, which installs the factory.
                MethodHandles.lookup().ensureInitialized(ModuleLookupGrant.class);
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return factory.apply(receiver);
    }
}
