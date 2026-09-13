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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CDI 4.1 {@code Invoker} path, which calls a bean's method on the container's behalf.
 *
 * <p>It resolves a {@link java.lang.invoke.MethodHandle} once, when the invoker is built, and falls
 * back to {@link java.lang.reflect.Method#invoke} only when this module may not have one — a handle
 * needs the same consent {@code setAccessible} would, so it is not a way around the module system.
 * Both paths must behave identically, which is what these tests hold: the arguments arrive, the
 * return value comes back, and an exception thrown by the target reaches the caller unwrapped
 * rather than as an {@code InvocationTargetException}.
 */
@DisplayName("VaubanInvoker — method handle first, reflection as the fallback")
class VaubanInvokerTest {

    @ApplicationScoped
    public static class Calculator {

        public int add(int a, int b) {
            return a + b;
        }

        public static String greet(String who) {
            return "hello " + who;
        }

        public void boom() {
            throw new IllegalStateException("boom");
        }
    }

    private static VaubanInvoker invokerFor(String name, Class<?>... paramTypes) throws Exception {
        var method = Calculator.class.getMethod(name, paramTypes);
        return new VaubanInvoker(method, Calculator.class, false, Set.of());
    }

    @Test
    @DisplayName("an instance method runs with its arguments and returns its value")
    void instanceMethod() throws Exception {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Calculator.class)
                .build()) {
            assertEquals(7, invokerFor("add", int.class, int.class)
                    .invoke(new Calculator(), new Object[] {3, 4}));
        }
    }

    @Test
    @DisplayName("a static method runs without an instance")
    void staticMethod() throws Exception {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Calculator.class)
                .build()) {
            assertEquals("hello world", invokerFor("greet", String.class)
                    .invoke(null, new Object[] {"world"}));
        }
    }

    @Test
    @DisplayName("an exception from the target reaches the caller as itself")
    void targetExceptionIsNotWrapped() throws Exception {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Calculator.class)
                .build()) {
            var invoker = invokerFor("boom");
            var thrown = assertThrows(IllegalStateException.class,
                    () -> invoker.invoke(new Calculator(), new Object[0]));
            assertEquals("boom", thrown.getMessage(),
                    "the target's own exception, not an InvocationTargetException wrapper");
        }
    }

    @Test
    @DisplayName("calling an instance method with no instance is refused, not NPE'd")
    void instanceMethodWithoutInstance() throws Exception {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Calculator.class)
                .build()) {
            var invoker = invokerFor("add", int.class, int.class);
            var thrown = assertThrows(RuntimeException.class,
                    () -> invoker.invoke(null, new Object[] {1, 2}));
            assertTrue(thrown.getMessage().contains("null instance"),
                    "the diagnostic must say what is missing: " + thrown.getMessage());
        }
    }
}
