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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Names the application declares: a {@link JndiBindings} bean binds each one to a bean or to another name, in
 * one of the four namespaces, which never fall back on one another.
 */
class DeclaredNamesTest {

    private VaubanContainer container;

    @BeforeAll
    static void installTheFactory() {
        assertTrue(JavaNaming.installAsDefault(Thread.currentThread().getContextClassLoader()),
                "nothing else chose a factory in this JVM");
    }

    @AfterAll
    static void uninstallTheFactory() {
        JavaNaming.uninstall();
    }

    @AfterEach
    void stop() {
        if (container != null) {
            container.close();
        }
    }

    private void start(Class<?>... beans) {
        var builder = VaubanContainer.builder().addBeanClass(DefaultNamingService.class);
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        container = builder.build();
    }

    private static DataSource orders() {
        return CDI.current().select(DataSource.class, NamedLiteral.of("orders")).get();
    }

    private static Object lookup(String name) throws NamingException {
        return new InitialContext().lookup(name);
    }

    @Test
    void aDeclaredBeanNameAnswersTheBean() throws NamingException {
        start(OrdersDataSource.class, OrdersNames.class);

        assertSame(orders(), lookup("java:comp/env/jdbc/orders"));
    }

    @Test
    void aDeclaredNameWithoutQualifierAnswersTheDefaultBean() throws NamingException {
        start(DefaultDataSource.class, DefaultNames.class);

        assertSame(CDI.current().select(DataSource.class).get(), lookup("java:comp/env/jdbc/default"));
    }

    @Test
    void aLinkAnswersWhatItsTargetAnswers() throws NamingException {
        start(OrdersDataSource.class, OrdersNames.class);

        assertSame(orders(), lookup("java:app/env/jdbc/orders"));
    }

    @Test
    void aChainOfLinksIsFollowed() throws NamingException {
        start(OrdersDataSource.class, OrdersNames.class, LinkNames.class);

        assertSame(orders(), lookup("java:comp/env/jdbc/older"));
    }

    @Test
    void theNamespacesAreDistinct() throws NamingException {
        start(OrdersDataSource.class, OrdersNames.class);
        var context = new InitialContext();

        assertThrows(NameNotFoundException.class, () -> context.lookup("java:module/env/jdbc/orders"));
        assertThrows(NameNotFoundException.class, () -> context.lookup("java:global/jdbc/orders"));
    }

    @Test
    void aGlobalNameIsServed() throws NamingException {
        start(OrdersDataSource.class, GlobalNames.class);

        assertSame(orders(), lookup("java:global/orders"));
    }

    @Test
    void aDeclaredNamesParentsAreContexts() throws NamingException {
        start(OrdersDataSource.class, OrdersNames.class);

        var jdbc = assertInstanceOf(Context.class, lookup("java:comp/env/jdbc"));
        assertSame(orders(), jdbc.lookup("orders"));
        assertEquals("java:comp/env/jdbc", jdbc.getNameInNamespace());
    }

    @Test
    void theEnvironmentsAreContextsWithNothingDeclared() throws NamingException {
        start();

        for (String name : new String[] {"java:comp", "java:comp/env", "java:module/env", "java:app/env", "java:global"}) {
            assertInstanceOf(Context.class, lookup(name), name);
        }
    }

    @Test
    void aProviderAnswersAnUndeclaredName() throws NamingException {
        start(CountingQueueNames.class);

        assertEquals(QueueNames.ORDERS_QUEUE, lookup("java:comp/env/jms/orders"));
    }

    @Test
    void aProviderIsNotAskedForADeclaredName() throws NamingException {
        start(OrdersDataSource.class, OrdersNames.class, CountingQueueNames.class);
        int asked = CountingQueueNames.asked;

        assertSame(orders(), lookup("java:comp/env/jdbc/orders"));
        assertEquals(asked, CountingQueueNames.asked);
    }

    @Test
    void aProviderCanDeclareAContext() throws NamingException {
        start(CountingQueueNames.class);

        var jms = assertInstanceOf(Context.class, lookup("java:comp/env/jms"));
        assertEquals(QueueNames.ORDERS_QUEUE, jms.lookup("orders"));
    }

    @Test
    void aDependentBindingsBeanIsDestroyedOnceItHasBound() throws NamingException {
        int destroyed = DependentNames.destroyed;
        start(OrdersDataSource.class, DependentNames.class);

        assertSame(orders(), lookup("java:comp/env/jdbc/dependent"));
        assertEquals(destroyed + 1, DependentNames.destroyed);
    }

    @Test
    void aLookupBeforeStartupFindsTheDeclaredNames() {
        EarlyLookup.found = null;
        start(OrdersDataSource.class, OrdersNames.class, EarlyLookup.class);

        assertSame(orders(), EarlyLookup.found);
    }

    @Test
    void aNewContainerAnswersItsOwnDeclarations() throws NamingException {
        var context = new InitialContext();
        start(OrdersDataSource.class, FirstNames.class);
        assertSame(orders(), context.lookup("java:comp/env/a"));
        container.close();

        start(OrdersDataSource.class, SecondNames.class);

        assertThrows(NameNotFoundException.class, () -> context.lookup("java:comp/env/a"));
        assertSame(orders(), context.lookup("java:comp/env/b"));
    }
}
