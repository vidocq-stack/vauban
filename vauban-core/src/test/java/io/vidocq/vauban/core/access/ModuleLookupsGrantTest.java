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

import io.vidocq.vauban.api.ModuleLookupGrant;
import io.vidocq.vauban.api.VaubanComponentProvider;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModuleLookups} hands out a managed class's lookup only through a provider of the same package that
 * grants its own lookup: a provider whose grant fails, or a managed class of another module, gets none.
 */
class ModuleLookupsGrantTest {

    /** A managed class of this package. */
    static final class Managed {
    }

    /** A provider that fails when asked for its lookup. */
    static final class FailingGrant implements VaubanComponentProvider {
        @Override
        public Object create(String className) {
            return null;
        }

        @Override
        public void grantModuleLookup(ModuleLookupGrant grant) {
            throw new IllegalStateException("no grant");
        }
    }

    /** A provider that grants its own full-privilege lookup. */
    static final class OwnGrant implements VaubanComponentProvider {
        @Override
        public Object create(String className) {
            return null;
        }

        @Override
        public void grantModuleLookup(ModuleLookupGrant grant) {
            grant.accept(MethodHandles.lookup());
        }
    }

    @Test
    void aProviderWhoseGrantFailsGivesNoLookup() {
        try (var _ = ModuleLookups.register(List.of(new FailingGrant()), List.of(Managed.class))) {
            assertTrue(ModuleLookups.lookupFor(Managed.class).isEmpty());
        }
    }

    @Test
    void aManagedClassOfAnotherModuleGetsNoLookupFromThisPackagesProvider() {
        try (var _ = ModuleLookups.register(List.of(new OwnGrant()), List.of(String.class, Managed.class))) {
            assertTrue(ModuleLookups.lookupFor(String.class).isEmpty());
            assertTrue(ModuleLookups.lookupFor(Managed.class).isPresent());
        }
    }
}
