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
package io.vidocq.vauban.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The built-in {@code @ActivateRequestContext} interceptor (CDI 4.1 §6.5.9), after the CDI TCK's
 * ActivateRequestContextByInterceptorTest (group "se", which the TCK runner does not run).
 * Vidocq/vauban#147.
 */
@DisplayName("@ActivateRequestContext")
class ActivateRequestContextTest {

    @RequestScoped
    public static class RequestScopeCounter {
        private int count = 10;

        public int increment() {
            return ++count;
        }
    }

    @BeforeActivation
    @ActivateRequestContext
    @AfterActivation
    @Dependent
    public static class ClassInterceptorContextActivator {
        @Inject
        RequestScopeCounter counter;

        public int callRequestScopeBean() {
            return counter.increment();
        }
    }

    @Dependent
    public static class MethodInterceptorContextActivator {
        @Inject
        RequestScopeCounter counter;

        @ActivateRequestContext
        public int callRequestScopeBean() {
            return counter.increment();
        }
    }

    @Interceptor
    @BeforeActivation
    @Priority(Interceptor.Priority.PLATFORM_BEFORE + 99)
    public static class BeforeActivationInterceptor {
        static final AtomicBoolean requestContextActive = new AtomicBoolean(true);

        @Inject
        BeanManager beanManager;

        @AroundInvoke
        public Object intercept(InvocationContext ctx) throws Exception {
            requestContextActive.set(isActive(beanManager));
            return ctx.proceed();
        }
    }

    @Interceptor
    @AfterActivation
    @Priority(Interceptor.Priority.PLATFORM_BEFORE + 101)
    public static class AfterActivationInterceptor {
        static final AtomicBoolean requestContextActive = new AtomicBoolean(false);

        @Inject
        BeanManager beanManager;

        @AroundInvoke
        public Object intercept(InvocationContext ctx) throws Exception {
            requestContextActive.set(isActive(beanManager));
            return ctx.proceed();
        }
    }

    @ApplicationScoped
    public static class RequestContextObserver {
        private final AtomicInteger initialized = new AtomicInteger();
        private final AtomicInteger beforeDestroyed = new AtomicInteger();
        private final AtomicInteger destroyed = new AtomicInteger();

        public void onInitialized(@Observes @Initialized(RequestScoped.class) Object event) {
            initialized.incrementAndGet();
        }

        public void onBeforeDestroyed(@Observes @BeforeDestroyed(RequestScoped.class) Object event) {
            beforeDestroyed.incrementAndGet();
        }

        public void onDestroyed(@Observes @Destroyed(RequestScoped.class) Object event) {
            destroyed.incrementAndGet();
        }

        public String counts() {
            return initialized.get() + "/" + beforeDestroyed.get() + "/" + destroyed.get();
        }
    }

    private VaubanContainer container;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder()
                .addBeanClass(RequestScopeCounter.class)
                .addBeanClass(ClassInterceptorContextActivator.class)
                .addBeanClass(MethodInterceptorContextActivator.class)
                .addBeanClass(BeforeActivationInterceptor.class)
                .addBeanClass(AfterActivationInterceptor.class)
                .addBeanClass(RequestContextObserver.class)
                .build();
    }

    @AfterEach
    void stop() {
        container.close();
    }

    @Test
    @DisplayName("a class binding activates the request context around the call, with its events")
    void classInterceptorRequestContextActivation() {
        assertEquals(11, container.select(ClassInterceptorContextActivator.class).callRequestScopeBean());
        assertEquals("1/1/1", container.select(RequestContextObserver.class).counts());
        assertFalse(isActive(container.getBeanManager()), "deactivated after the call");
    }

    @Test
    @DisplayName("a method binding activates the request context around the call, with its events")
    void methodInterceptorRequestContextActivation() {
        assertEquals(11, container.select(MethodInterceptorContextActivator.class).callRequestScopeBean());
        assertEquals("1/1/1", container.select(RequestContextObserver.class).counts());
    }

    @Test
    @DisplayName("an active request context is left as it is")
    void anActiveContextIsLeftAsItIs() {
        var requestContext = container.requestContext();
        requestContext.activate();
        try {
            var activator = container.select(MethodInterceptorContextActivator.class);
            assertEquals(11, activator.callRequestScopeBean());
            assertEquals(12, activator.callRequestScopeBean(), "the same request instance");
            assertTrue(isActive(container.getBeanManager()), "still active after the call");
        } finally {
            requestContext.deactivate();
        }
    }

    @Test
    @DisplayName("the built-in interceptor has priority PLATFORM_BEFORE + 100")
    void builtInInterceptorHasGivenPriority() {
        container.select(ClassInterceptorContextActivator.class).callRequestScopeBean();
        assertFalse(BeforeActivationInterceptor.requestContextActive.get(), "PLATFORM_BEFORE + 99 runs before");
        assertTrue(AfterActivationInterceptor.requestContextActive.get(), "PLATFORM_BEFORE + 101 runs after");
    }

    static boolean isActive(BeanManager beanManager) {
        try {
            return beanManager.getContext(RequestScoped.class).isActive();
        } catch (ContextNotActiveException _) {
            return false;
        }
    }
}
