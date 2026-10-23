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
import jakarta.interceptor.AroundConstruct;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-INT-007: when the {@code $$Intercepted} subclass of a bean cannot be defined the regular way, the container
 * falls back on a second definition and its own factory. An {@code @AroundConstruct} interceptor that proceeds must
 * create the bean on that path as it does on the regular one. The bean class serves this test only: a subclass,
 * once defined, stays defined.
 */
@DisplayName("InterceptorBeanWrapper: an @AroundConstruct that proceeds on the fallback intercepted factory")
class FallbackAroundConstructTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    /** What {@code proceed()} returned and what the context's target was after it, per construction. */
    static final List<Object> PROCEEDED = new CopyOnWriteArrayList<>();

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.CONSTRUCTOR})
    public @interface Constructed {
    }

    @Constructed
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class ConstructingInterceptor {
        @AroundConstruct
        public Object construct(InvocationContext context) throws Exception {
            CALLS.add("around-construct");
            var result = context.proceed();
            PROCEEDED.add(context.getTarget());
            return result;
        }
    }

    @Constructed
    @Dependent
    public static class SelectedThroughFallback {
        public String greet() {
            return "hello";
        }
    }

    /** Defines intercepted subclasses in this package, after refusing the first definition of each. */
    private static BiFunction<String, byte[], Class<?>> refusingFirstDefinition() {
        Set<String> refusedOnce = ConcurrentHashMap.newKeySet();
        return (name, bytes) -> {
            if (refusedOnce.add(name)) {
                throw new IllegalStateException("first definition of " + name + " refused");
            }
            try {
                return MethodHandles.lookup().defineClass(bytes);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static VaubanContainer boot(Class<?> beanClass) {
        return VaubanContainer.builder()
                .classDefiner(refusingFirstDefinition())
                .addBeanClass(ConstructingInterceptor.class)
                .addBeanClass(beanClass)
                .build();
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
        PROCEEDED.clear();
    }

    @Test
    @DisplayName("the bean is created, through the @AroundConstruct interceptor, and proceed() gives the instance")
    void selectedBeanIsCreatedThroughAroundConstruct() {
        try (var container = boot(SelectedThroughFallback.class)) {
            var bean = container.select(SelectedThroughFallback.class);

            assertEquals("hello", bean.greet());
            assertTrue(bean.getClass().getName().endsWith("$$Intercepted"), bean.getClass().getName());
            assertEquals(List.of("around-construct"), CALLS);
            assertEquals(1, PROCEEDED.size());
            assertSame(bean, PROCEEDED.getFirst(), "proceed() produced the instance the container returned");
        }
    }
}
