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
import java.util.Hashtable;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.OperationNotSupportedException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The {@code java:} namespace as an application sees it: {@code new InitialContext()} with the factory
 * installed as the JVM default, the names answered by the running container.
 */
class JavaNamespaceTest {

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

    private VaubanContainer start(Class<?>... beans) {
        var builder = VaubanContainer.builder()
                .addBeanClass(DefaultNamingService.class)
                .addBeanClass(DataSourceNames.class);
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        container = builder.build();
        return container;
    }

    private DataSource orders() {
        return CDI.current().select(DataSource.class, NamedLiteral.of("orders")).get();
    }

    @Test
    void aJdbcNameAnswersTheNamedDataSourceBean() throws NamingException {
        start(OrdersDataSource.class);

        assertSame(orders(), new InitialContext().lookup("java:comp/env/jdbc/orders"));
    }

    @Test
    void theApplicationAndModuleNamespacesAreAliasesOfTheComponentOne() throws NamingException {
        start(OrdersDataSource.class);
        var context = new InitialContext();

        assertSame(orders(), context.lookup("java:app/env/jdbc/orders"));
        assertSame(orders(), context.lookup("java:module/env/jdbc/orders"));
    }

    @Test
    void theEnvironmentIsASubcontextThatResolvesRelativeNames() throws NamingException {
        start(OrdersDataSource.class);

        // The Standard Tag Library's lookup of a data source path.
        var environment = (Context) new InitialContext().lookup("java:comp/env");

        assertSame(orders(), environment.lookup("jdbc/orders"));
        assertEquals("java:comp/env", environment.getNameInNamespace());
    }

    @Test
    void aContextAnswersTheContainerRunningAtTheTimeOfTheLookup() throws NamingException {
        start(OrdersDataSource.class);
        var context = new InitialContext();
        var first = context.lookup("java:comp/env/jdbc/orders");
        container.close();

        start(OrdersDataSource.class);
        var second = context.lookup("java:comp/env/jdbc/orders");

        assertSame(orders(), second);
        assertTrue(!first.equals(second), "a new container, a new data source: " + first + " then " + second);
    }

    @Test
    void withNoContainerRunningALookupFailsAsNoInitialContext() throws NamingException {
        container = null;

        assertThrows(NoInitialContextException.class, () -> new InitialContext().lookup("java:comp/env/jdbc/orders"));
    }

    @Test
    void aLookupBeforeTheContainerStartsDoesNotPreventTheNextOnes() throws NamingException {
        assertThrows(NoInitialContextException.class, () -> new InitialContext().lookup("java:comp/env/jdbc/orders"));

        start(OrdersDataSource.class);

        assertSame(orders(), new InitialContext().lookup("java:comp/env/jdbc/orders"));
    }

    @Test
    void anUnknownNameIsNotFound() {
        start(OrdersDataSource.class);

        assertThrows(NameNotFoundException.class, () -> new InitialContext().lookup("java:comp/env/jdbc/billing"));
        assertThrows(NameNotFoundException.class, () -> new InitialContext().lookup("java:comp/env/mail/orders"));
    }

    @Test
    void aDependentBeanIsRefusedRatherThanLeaked() {
        start(DependentDataSource.class);

        var refused = assertThrows(NamingException.class,
                () -> new InitialContext().lookup("java:comp/env/jdbc/scratch"));
        assertTrue(refused.getMessage().contains("@Dependent"), refused.getMessage());
    }

    @Test
    void aProviderBeanContributesItsNames() throws NamingException {
        start(QueueNames.class);

        assertEquals(QueueNames.ORDERS_QUEUE, new InitialContext().lookup("java:comp/env/jms/orders"));
    }

    @Test
    void twoProvidersAnsweringOneNameMakeItAmbiguous() {
        start(QueueNames.class, RivalQueueNames.class);

        var ambiguous = assertThrows(NamingException.class,
                () -> new InitialContext().lookup("java:comp/env/jms/orders"));
        assertTrue(ambiguous.getMessage().contains("ambiguous"), ambiguous.getMessage());
    }

    @Test
    void theNamespaceIsReadOnly() throws NamingException {
        start(OrdersDataSource.class);
        var context = new InitialContext();

        assertThrows(OperationNotSupportedException.class, () -> context.bind("java:comp/env/jdbc/x", "x"));
        assertThrows(OperationNotSupportedException.class, () -> context.rebind("java:comp/env/jdbc/x", "x"));
        assertThrows(OperationNotSupportedException.class, () -> context.unbind("java:comp/env/jdbc/orders"));
        assertThrows(OperationNotSupportedException.class,
                () -> context.rename("java:comp/env/jdbc/orders", "java:comp/env/jdbc/x"));
        assertThrows(OperationNotSupportedException.class, () -> context.createSubcontext("java:comp/env/x"));
        assertThrows(OperationNotSupportedException.class, () -> context.destroySubcontext("java:comp/env/jdbc"));
    }

    @Test
    void anExplicitFactoryInTheEnvironmentNeverReachesTheNamespace() {
        start(OrdersDataSource.class);
        var environment = new Hashtable<String, Object>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, RefusingFactory.class.getName());

        var answered = assertThrows(NamingException.class,
                () -> new InitialContext(environment).lookup("java:comp/env/jdbc/orders"));
        assertEquals(RefusingFactory.MESSAGE, answered.getMessage());
    }

    @Test
    void anLdapUrlStillGoesToTheJdkLdapProvider() {
        start(OrdersDataSource.class);

        // Nothing listens on port 1: the JDK's LDAP provider fails to connect. Had the name reached the
        // java: namespace, it would have been NameNotFoundException.
        var failure = assertThrows(NamingException.class,
                () -> new InitialContext().lookup("ldap://127.0.0.1:1/cn=orders"));
        assertInstanceOf(CommunicationException.class, failure);
    }
}
