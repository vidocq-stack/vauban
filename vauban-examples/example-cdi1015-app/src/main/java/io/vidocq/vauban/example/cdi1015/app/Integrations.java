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
import io.vidocq.vauban.example.cdi1015.lib.FraudScreen;
import io.vidocq.vauban.example.cdi1015.lib.PaymentGateway;
import io.vidocq.vauban.example.cdi1015.lib.ReceiptPrinter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the CDI-agnostic library types as normal-scoped beans — the jakartaee/cdi#1015 case:
 * the produced types belong to a module that knows nothing about CDI and opens nothing.
 */
@ApplicationScoped
public class Integrations {

    /** A producer of a fully-public class from another module (Stage 1: build-time proxy, zero opens). */
    @Produces
    @ApplicationScoped
    public PaymentGateway paymentGateway() {
        return new PaymentGateway();
    }

    /** A producer of an interface from another module (build-time static proxy, no reflect.Proxy). */
    @Produces
    @ApplicationScoped
    public AuditLog auditLog() {
        List<String> events = new ArrayList<>();
        return event -> {
            events.add(event);
            System.out.println("[audit] " + event);
        };
    }

    /**
     * The hard case: a class with a package-private member, from another module. Its proxy must
     * live in the library's package, so the build ships it as bytes and, in a Vauban layer (see
     * {@link Main}), the loader that owns the library places it there. Still zero {@code opens}.
     */
    @Produces
    @ApplicationScoped
    public FraudScreen fraudScreen() {
        return new FraudScreen(10_000);
    }

    /**
     * The other hard case: no accessible constructor, only a package-private one behind a static
     * factory. A proxy outside the library's package has no {@code super()} to chain to, so this
     * one is placed in the library's package as well.
     */
    @Produces
    @ApplicationScoped
    public ReceiptPrinter receiptPrinter() {
        return ReceiptPrinter.withFooter("thank you");
    }
}
