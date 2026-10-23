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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The built-in RequestContextController (CDI 4.1 §6.5.8), after the CDI TCK's
 * ActivateRequestContextProgrammaticallyTest (group "se", which the TCK runner does not run), plus
 * what a servlet container needs: one request context per thread, and the context events.
 * Vidocq/vauban#147.
 */
@DisplayName("RequestContextController")
class RequestContextControllerTest {

    @RequestScoped
    public static class RequestScopeCounter {
        private final String id = UUID.randomUUID().toString();
        private int count;

        public int increment() {
            return ++count;
        }

        public String id() {
            return id;
        }
    }

    @ApplicationScoped
    public static class TestContextActivator {
        @Inject
        RequestContextController requestContextController;

        @Inject
        RequestScopeCounter requestScopeCounter;

        public boolean activate() {
            return requestContextController.activate();
        }

        public int callRequestScopeBean() {
            return requestScopeCounter.increment();
        }

        public void deactivate() {
            requestContextController.deactivate();
        }
    }

    @ApplicationScoped
    public static class RequestContextObserver {
        final AtomicInteger initialized = new AtomicInteger();
        final AtomicInteger beforeDestroyed = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();

        public void onInitialized(@Observes @Initialized(RequestScoped.class) Object event) {
            initialized.incrementAndGet();
        }

        public void onBeforeDestroyed(@Observes @BeforeDestroyed(RequestScoped.class) Object event) {
            beforeDestroyed.incrementAndGet();
        }

        public void onDestroyed(@Observes @Destroyed(RequestScoped.class) Object event) {
            destroyed.incrementAndGet();
        }

        // Read through methods: the test holds a client proxy, whose own fields are not the bean's.
        public int initialized() {
            return initialized.get();
        }

        public int beforeDestroyed() {
            return beforeDestroyed.get();
        }

        public int destroyed() {
            return destroyed.get();
        }
    }

    private VaubanContainer container;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder()
                .addBeanClass(RequestScopeCounter.class)
                .addBeanClass(TestContextActivator.class)
                .addBeanClass(RequestContextObserver.class)
                .build();
    }

    @AfterEach
    void stop() {
        container.close();
    }

    @Test
    @DisplayName("is a @Dependent built-in bean")
    void isADependentBuiltInBean() {
        var beanManager = container.getBeanManager();
        var bean = beanManager.resolve(beanManager.getBeans(RequestContextController.class));
        assertEquals(Dependent.class, bean.getScope());
    }

    @Test
    @DisplayName("is found by a programmatic lookup, as a servlet container makes it")
    void isFoundByAProgrammaticLookup() {
        var controller = container.getBeanManager().createInstance().select(RequestContextController.class).get();
        assertTrue(controller.activate());
        try {
            assertEquals(1, container.select(RequestScopeCounter.class).increment());
        } finally {
            controller.deactivate();
        }
    }

    @Test
    @DisplayName("activates, keeps one instance per activation, deactivates (TCK programmatic scenario)")
    void programmaticRequestContextActivation() {
        var activator = container.select(TestContextActivator.class);
        assertTrue(activator.activate());
        assertFalse(activator.activate(), "already active");
        activator.callRequestScopeBean();
        assertEquals(2, activator.callRequestScopeBean());
        activator.deactivate();

        activator.activate();
        var beanManager = container.getBeanManager();
        assertTrue(beanManager.getContext(RequestScoped.class).isActive());
        assertEquals(1, activator.callRequestScopeBean(), "a new instance in a new activation");
        activator.deactivate();
        assertThrows(ContextNotActiveException.class, () -> beanManager.getContext(RequestScoped.class));
    }

    @Test
    @DisplayName("deactivating an inactive context throws ContextNotActiveException")
    void deactivatingAnInactiveContextThrows() {
        var activator = container.select(TestContextActivator.class);
        assertThrows(ContextNotActiveException.class, activator::deactivate);
    }

    @Test
    @DisplayName("a controller that did not activate the context leaves it active")
    void anotherControllerLeavesTheContextActive() {
        var first = container.getBeanManager().createInstance().select(RequestContextController.class).get();
        var second = container.getBeanManager().createInstance().select(RequestContextController.class).get();
        assertTrue(first.activate());
        assertFalse(second.activate());
        second.deactivate();
        assertTrue(container.getBeanManager().getContext(RequestScoped.class).isActive(),
                "only the controller that activated the context deactivates it");
        first.deactivate();
    }

    @Test
    @DisplayName("fires @Initialized, @BeforeDestroyed and @Destroyed(RequestScoped.class)")
    void firesTheContextEvents() {
        var observer = container.select(RequestContextObserver.class);
        var activator = container.select(TestContextActivator.class);
        activator.activate();
        activator.callRequestScopeBean();
        activator.deactivate();

        assertEquals(1, observer.initialized());
        assertEquals(1, observer.beforeDestroyed());
        assertEquals(1, observer.destroyed());
    }

    @Test
    @DisplayName("each thread has its own request context, as each servlet request has")
    void eachThreadHasItsOwnRequestContext() throws Exception {
        var beanManager = container.getBeanManager();
        var here = beanManager.createInstance().select(RequestContextController.class).get();
        here.activate();
        try {
            String mine = container.select(RequestScopeCounter.class).id();

            var otherId = new AtomicReference<String>();
            var activeBefore = new AtomicReference<Boolean>();
            var otherActivated = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var other = Thread.ofVirtual().start(() -> {
                activeBefore.set(isRequestContextActive());
                var controller = beanManager.createInstance().select(RequestContextController.class).get();
                controller.activate();
                try {
                    otherId.set(container.select(RequestScopeCounter.class).id());
                    otherActivated.countDown();
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    controller.deactivate();
                }
            });
            otherActivated.await();

            assertFalse(activeBefore.get(), "another thread's context is not active here");
            assertNotEquals(mine, otherId.get(), "one instance per thread");
            assertEquals(mine, container.select(RequestScopeCounter.class).id(),
                    "the other thread's activation did not replace this thread's context");
            release.countDown();
            other.join();
            assertEquals(mine, container.select(RequestScopeCounter.class).id(),
                    "the other thread's deactivation did not end this thread's context");
        } finally {
            here.deactivate();
        }
    }

    private boolean isRequestContextActive() {
        try {
            return container.getBeanManager().getContext(RequestScoped.class).isActive();
        } catch (ContextNotActiveException _) {
            return false;
        }
    }
}
