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

/**
 * Runs the CDI SE application on the module path — no {@code --add-opens}, no agent, no reflection.
 *
 * <pre>{@code
 *   java -p <module-path> -m io.vidocq.vauban.example.cdi1015.app/io.vidocq.vauban.example.cdi1015.app.Main
 * }</pre>
 *
 * <p>Prints the generated proxy class names so you can see they are build-time
 * {@code _ClientProxy} classes in this module's package — not {@code java.lang.reflect.Proxy}
 * instances, and not classes reflected into the CDI-agnostic library's package.
 */
@SuppressWarnings("java:S106")
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .addBeanClasses(Integrations.class, CheckoutService.class)
                .initialize()) {

            PaymentGateway gateway = container.select(PaymentGateway.class).get();
            AuditLog audit = container.select(AuditLog.class).get();
            CheckoutService checkout = container.select(CheckoutService.class).get();

            System.out.println("PaymentGateway proxy : " + gateway.getClass().getName()
                    + "  (reflect.Proxy? " + java.lang.reflect.Proxy.isProxyClass(gateway.getClass()) + ")");
            System.out.println("AuditLog proxy       : " + audit.getClass().getName()
                    + "  (reflect.Proxy? " + java.lang.reflect.Proxy.isProxyClass(audit.getClass()) + ")");
            System.out.println();
            System.out.println("Checkout: " + checkout.checkout("acct-42", "order-7", 1999));
        }
    }
}
