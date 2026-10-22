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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.naming.InvalidNameException;
import org.junit.jupiter.api.Test;

/** A {@code java:} name: one of the four namespaces of the Jakarta EE Platform, and a path in it. */
class JndiNameTest {

    @Test
    void aNameIsItsNamespaceAndItsPath() throws InvalidNameException {
        var name = JndiName.parse("java:comp/env/jdbc/orders");

        assertEquals(new JndiName(Namespace.COMP, "env/jdbc/orders"), name);
        assertEquals("java:comp/env/jdbc/orders", name.toString());
    }

    @Test
    void eachOfTheFourNamespacesIsRecognised() throws InvalidNameException {
        assertEquals(Namespace.COMP, JndiName.parse("java:comp/x").namespace());
        assertEquals(Namespace.MODULE, JndiName.parse("java:module/x").namespace());
        assertEquals(Namespace.APP, JndiName.parse("java:app/x").namespace());
        assertEquals(Namespace.GLOBAL, JndiName.parse("java:global/x").namespace());
        assertEquals("java:global", Namespace.GLOBAL.prefix());
    }

    @Test
    void aTrailingSlashIsDropped() throws InvalidNameException {
        assertEquals(JndiName.parse("java:comp/env"), JndiName.parse("java:comp/env/"));
    }

    @Test
    void aNamespaceAloneIsARoot() throws InvalidNameException {
        var root = JndiName.parse("java:comp");

        assertTrue(root.isRoot());
        assertEquals("java:comp", root.toString());
        assertFalse(JndiName.parse("java:comp/env").isRoot());
    }

    @Test
    void aPathKeepsItsDots() throws InvalidNameException {
        assertEquals("env/com.acme.Orders/ds", JndiName.parse("java:comp/env/com.acme.Orders/ds").path());
    }

    @Test
    void aNameOutsideTheFourNamespacesIsInvalid() {
        assertThrows(InvalidNameException.class, () -> JndiName.parse("java:other/x"));
        assertThrows(InvalidNameException.class, () -> JndiName.parse("comp/env/x"));
        assertThrows(InvalidNameException.class, () -> JndiName.parse("java:"));
        assertThrows(InvalidNameException.class, () -> JndiName.parse("java:compx/y"));
        assertThrows(InvalidNameException.class, () -> JndiName.parse("java:comp//x"));
    }

    @Test
    void aChildIsTheRelativeNameUnderIt() throws InvalidNameException {
        assertEquals(JndiName.parse("java:comp/env/jdbc/orders"), JndiName.parse("java:comp").child("env/jdbc/orders"));
        assertEquals(JndiName.parse("java:comp/env/x"), JndiName.parse("java:comp/env").child("x"));
    }

    @Test
    void startsWithComparesWholeSegments() throws InvalidNameException {
        var orders = JndiName.parse("java:comp/env/jdbc/orders");

        assertTrue(orders.startsWith(JndiName.parse("java:comp/env")));
        assertTrue(orders.startsWith(JndiName.parse("java:comp")));
        assertFalse(JndiName.parse("java:comp/env").startsWith(JndiName.parse("java:comp/en")));
        assertFalse(orders.startsWith(JndiName.parse("java:app/env")));
    }
}
