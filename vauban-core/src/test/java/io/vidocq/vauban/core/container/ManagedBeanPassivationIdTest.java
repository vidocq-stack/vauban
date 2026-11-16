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
package io.vidocq.vauban.core.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.PassivationCapable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bean's passivation id is its {@code BeanId}: built from names only, it is the same in two containers built from
 * the same classes, so a session value written by one is found by the other (Vidocq dev swap, foy#114).
 */
@DisplayName("ManagedBean: the passivation id is the BeanId")
class ManagedBeanPassivationIdTest {

    @ApplicationScoped
    public static class Shelf {
    }

    public static class Book {
        final String title;

        Book(String title) {
            this.title = title;
        }
    }

    public static class Pen {
    }

    @Dependent
    public static class Stationery {

        @Produces
        Book book() {
            return new Book("produced");
        }

        @Produces
        Pen pen = new Pen();
    }

    private VaubanContainer container;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder().addBeanClass(Shelf.class).addBeanClass(Stationery.class).build();
    }

    @AfterEach
    void stop() {
        container.close();
    }

    private String passivationIdOf(Class<?> type, String expectedId) {
        var beanManager = container.getBeanManager();
        Bean<?> bean = beanManager.resolve(beanManager.getBeans(type));
        var managed = assertInstanceOf(ManagedBean.class, bean);
        var id = assertInstanceOf(PassivationCapable.class, bean).getId();

        assertEquals(managed.descriptor().id().value(), id, "the id is the BeanId");
        assertSame(bean, beanManager.getPassivationCapableBean(expectedId), "the bean manager finds it by that id");
        return id;
    }

    @Test
    @DisplayName("a managed bean: its class name")
    void managedBean() {
        assertEquals(Shelf.class.getName(), passivationIdOf(Shelf.class, Shelf.class.getName()));
    }

    @Test
    @DisplayName("a producer method: Class#method")
    void producerMethod() {
        var expected = Stationery.class.getName() + "#book";

        assertEquals(expected, passivationIdOf(Book.class, expected));
    }

    @Test
    @DisplayName("a producer field: Class.field")
    void producerField() {
        var expected = Stationery.class.getName() + ".pen";

        assertEquals(expected, passivationIdOf(Pen.class, expected));
    }
}
