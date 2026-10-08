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
package io.vidocq.vauban.api;

import io.vidocq.vauban.api.access.ModuleLookupGrants;
import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The container's receipt for a generated provider's lookup
 * ({@link VaubanComponentProvider#grantModuleLookup}).
 *
 * <p>Only the container creates grants: the factory lives in {@code io.vidocq.vauban.api.access},
 * exported to {@code io.vidocq.vauban.core} alone. Code that obtains a provider through
 * {@link java.util.ServiceLoader} therefore cannot make it hand its lookup over.</p>
 */
public final class ModuleLookupGrant {

    static {
        ModuleLookupGrants.install(ModuleLookupGrant::new);
    }

    private final Consumer<MethodHandles.Lookup> receiver;

    private ModuleLookupGrant(Consumer<MethodHandles.Lookup> receiver) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    /**
     * Passes the provider's lookup to the container.
     *
     * @param lookup {@code MethodHandles.lookup()} taken in the provider
     * @throws IllegalArgumentException if the lookup does not have full privilege access
     */
    public void accept(MethodHandles.Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        if (!lookup.hasFullPrivilegeAccess()) {
            throw new IllegalArgumentException("a module lookup grant needs a full-privilege lookup, got " + lookup);
        }
        receiver.accept(lookup);
    }
}
