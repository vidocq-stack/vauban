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
package io.vidocq.vauban.example.cdi1015.app;

import io.vidocq.vauban.example.cdi1015.lib.AuditLog;
import io.vidocq.vauban.example.cdi1015.lib.PaymentGateway;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the jakartaee/cdi#1015 example on the module path (a main {@code module-info.java} exists, so
 * surefire uses it) and proves both produced third-party types resolve with zero {@code opens} via
 * build-time proxies — no {@code java.lang.reflect.Proxy}.
 */
@DisplayName("cdi#1015 CDI SE example — third-party producers resolve with zero opens")
class Cdi1015ExampleTest {

    @Test
    void resolvesThirdPartyProducersWithZeroOpens() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .addBeanClasses(Integrations.class, CheckoutService.class)
                .initialize()) {

            PaymentGateway gateway = container.select(PaymentGateway.class).get();
            AuditLog audit = container.select(AuditLog.class).get();
            CheckoutService checkout = container.select(CheckoutService.class).get();

            // Both proxies are build-time generated classes, not runtime reflect.Proxy instances.
            assertFalse(java.lang.reflect.Proxy.isProxyClass(gateway.getClass()),
                    "the class producer must resolve to a build-time proxy, not a reflect.Proxy");
            assertFalse(java.lang.reflect.Proxy.isProxyClass(audit.getClass()),
                    "the interface producer must resolve to a build-time static proxy, not a reflect.Proxy");
            assertTrue(gateway.getClass().getName().endsWith("_ClientProxy"));
            assertTrue(audit.getClass().getName().endsWith("_ClientProxy"));

            // And they work — the class method, and the interface default method.
            assertEquals("charged 1999 cents to acct-42", checkout.checkout("acct-42", "order-7", 1999));
        }
    }

    @Test
    @DisplayName("an intercepted bean runs its chain through a build-time $$Intercepted subclass")
    void interceptedBeanIsProxiedAndIntercepted() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .addBeanClasses(Integrations.class, CheckoutService.class, Vault.class)
                .addBeanClasses(AuditTrail.class)
                .initialize()) {

            Vault vault = container.select(Vault.class).get();

            assertEquals("[audited] sealed:secret", vault.seal("secret"),
                    "the interceptor chain must run around the bean's own method");
            assertTrue(vault.getClass().getName().endsWith("_ClientProxy"),
                    "the container still hands out a client proxy: " + vault.getClass().getName());
            assertFalse(java.lang.reflect.Proxy.isProxyClass(vault.getClass()),
                    "neither of the two generated classes is a runtime reflect.Proxy");
        }
    }

    /**
     * The one bean shape this example cannot serve with zero {@code opens}, kept here so the limit
     * is visible rather than folklore.
     *
     * <p>A nested bean's client proxy exists — the APT emits {@code Ledgers$Ledger_ClientProxy} as
     * bytecode, since that name cannot be written as Java source. What is missing is the other half:
     * the in-module {@code _VaubanComponents} provider only carries top-level beans, so nobody can
     * instantiate this one from inside the module and the container falls back to reflection, which
     * a module that opens nothing refuses. The diagnostic below is the whole point: it names the
     * type and both ways out.
     */
    @Test
    @DisplayName("a nested bean on the module path fails, and the message says exactly what to do")
    void nestedBeanNeedsOpensOrATopLevelBean() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .addBeanClasses(Ledgers.Ledger.class)
                .initialize()) {

            var failure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> container.select(Ledgers.Ledger.class).get().record("first"));

            // The actionable half is in the cause: the outer frame only says which bean failed.
            var text = new StringBuilder();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                text.append(t.getMessage()).append('\n');
            }
            String message = text.toString();
            assertTrue(message.contains("Ledgers$Ledger"),
                    "the diagnostic must name the bean by its binary name: " + message);
            assertTrue(message.contains("VaubanComponentProvider"),
                    "and offer the in-module route first: " + message);
            assertTrue(message.contains("opens io.vidocq.vauban.example.cdi1015.app"),
                    "and spell out the directive that unblocks it: " + message);
        }
    }
}
