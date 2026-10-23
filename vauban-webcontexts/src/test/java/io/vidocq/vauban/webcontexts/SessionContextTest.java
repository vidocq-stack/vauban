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
package io.vidocq.vauban.webcontexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionContextTest {

    private VaubanContainer container;
    private Cart cart;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder()
                .addBeanClass(WebContextsExtension.class)
                .addBeanClass(Cart.class)
                .addBeanClass(SessionEvents.class)
                .build();
        cart = container.select(Cart.class);
    }

    @AfterEach
    void stop() {
        WebContexts.deactivateSession();
        container.close();
    }

    @Test
    void theSessionContextIsRegisteredAndInactiveOutsideASession() {
        // BeanManager.getContext throws when no context of the scope is active (CDI 4.1 §11.3.15).
        var beanManager = container.getBeanManager();
        assertThrows(ContextNotActiveException.class,
                () -> beanManager.getContext(SessionScoped.class), "no session bound to this thread");
        assertThrows(ContextNotActiveException.class, () -> cart.id());
    }

    @Test
    void oneSessionKeepsItsInstanceAcrossActivations() {
        var session = new MapSessionStore();
        WebContexts.activateSession(session);
        String first = cart.id();
        WebContexts.deactivateSession();

        WebContexts.activateSession(session);
        assertTrue(container.getBeanManager().getContext(SessionScoped.class).isActive());
        assertEquals(first, cart.id(), "the same instance for the same session");
    }

    @Test
    void twoSessionsHaveTwoInstances() {
        WebContexts.activateSession(new MapSessionStore());
        String alice = cart.id();
        WebContexts.deactivateSession();

        WebContexts.activateSession(new MapSessionStore());
        assertNotEquals(alice, cart.id());
    }

    @Test
    void theInstanceLivesInTheSessionStorage() {
        var session = new MapSessionStore();
        WebContexts.activateSession(session);
        cart.id();

        assertEquals(1, session.attributes.size(), "one attribute for the cart: " + session.attributes.keySet());
    }

    @Test
    void eachThreadSeesItsOwnSession() throws Exception {
        WebContexts.activateSession(new MapSessionStore());
        String here = cart.id();

        var there = new AtomicReference<String>();
        Thread.ofVirtual().start(() -> {
            WebContexts.activateSession(new MapSessionStore());
            try {
                there.set(cart.id());
            } finally {
                WebContexts.deactivateSession();
            }
        }).join();

        assertNotEquals(here, there.get());
        assertEquals(here, cart.id(), "the other thread did not replace this thread's session");
    }

    @Test
    void destroyingASessionDestroysItsInstancesAndFiresTheEvents() {
        var beanManager = container.getBeanManager();
        var session = new MapSessionStore();
        WebContexts.sessionInitialized(beanManager, "s1");
        WebContexts.activateSession(session);
        String id = cart.id();
        WebContexts.deactivateSession();

        WebContexts.destroySession(beanManager, session, "s1");

        assertEquals(List.of("initialized s1", "before destroyed s1", "destroyed " + id, "destroyed s1"),
                container.select(SessionEvents.class).log());
        assertTrue(session.attributes.isEmpty(), "the instance left the session: " + session.attributes.keySet());
        assertThrows(ContextNotActiveException.class,
                () -> beanManager.getContext(SessionScoped.class),
                "destroying a session leaves no session bound to the thread");
    }

    @Test
    void destroyingASessionInsideAnotherKeepsTheOuterOneBound() {
        var outer = new MapSessionStore();
        WebContexts.activateSession(outer);
        String outerCart = cart.id();

        var ended = new MapSessionStore();
        WebContexts.activateSession(ended);
        cart.id();
        WebContexts.deactivateSession();
        WebContexts.activateSession(outer);
        WebContexts.destroySession(container.getBeanManager(), ended, "ended");

        assertEquals(outerCart, cart.id(), "the outer session is still the active one");
    }
}
