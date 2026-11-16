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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.PassivationCapable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Overloaded producer methods, two {@code @Produces} methods of one class with the same name: each is a bean of its
 * own, with an id of its own.
 */
@DisplayName("Overloaded producer methods are two beans")
class OverloadedProducerTest {

    @Test
    @DisplayName("each overloaded producer method produces its own type")
    void eachOverloadProducesItsType() {
        try (var container = VaubanContainer.builder().addBeanClass(Kitchen.class).build()) {
            assertEquals("make()", container.select(Spoon.class).from());
            assertEquals("make(Spoon)", container.select(Fork.class).from());
        }
    }

    @Test
    @DisplayName("an overloaded producer method's id carries its parameter types, another producer's does not")
    void overloadsHaveDifferentIds() {
        try (var container = VaubanContainer.builder().addBeanClass(Kitchen.class).build()) {
            var beanManager = container.getBeanManager();
            var spoon = (PassivationCapable) beanManager.resolve(beanManager.getBeans(Spoon.class));
            var fork = (PassivationCapable) beanManager.resolve(beanManager.getBeans(Fork.class));
            var knife = (PassivationCapable) beanManager.resolve(beanManager.getBeans(Knife.class));

            assertEquals(Kitchen.class.getName() + "#make()", spoon.getId());
            assertEquals(Kitchen.class.getName() + "#make(" + Spoon.class.getName() + ")", fork.getId());
            assertEquals(Kitchen.class.getName() + "#knife", knife.getId());
            assertNotEquals(spoon.getId(), fork.getId());
            assertEquals(spoon, beanManager.getPassivationCapableBean(spoon.getId()));
            assertEquals(fork, beanManager.getPassivationCapableBean(fork.getId()));
        }
    }

    // --- fixtures ---

    public record Spoon(String from) {
    }

    public record Fork(String from) {
    }

    public record Knife() {
    }

    /** Two producer methods named {@code make}, and one of a name of its own. */
    @Dependent
    public static class Kitchen {

        @Produces
        Spoon make() {
            return new Spoon("make()");
        }

        @Produces
        Fork make(Spoon spoon) {
            return new Fork("make(" + spoon.getClass().getSimpleName() + ")");
        }

        @Produces
        Knife knife() {
            return new Knife();
        }
    }
}
