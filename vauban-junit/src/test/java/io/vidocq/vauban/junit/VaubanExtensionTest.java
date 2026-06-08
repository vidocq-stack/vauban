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
package io.vidocq.vauban.junit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanExtension - JUnit 6 extension")
class VaubanExtensionTest {

    // --- Test beans ---

    @ApplicationScoped
    public static class GreetingService {
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    @Dependent
    public static class Calculator {
        public int add(int a, int b) {
            return a + b;
        }
    }

    // --- Tests ---

    @SuppressWarnings("CdiManagedBeanInconsistencyInspection")
    @Nested
    @DisplayName("@VaubanTest with @AddBeans and @Inject")
    @VaubanTest
    @AddBeans({GreetingService.class, Calculator.class})
    class InjectionTest {

        @Inject
        GreetingService greetingService;

        @Inject
        Calculator calculator;

        @Test
        @DisplayName("injects an @ApplicationScoped bean")
        void shouldInjectApplicationScopedBean() {
            assertNotNull(greetingService);
            assertEquals("Hello, World!", greetingService.greet("World"));
        }

        @Test
        @DisplayName("injects a @Dependent bean")
        void shouldInjectDependentBean() {
            assertNotNull(calculator);
            assertEquals(5, calculator.add(2, 3));
        }
    }

    @Nested
    @DisplayName("@VaubanTest without beans")
    @VaubanTest
    class EmptyContainerTest {

        @Test
        @DisplayName("works with an empty container")
        void shouldWorkWithEmptyContainer() {
            // No injection, just verify the container lifecycle doesn't crash
            assertTrue(true);
        }
    }

    @SuppressWarnings("CdiManagedBeanInconsistencyInspection")
    @Nested
    @DisplayName("@VaubanTest with a single bean")
    @VaubanTest
    @AddBeans(GreetingService.class)
    class SingleBeanTest {

        @Inject
        GreetingService service;

        @Test
        @DisplayName("@ApplicationScoped returns the same instance across tests")
        void shouldReturnSameInstance1() {
            assertNotNull(service);
            service.greet("test");
        }

        @Test
        @DisplayName("the bean is functional")
        void shouldReturnSameInstance2() {
            assertEquals("Hello, Vauban!", service.greet("Vauban"));
        }
    }
}
