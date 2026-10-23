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

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.Bean;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * VAU-LIFE-003: {@code Bean#destroy(instance, null)} of an intercepted bean runs its {@code @PreDestroy} callbacks
 * through its lifecycle interceptors (CDI 4.1 §7.3.1, §5.5.3, Jakarta Interceptors 2.2). An exception from a
 * {@code @PreDestroy} callback is caught by {@code destroy()} (CDI 4.1 §6.1; Jakarta Annotations 3.0,
 * {@code @PreDestroy}). Each bean class serves one test only: an intercepted subclass, once defined, stays defined.
 */
@DisplayName("ManagedBean: Bean#destroy(instance, null) of a bean bound to a @PreDestroy interceptor")
class InterceptedBeanDestroyWithoutCreationalContextTest {

    private static final String PRE_DESTROY = "pre-destroy";
    private static final String TARGET_PRE_DESTROY = "target-pre-destroy";

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Disposed {
    }

    @Disposed
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class DisposingInterceptor {
        @PreDestroy
        public void preDestroy(InvocationContext context) throws Exception {
            CALLS.add(PRE_DESTROY);
            context.proceed();
        }
    }

    @Disposed
    @Dependent
    public static class DisposedBean {
        @PreDestroy
        void cleanUp() {
            CALLS.add(TARGET_PRE_DESTROY);
        }
    }

    @Disposed
    @Dependent
    public static class FailingDisposedBean {
        @PreDestroy
        void cleanUp() {
            CALLS.add(TARGET_PRE_DESTROY);
            throw new IllegalStateException("clean-up failed");
        }
    }

    /** The bean of {@code beanClass}, with an instance created with a creational context of the container. */
    private record Created<T>(Bean<T> bean, T instance) {
    }

    @SuppressWarnings("unchecked")
    private static <T> Created<T> create(VaubanContainer container, Class<T> beanClass) {
        var beanManager = container.getBeanManager();
        var bean = (Bean<T>) beanManager.resolve(beanManager.getBeans(beanClass));
        var instance = assertInstanceOf(beanClass, bean.create(beanManager.createCreationalContext(bean)));
        return new Created<>(bean, instance);
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    @Test
    @DisplayName("the @PreDestroy callback runs through its interceptor")
    void preDestroyRunsThroughItsInterceptor() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(DisposingInterceptor.class)
                .addBeanClass(DisposedBean.class)
                .build()) {
            var created = create(container, DisposedBean.class);
            CALLS.clear();

            created.bean().destroy(created.instance(), null);

            assertEquals(List.of(PRE_DESTROY, TARGET_PRE_DESTROY), List.copyOf(CALLS));
        }
    }

    @Test
    @DisplayName("an exception from the @PreDestroy callback is ignored, after the callback ran")
    void failingPreDestroyIsIgnored() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(DisposingInterceptor.class)
                .addBeanClass(FailingDisposedBean.class)
                .build()) {
            var created = create(container, FailingDisposedBean.class);
            CALLS.clear();

            assertDoesNotThrow(() -> created.bean().destroy(created.instance(), null));

            assertEquals(List.of(PRE_DESTROY, TARGET_PRE_DESTROY), List.copyOf(CALLS));
        }
    }
}
