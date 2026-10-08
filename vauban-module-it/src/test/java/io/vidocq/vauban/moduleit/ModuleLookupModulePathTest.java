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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.core.access.ModuleLookups;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extension obtains, for a class the container manages, a full-privilege lookup of that class
 * supplied by the APT-generated {@code _VaubanComponents} of its package — module path, no opens.
 *
 * <p>The test classes are patched into {@code io.vidocq.vauban.moduleit}; the Surefire argLine
 * exports {@code io.vidocq.vauban.core.access} to this module for the test only (the package is
 * exported to the trusted extension modules alone).</p>
 */
@DisplayName("ModuleLookups — a managed class's lookup from its package's generated provider (processor path)")
class ModuleLookupModulePathTest {

    private static final MethodType SECRET = MethodType.methodType(String.class, String.class);

    @Test
    @DisplayName("a managed bean's private method is reachable through the lookup; nothing is opened")
    void managedClassLookupReachesPrivateMembers() throws Throwable {
        Module app = SecretKeeper.class.getModule();
        Module core = VaubanContainer.class.getModule();
        assertTrue(app.isNamed());
        assertFalse(app.isOpen(SecretKeeper.class.getPackageName(), core),
                "the package must not be open to the container");

        try (var container = VaubanContainer.builder().addBeanClass(SecretKeeper.class).build()) {
            var lookup = ModuleLookups.lookupFor(SecretKeeper.class).orElseThrow();
            assertEquals(SecretKeeper.class, lookup.lookupClass());
            assertTrue(lookup.hasFullPrivilegeAccess());

            var secret = lookup.findSpecial(SecretKeeper.class, "secret", SECRET, SecretKeeper.class);
            var bean = container.select(SecretKeeper.class);
            assertEquals("secret of duke", (String) secret.invoke(bean, "duke"));
        }
    }

    @Test
    @DisplayName("no lookup for a class the container does not manage, nor once it is closed")
    void noLookupOutsideTheDeployment() {
        try (var container = VaubanContainer.builder().addBeanClass(SecretKeeper.class).build()) {
            assertTrue(ModuleLookups.lookupFor(NotABean.class).isEmpty(), "not a managed class");
            assertTrue(ModuleLookups.lookupFor(String.class).isEmpty(), "not a managed class");
        }
        assertTrue(ModuleLookups.lookupFor(SecretKeeper.class).isEmpty(), "the container is closed");
    }
}
