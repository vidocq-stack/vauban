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
}
