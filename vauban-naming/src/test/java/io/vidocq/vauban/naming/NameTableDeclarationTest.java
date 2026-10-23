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
package io.vidocq.vauban.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import javax.naming.InvalidNameException;
import org.junit.jupiter.api.Test;

/** The table of names a container's declaring beans build, and the names that are contexts in it. */
class NameTableDeclarationTest {

    @Test
    void aDeclaringBeanBindingAnInvalidNameIsNamedInTheFailure() {
        try (var _ = VaubanContainer.builder().addBeanClass(InvalidNames.class).build()) {
            var bindings = CDI.current().select(JndiBindings.class, Any.Literal.INSTANCE);

            var failure = assertThrows(IllegalArgumentException.class, () -> NameTable.of(bindings));

            assertEquals(InvalidNames.class.getName() + " declares an invalid name: jdbc/orders is outside java:comp,"
                    + " java:module, java:app and java:global", failure.getMessage());
            assertInstanceOf(InvalidNameException.class, failure.getCause());
        }
    }

    @Test
    void aBoundNameIsNoContextButItsAncestorsAre() throws InvalidNameException {
        try (var _ = VaubanContainer.builder().addBeanClass(OrdersNames.class).build()) {
            var table = NameTable.of(CDI.current().select(JndiBindings.class, Any.Literal.INSTANCE));

            assertFalse(table.isContext(JndiName.parse("java:comp/env/jdbc/orders")));
            assertTrue(table.isContext(JndiName.parse("java:comp/env/jdbc")));
            assertTrue(table.isContext(JndiName.parse("java:comp/env")));
            assertFalse(table.isContext(JndiName.parse("java:comp/env/jms")));
        }
    }
}
