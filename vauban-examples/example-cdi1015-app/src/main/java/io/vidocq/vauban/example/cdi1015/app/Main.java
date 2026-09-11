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

import io.vidocq.vauban.classloader.Launch;
import io.vidocq.vauban.example.cdi1015.lib.AuditLog;
import io.vidocq.vauban.example.cdi1015.lib.FraudPolicy;
import io.vidocq.vauban.example.cdi1015.lib.FraudScreen;
import io.vidocq.vauban.example.cdi1015.lib.PaymentGateway;
import io.vidocq.vauban.example.cdi1015.lib.ReceiptPrinter;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

/**
 * Runs the CDI SE application on the module path — no {@code --add-opens}, no agent, no reflection.
 *
 * <pre>{@code
 *   java -p <module-path> -m io.vidocq.vauban.example.cdi1015.app/io.vidocq.vauban.example.cdi1015.app.Main
 * }</pre>
 *
 * <p>The first statement re-launches {@code main} inside a Vauban layer, once: the launcher returns
 * {@code true} for the call made from the boot layer, and {@code false} for the call made again from
 * inside the layer, where the application then runs. That is what lets the in-package proxies be
 * placed inside the library's package. The plain command above is enough: the application module
 * is the root, so everything it needs is already resolved in the boot layer.
 *
 * <p>Prints the generated proxy class names: {@code PaymentGateway} and {@code AuditLog} get
 * build-time proxies in this module's package; {@code FraudScreen} and {@code ReceiptPrinter} get
 * co-located proxies, defined in the library's package by the Vauban loader. None is a {@code java.lang.reflect.Proxy}.
 */
@SuppressWarnings("java:S106")
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Throwable {
        if (Launch.run("io.vidocq.vauban.example.cdi1015.app/io.vidocq.vauban.example.cdi1015.app.Main", args)) {
            return; // the application ran inside the Vauban layer
        }
        try (SeContainer container = SeContainerInitializer.newInstance()
                .addBeanClasses(Integrations.class, CheckoutService.class)
                .initialize()) {

            PaymentGateway gateway = container.select(PaymentGateway.class).get();
            AuditLog audit = container.select(AuditLog.class).get();
            FraudScreen screen = container.select(FraudScreen.class).get();
            ReceiptPrinter printer = container.select(ReceiptPrinter.class).get();
            CheckoutService checkout = container.select(CheckoutService.class).get();

            System.out.println("PaymentGateway proxy : " + gateway.getClass().getName()
                    + "  (reflect.Proxy? " + java.lang.reflect.Proxy.isProxyClass(gateway.getClass()) + ")");
            System.out.println("AuditLog proxy       : " + audit.getClass().getName()
                    + "  (reflect.Proxy? " + java.lang.reflect.Proxy.isProxyClass(audit.getClass()) + ")");
            System.out.println("FraudScreen proxy    : " + screen.getClass().getName() + where(screen.getClass()));
            System.out.println("ReceiptPrinter proxy : " + printer.getClass().getName() + where(printer.getClass()));
            System.out.println();
            System.out.println("Checkout: " + checkout.checkout("acct-42", "order-7", 1999));
            // FraudPolicy, a class of the library's own package, calls FraudScreen's package-private
            // score on the proxy it is handed: the placed proxy forwards it to the real instance.
            System.out.println("Fraud review: " + new FraudPolicy().review(screen, 1999));
            System.out.println("Receipt: " + printer.print("order-7", 1999));
        }
    }

    /** Where a class lives: its module and the simple name of the loader that defined it. */
    private static String where(Class<?> type) {
        return "  (module " + type.getModule().getName() + ", "
                + type.getClassLoader().getClass().getSimpleName() + ")";
    }
}
