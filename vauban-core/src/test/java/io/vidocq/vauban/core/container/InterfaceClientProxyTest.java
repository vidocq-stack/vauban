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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression for VAU-PROXY-INTERFACE: a normal-scoped bean whose bean type is an <em>interface</em>
 * must receive a client proxy. The subclass-based {@code RuntimeClientProxyGenerator} cannot subclass
 * an interface, so the container falls back to {@link java.lang.reflect.Proxy}, which delegates each
 * call to the contextual instance resolved in the active scope.
 *
 * <p>Concrete case: a {@code @RequestScoped @Produces} method returning an interface — exactly
 * MicroProfile JWT's {@code @Produces @RequestScoped JsonWebToken}. Before the fix, injecting the
 * interface-typed bean into a wider-scoped consumer failed (unproxyable / null field); after it, the
 * proxy resolves the per-request instance lazily.</p>
 */
@DisplayName("VAU-PROXY-INTERFACE - client proxy for an interface-typed normal-scoped bean")
class InterfaceClientProxyTest {

    /** An interface bean type (mirrors org.eclipse.microprofile.jwt.JsonWebToken). */
    public interface Identity {
        String name();
    }

    @ApplicationScoped
    public static class IdentityProducer {
        @Produces
        @RequestScoped
        public Identity currentIdentity() {
            return () -> "alice";
        }
    }

    /** @ApplicationScoped consumer holding an interface-typed @RequestScoped dependency. */
    @ApplicationScoped
    public static class IdentityConsumer {
        @Inject
        Identity identity;

        public Identity identity() {
            return identity;
        }

        public String who() {
            return identity.name();
        }
    }

    @Test
    @DisplayName("interface-typed @RequestScoped bean injects as a proxy and resolves inside an active scope")
    void interfaceTypedRequestScopedBeanInjectsAndResolves() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(IdentityProducer.class)
                .addBeanClass(IdentityConsumer.class)
                .build()) {

            IdentityConsumer consumer = container.select(IdentityConsumer.class);

            // The field must be a non-null client proxy (interface -> java.lang.reflect.Proxy).
            assertNotNull(consumer.identity(),
                    "interface-typed @RequestScoped field must be injected as a non-null proxy");

            // Out of scope, invoking the proxy throws (no active request context).
            assertThrows(ContextNotActiveException.class, consumer::who,
                    "invoking the interface proxy out of scope must throw ContextNotActiveException");

            // In scope, the proxy delegates to the per-request produced instance.
            container.requestContext().runInScope(() ->
                    assertEquals("alice", consumer.who(),
                            "interface proxy must delegate to the contextual instance in the active request scope"));
        }
    }
}
