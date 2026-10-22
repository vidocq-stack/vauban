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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import java.util.Map;
import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The {@code Context} contract beyond a string lookup: names, links, environment, and what it refuses. */
class CdiContextTest {

    private final VaubanContainer container = VaubanContainer.builder()
            .addBeanClass(DefaultNamingService.class)
            .addBeanClass(DataSourceNames.class)
            .addBeanClass(OrdersDataSource.class)
            .build();

    private final Context root = new CdiContext(null, "");

    @AfterEach
    void stop() {
        container.close();
    }

    private static DataSource orders() {
        return CDI.current().select(DataSource.class, NamedLiteral.of("orders")).get();
    }

    private static Name name(String name) throws NamingException {
        return new CompositeName(name);
    }

    @Test
    void aNameAndALinkResolveAsTheirString() throws NamingException {
        assertSame(orders(), root.lookup(name("java:comp/env/jdbc/orders")));
        assertSame(orders(), root.lookupLink("java:comp/env/jdbc/orders"));
        assertSame(orders(), root.lookupLink(name("java:comp/env/jdbc/orders")));
    }

    @Test
    void anEmptyNameIsAnotherContextOnTheSameBase() throws NamingException {
        var environment = (Context) root.lookup("java:comp/env");

        var same = assertInstanceOf(Context.class, environment.lookup(""));
        assertEquals("java:comp/env", same.getNameInNamespace());
        assertSame(orders(), same.lookup("jdbc/orders"));
    }

    @Test
    void aSubcontextResolvesAnAbsoluteNameAsItIs() throws NamingException {
        var environment = (Context) root.lookup("java:comp/env");

        assertSame(orders(), environment.lookup("java:app/env/jdbc/orders"));
    }

    @Test
    void everyWriteThroughANameIsRefused() throws NamingException {
        var orders = name("java:comp/env/jdbc/orders");
        var other = name("java:comp/env/jdbc/other");

        assertThrows(OperationNotSupportedException.class, () -> root.bind(other, "x"));
        assertThrows(OperationNotSupportedException.class, () -> root.rebind(orders, "x"));
        assertThrows(OperationNotSupportedException.class, () -> root.unbind(orders));
        assertThrows(OperationNotSupportedException.class, () -> root.rename(orders, other));
        assertThrows(OperationNotSupportedException.class, () -> root.createSubcontext(other));
        assertThrows(OperationNotSupportedException.class, () -> root.destroySubcontext(other));
    }

    @Test
    void theNamespaceCannotBeListed() throws NamingException {
        var environment = name("java:comp/env");

        assertThrows(OperationNotSupportedException.class, () -> root.list("java:comp/env"));
        assertThrows(OperationNotSupportedException.class, () -> root.list(environment));
        assertThrows(OperationNotSupportedException.class, () -> root.listBindings("java:comp/env"));
        assertThrows(OperationNotSupportedException.class, () -> root.listBindings(environment));
    }

    @Test
    void namesAreCompositeNames() throws NamingException {
        assertEquals(name("jdbc/orders"), root.getNameParser("").parse("jdbc/orders"));
        assertEquals(name("jdbc/orders"), root.getNameParser(name("")).parse("jdbc/orders"));
        assertEquals(name("java:comp/env/jdbc/orders"), root.composeName(name("jdbc/orders"), name("java:comp/env")));
        assertEquals("java:comp/env/jdbc/orders", root.composeName("jdbc/orders", "java:comp/env"));
        assertEquals("jdbc/orders", root.composeName("jdbc/orders", ""));
    }

    @Test
    void theEnvironmentIsTheContextsOwnCopy() throws NamingException {
        var source = Map.of("vendor.timeout", "5");
        var context = new CdiContext(source, "");

        assertNull(context.addToEnvironment("vendor.retries", "2"));
        assertEquals("5", context.removeFromEnvironment("vendor.timeout"));
        assertEquals(Map.of("vendor.retries", "2"), context.getEnvironment());
        assertEquals(Map.of("vendor.timeout", "5"), source);
        assertTrue(root.getEnvironment().isEmpty());
        context.close();
        assertEquals("", context.getNameInNamespace());
    }
}
