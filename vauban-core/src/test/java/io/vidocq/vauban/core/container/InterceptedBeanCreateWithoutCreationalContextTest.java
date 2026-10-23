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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * VAU-LIFE-002: {@code Bean#create(null)} of an intercepted bean runs its {@code @PostConstruct} through its lifecycle
 * interceptors, with one interceptor instance for the bean instance's construction, {@code @PostConstruct} and
 * business methods (Jakarta Interceptors 2.2 §2.2). Each bean class serves one test only: an intercepted subclass, once
 * defined, stays defined.
 */
@DisplayName("ManagedBean: Bean#create(null) of a bean bound to a @PostConstruct interceptor")
class InterceptedBeanCreateWithoutCreationalContextTest {

    private static final String AROUND_CONSTRUCT = "around-construct";
    private static final String POST_CONSTRUCT = "post-construct";
    private static final String TARGET_POST_CONSTRUCT = "target-post-construct";
    private static final String AROUND_INVOKE = "around-invoke";

    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    /** The interceptor instance behind each interceptor call, in order. */
    static final List<Object> INTERCEPTORS = new CopyOnWriteArrayList<>();

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
    public @interface Watched {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Initialized {
    }

    @Watched
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class WatchingInterceptor {
        @AroundConstruct
        public Object construct(InvocationContext context) throws Exception {
            record(AROUND_CONSTRUCT, this);
            return context.proceed();
        }

        @PostConstruct
        public void postConstruct(InvocationContext context) throws Exception {
            record(POST_CONSTRUCT, this);
            context.proceed();
        }

        @AroundInvoke
        public Object invoke(InvocationContext context) throws Exception {
            record(AROUND_INVOKE, this);
            return context.proceed();
        }
    }

    @Initialized
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class InitializingInterceptor {
        @PostConstruct
        public void postConstruct(InvocationContext context) throws Exception {
            record(POST_CONSTRUCT, this);
            context.proceed();
        }
    }

    @Watched
    @Dependent
    public static class WatchedBean {
        @PostConstruct
        void init() {
            CALLS.add(TARGET_POST_CONSTRUCT);
        }

        public String greet() {
            return "hello";
        }
    }

    @Initialized
    @Dependent
    public static class InitializedBean {
        @PostConstruct
        void init() {
            CALLS.add(TARGET_POST_CONSTRUCT);
        }
    }

    private static void record(String call, Object interceptor) {
        CALLS.add(call);
        INTERCEPTORS.add(interceptor);
    }

    /** The bean {@code beanClass} created through {@code Bean#create(null)}. */
    @SuppressWarnings("unchecked")
    private static <T> T createWithoutContext(VaubanContainer container, Class<T> beanClass) {
        var beanManager = container.getBeanManager();
        var bean = (Bean<T>) beanManager.resolve(beanManager.getBeans(beanClass));
        return assertInstanceOf(beanClass, bean.create(null));
    }

    /** Clears what the boot recorded. */
    private static void clearCalls() {
        CALLS.clear();
        INTERCEPTORS.clear();
    }

    @BeforeEach
    void reset() {
        clearCalls();
    }

    @Test
    @DisplayName("one interceptor instance serves the construction, the @PostConstruct and the methods")
    void aroundConstructPostConstructAndAroundInvoke() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(WatchingInterceptor.class)
                .addBeanClass(WatchedBean.class)
                .build()) {
            clearCalls();
            var bean = createWithoutContext(container, WatchedBean.class);

            assertEquals("hello", bean.greet());
            assertEquals(List.of(AROUND_CONSTRUCT, POST_CONSTRUCT, TARGET_POST_CONSTRUCT, AROUND_INVOKE),
                    List.copyOf(CALLS));
            assertEquals(3, INTERCEPTORS.size());
            assertSame(INTERCEPTORS.getFirst(), INTERCEPTORS.get(1));
            assertSame(INTERCEPTORS.getFirst(), INTERCEPTORS.getLast());
        }
    }

    @Test
    @DisplayName("a bean bound to a @PostConstruct interceptor only runs its callback through it")
    void postConstructOnly() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(InitializingInterceptor.class)
                .addBeanClass(InitializedBean.class)
                .build()) {
            clearCalls();
            createWithoutContext(container, InitializedBean.class);

            assertEquals(List.of(POST_CONSTRUCT, TARGET_POST_CONSTRUCT), List.copyOf(CALLS));
            assertEquals(1, INTERCEPTORS.size());
        }
    }
}
