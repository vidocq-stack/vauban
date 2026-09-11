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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard case of jakartaee/cdi#1015, end to end: {@code FraudScreen} comes from a CDI-agnostic
 * module that opens nothing, and has a package-private member that {@code FraudPolicy}, a class of
 * the same package, calls on the instance it is handed. Its client proxy must therefore live inside
 * the library's package. This test calls the example's real {@code Main.main} from the boot layer,
 * exactly as {@code java -m} does: its first statement must re-launch it inside a Vauban layer. The
 * test then checks what it prints — exactly the output the README promises.
 *
 * <p>The score is derived from the producer's state (a limit of 10 000 cents): through a forwarding
 * proxy 1 999 cents score 1/9; a call left on the proxy's own empty state would score 9/9.
 *
 * <p>{@code ReceiptPrinter} is the other in-package shape: no accessible constructor, only a
 * package-private one behind a static factory, so a proxy outside the package has no {@code super()}
 * to chain to.
 */
@DisplayName("cdi#1015 example — the in-package case, placed through the launcher")
class LauncherExampleTest {

    @Test
    @DisplayName("Main re-launches itself in a Vauban layer, where FraudScreen's proxy is placed inside the library")
    void mainRunsInsideAVaubanLayer() throws Throwable {
        // Surefire puts JUnit on the module path too: keep it in the boot layer, it is not the app.
        System.setProperty(Launch.KEEP_PROPERTY, "org.junit,org.apiguardian,org.opentest4j");
        var captured = new ByteArrayOutputStream();
        var previous = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Main.main(new String[0]);
        } finally {
            System.setOut(previous);
            System.clearProperty(Launch.KEEP_PROPERTY);
        }
        var out = captured.toString(StandardCharsets.UTF_8);
        previous.println(out);

        assertTrue(out.contains("FraudScreen proxy    : io.vidocq.vauban.example.cdi1015.lib.FraudScreen_ClientProxy"),
                "the proxy must be the co-located one, in the library's package:\n" + out);
        assertTrue(out.contains("(module io.vidocq.vauban.example.cdi1015.lib, VaubanClassLoader)"),
                "defined by the Vauban loader that owns the library module:\n" + out);
        assertTrue(out.contains("Fraud review: score 1/9 for 1999 cents"),
                "FraudPolicy calls the package-private score on the proxy it is handed; forwarded, "
                        + "it reads the real limit — un-forwarded, it would score 9/9:\n" + out);
        assertTrue(out.contains("ReceiptPrinter proxy : io.vidocq.vauban.example.cdi1015.lib.ReceiptPrinter_ClientProxy"
                        + "  (module io.vidocq.vauban.example.cdi1015.lib, VaubanClassLoader)"),
                "no accessible constructor: its proxy is placed in the library's package too:\n" + out);
        assertTrue(out.contains("Receipt: order-7, 1999 cents, thank you"),
                "forwarded to the instance the static factory built — un-forwarded, the footer is null:\n" + out);
        assertTrue(out.contains("Checkout: charged 1999 cents to acct-42"), out);
    }
}
