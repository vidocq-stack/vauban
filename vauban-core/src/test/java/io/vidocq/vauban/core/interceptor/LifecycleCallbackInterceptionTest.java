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
package io.vidocq.vauban.core.interceptor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainerInitializer;
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

/**
 * An {@code @AroundInvoke} interceptor sees business methods only: a bean's {@code @PostConstruct} and
 * {@code @PreDestroy} callbacks are intercepted by the interceptor's own lifecycle methods, never by
 * {@code @AroundInvoke} (Jakarta Interceptors 2.2, VAU-INT-006, vauban#114). This is the run-time path; the
 * processor path is covered by {@code LifecycleCallbackModulePathTest} in vauban-module-it.
 */
@DisplayName("Lifecycle callbacks are not business methods (VAU-INT-006)")
class LifecycleCallbackInterceptionTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Logged {
    }

    /** What each interceptor method saw, in call order. */
    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    @Logged
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class LoggingInterceptor {

        @AroundInvoke
        public Object around(InvocationContext ctx) throws Exception {
            SEEN.add("around:" + ctx.getMethod().getName());
            return ctx.proceed();
        }

        @PostConstruct
        void postConstruct(InvocationContext ctx) throws Exception {
            SEEN.add("postConstruct:method=" + ctx.getMethod());
            ctx.proceed();
        }

        @PreDestroy
        void preDestroy(InvocationContext ctx) throws Exception {
            SEEN.add("preDestroy:method=" + ctx.getMethod());
            ctx.proceed();
        }
    }

    @Logged
    @ApplicationScoped
    public static class ScopedService {

        @PostConstruct
        void init() {
            SEEN.add("init");
        }

        @PreDestroy
        void dispose() {
            SEEN.add("dispose");
        }

        public String work() {
            return "done";
        }
    }

    @Logged
    @Dependent
    public static class DependentService {

        @PostConstruct
        protected void init() {
            SEEN.add("init");
        }

        @PreDestroy
        protected void dispose() {
            SEEN.add("dispose");
        }

        public String work() {
            return "done";
        }
    }

    @BeforeEach
    void reset() {
        SEEN.clear();
    }

    @Test
    @DisplayName("a normal-scoped bean: @AroundInvoke sees the business method, not init() or dispose()")
    void normalScopedBean() {
        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(ScopedService.class, LoggingInterceptor.class)
                .initialize()) {
            assertEquals("done", container.select(ScopedService.class).get().work());
        }
        assertEquals(List.of(
                "postConstruct:method=null", "init",
                "around:work",
                "preDestroy:method=null", "dispose"), SEEN);
    }

    @Test
    @DisplayName("a @Dependent bean with protected callbacks: the same")
    void dependentBean() {
        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(DependentService.class, LoggingInterceptor.class)
                .initialize()) {
            var handle = container.select(DependentService.class);
            var service = handle.get();
            assertEquals("done", service.work());
            handle.destroy(service);
        }
        assertEquals(List.of(
                "postConstruct:method=null", "init",
                "around:work",
                "preDestroy:method=null", "dispose"), SEEN);
    }
}
