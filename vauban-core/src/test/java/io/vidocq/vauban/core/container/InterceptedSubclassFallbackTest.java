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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.CreationException;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.interceptor.AroundConstruct;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the {@code $$Intercepted} subclass of a bean cannot be defined the regular way, the container falls back on a
 * second definition and a factory that creates the subclass through its no-arg constructor, after the
 * {@code @AroundConstruct} chain when there is one (an {@code @AroundConstruct} chain that proceeds is not covered
 * here: on this path it recurses into the factory without a creational context, a defect reported separately). A definition that fails again, or fails with a linkage error, is a
 * deployment or definition problem. Each scenario uses its own bean class: a subclass, once defined, stays defined.
 */
@DisplayName("InterceptorBeanWrapper: the fallback definition of an intercepted subclass")
class InterceptedSubclassFallbackTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
    public @interface Logged {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.CONSTRUCTOR})
    public @interface Blocked {
    }

    @Logged
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class LoggedInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("logged:" + context.getMethod().getName());
            return context.proceed();
        }
    }

    /** An {@code @AroundConstruct} interceptor that never lets the construction happen. */
    @Blocked
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class BlockingInterceptor {
        @AroundConstruct
        public Object construct(InvocationContext context) {
            CALLS.add("blocked");
            return null;
        }
    }

    @Logged
    @Dependent
    public static class FallbackGreeter {
        public String greet() {
            return "hello";
        }
    }

    @Blocked
    @Dependent
    public static class FallbackBlocked {
    }

    @Logged
    @Dependent
    public static class FallbackFailingConstructor {
        public FallbackFailingConstructor() {
            throw new IllegalStateException("constructor refused");
        }
    }

    @Logged
    @Dependent
    public static class FallbackCheckedConstructor {
        public FallbackCheckedConstructor() throws IOException {
            throw new IOException("constructor I/O failure");
        }
    }

    @Logged
    @Dependent
    public static class NeverDefined {
    }

    @Logged
    @Dependent
    public static class LinkageBroken {
    }

    @Logged
    @Dependent
    public static class DeploymentBroken {
    }

    /** How the test definer answers each definition of an intercepted subclass. */
    private enum Refusal { FIRST_ONLY, ALWAYS, LINKAGE, DEPLOYMENT }

    /** Defines intercepted subclasses in this package, after refusing them as {@code refusal} says. */
    private static BiFunction<String, byte[], Class<?>> definer(Refusal refusal) {
        Set<String> refusedOnce = ConcurrentHashMap.newKeySet();
        return (name, bytes) -> {
            switch (refusal) {
                case FIRST_ONLY -> {
                    if (refusedOnce.add(name)) {
                        throw new IllegalStateException("first definition of " + name + " refused");
                    }
                }
                case ALWAYS -> throw new IllegalStateException("definition of " + name + " refused");
                case LINKAGE -> throw new NoClassDefFoundError("definition of " + name + " broken");
                case DEPLOYMENT -> throw new DeploymentException("definition of " + name + " is a deployment problem");
            }
            try {
                return MethodHandles.lookup().defineClass(bytes);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static VaubanContainer boot(Refusal refusal, Class<?>... beanClasses) {
        var builder = VaubanContainer.builder()
                .classDefiner(definer(refusal))
                .addBeanClass(LoggedInterceptor.class)
                .addBeanClass(BlockingInterceptor.class);
        for (var beanClass : beanClasses) {
            builder.addBeanClass(beanClass);
        }
        return builder.build();
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    @Test
    @DisplayName("a subclass refused once is defined again, and its business methods are still intercepted")
    void fallbackKeepsInterception() {
        try (var container = boot(Refusal.FIRST_ONLY, FallbackGreeter.class)) {
            var greeter = container.select(FallbackGreeter.class);

            assertEquals("hello", greeter.greet());
            assertEquals(List.of("logged:greet"), CALLS);
            assertTrue(greeter.getClass().getName().endsWith("$$Intercepted"), greeter.getClass().getName());
        }
    }

    @Test
    @DisplayName("an @AroundConstruct chain that never proceeds leaves no instance: a CreationException")
    void fallbackWithoutInstance() {
        try (var container = boot(Refusal.FIRST_ONLY, FallbackBlocked.class)) {
            var thrown = assertThrows(CreationException.class, () -> container.select(FallbackBlocked.class));

            assertTrue(thrown.getMessage().contains("failed to create an instance"), thrown.getMessage());
            assertEquals(List.of("blocked"), CALLS);
        }
    }

    @Test
    @DisplayName("a bean constructor's runtime exception reaches the caller as it is")
    void fallbackConstructorRuntimeException() {
        try (var container = boot(Refusal.FIRST_ONLY, FallbackFailingConstructor.class)) {
            var thrown = assertThrows(IllegalStateException.class,
                    () -> container.select(FallbackFailingConstructor.class));

            assertEquals("constructor refused", thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a bean constructor's checked exception becomes a CreationException")
    void fallbackConstructorCheckedException() {
        try (var container = boot(Refusal.FIRST_ONLY, FallbackCheckedConstructor.class)) {
            var thrown = assertThrows(CreationException.class,
                    () -> container.select(FallbackCheckedConstructor.class));

            assertInstanceOf(IOException.class, thrown.getCause());
        }
    }

    @Test
    @DisplayName("a subclass whose fallback definition fails too is a deployment problem")
    void fallbackFailsToo() {
        var thrown = assertThrows(DeploymentException.class, () -> boot(Refusal.ALWAYS, NeverDefined.class));

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a linkage error while defining the subclass is a definition error")
    void linkageErrorIsDefinitionError() {
        var thrown = assertThrows(DefinitionException.class, () -> boot(Refusal.LINKAGE, LinkageBroken.class));

        assertTrue(thrown.getMessage().startsWith("Cannot create interceptor subclass"), thrown.getMessage());
        assertInstanceOf(NoClassDefFoundError.class, thrown.getCause());
    }

    @Test
    @DisplayName("a deployment problem while defining the subclass is reported as it is, with no fallback")
    void deploymentProblemIsRethrown() {
        var thrown = assertThrows(DeploymentException.class, () -> boot(Refusal.DEPLOYMENT, DeploymentBroken.class));

        assertTrue(thrown.getMessage().contains("is a deployment problem"), thrown.getMessage());
        assertSame(null, thrown.getCause());
    }
}
