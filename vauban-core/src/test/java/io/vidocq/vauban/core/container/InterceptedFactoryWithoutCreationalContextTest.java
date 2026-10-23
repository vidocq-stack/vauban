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
import jakarta.interceptor.AroundInvoke;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-INT-008: {@code factory().create()} of an intercepted bean, called with no creational context, creates the bean
 * through its {@code @AroundConstruct} interceptor and intercepts its business methods, on the regular intercepted
 * factory as on the fallback one. Each bean class serves one test only: an intercepted subclass, once defined, stays
 * defined.
 */
@DisplayName("InterceptorBeanWrapper: an intercepted bean's factory called without a creational context")
class InterceptedFactoryWithoutCreationalContextTest {

    private static final String AROUND_CONSTRUCT = "around-construct";
    private static final String AROUND_INVOKE = "around-invoke";

    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    /** The interceptor instance behind each call, in order. */
    static final List<Object> INTERCEPTORS = new CopyOnWriteArrayList<>();

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
    public @interface Watched {
    }

    @Watched
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class WatchingInterceptor {
        @AroundConstruct
        public Object construct(InvocationContext context) throws Exception {
            CALLS.add(AROUND_CONSTRUCT);
            INTERCEPTORS.add(this);
            return context.proceed();
        }

        @AroundInvoke
        public Object invoke(InvocationContext context) throws Exception {
            CALLS.add(AROUND_INVOKE);
            INTERCEPTORS.add(this);
            return context.proceed();
        }
    }

    @Watched
    @Dependent
    public static class RegularlyIntercepted {
        public String greet() {
            return "hello";
        }
    }

    @Watched
    @Dependent
    public static class InterceptedThroughFallback {
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

    /** The bean {@code beanClass} created by its factory with no creational context. */
    private static <T> T createWithoutContext(VaubanContainer container, Class<T> beanClass) {
        var beanManager = container.getBeanManager();
        var bean = (ManagedBean<?>) beanManager.resolve(beanManager.getBeans(beanClass));
        return assertInstanceOf(beanClass, bean.factory().create());
    }

    /** Clears what the boot recorded. */
    private static void clearCalls() {
        CALLS.clear();
        INTERCEPTORS.clear();
    }

    /** One interceptor instance, created for the bean instance, served both its construction and its method. */
    private static void assertOneInterceptorInstance() {
        assertEquals(2, INTERCEPTORS.size());
        assertSame(INTERCEPTORS.getFirst(), INTERCEPTORS.getLast());
    }

    @BeforeEach
    void reset() {
        clearCalls();
    }

    @Test
    @DisplayName("the regular intercepted factory creates the bean through @AroundConstruct and intercepts it")
    void regularFactory() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(WatchingInterceptor.class)
                .addBeanClass(RegularlyIntercepted.class)
                .build()) {
            clearCalls();
            var bean = createWithoutContext(container, RegularlyIntercepted.class);

            assertEquals("hello", bean.greet());
            assertTrue(bean.getClass().getName().endsWith("$$Intercepted"), bean.getClass().getName());
            assertEquals(List.of(AROUND_CONSTRUCT, AROUND_INVOKE), List.copyOf(CALLS));
            assertOneInterceptorInstance();
        }
    }

    @Test
    @DisplayName("the fallback intercepted factory creates the bean through @AroundConstruct and intercepts it")
    void fallbackFactory() {
        try (var container = VaubanContainer.builder()
                .classDefiner(refusingFirstDefinition())
                .addBeanClass(WatchingInterceptor.class)
                .addBeanClass(InterceptedThroughFallback.class)
                .build()) {
            clearCalls();
            var bean = createWithoutContext(container, InterceptedThroughFallback.class);

            assertEquals("hello", bean.greet());
            assertTrue(bean.getClass().getName().endsWith("$$Intercepted"), bean.getClass().getName());
            assertEquals(List.of(AROUND_CONSTRUCT, AROUND_INVOKE), List.copyOf(CALLS));
            assertOneInterceptorInstance();
        }
    }
}
