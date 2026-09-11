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
package io.vidocq.vauban.example.cdi1015.lib;

/**
 * Prints receipts. It has no accessible constructor — only a package-private one, reached through
 * the static factory {@link #withFooter(String)} — so a proxy outside this package has no
 * {@code super()} to chain to: its client proxy must live in this package too.
 */
public class ReceiptPrinter {

    private final String footer;

    ReceiptPrinter(String footer) {
        this.footer = footer;
    }

    /**
     * The only way in.
     *
     * @param footer the line printed at the end of every receipt
     * @return a printer
     */
    public static ReceiptPrinter withFooter(String footer) {
        return new ReceiptPrinter(footer);
    }

    /**
     * Formats a receipt line.
     *
     * @param orderId     the order
     * @param amountCents the amount charged, in cents
     * @return the receipt line, ending with the footer
     */
    public String print(String orderId, long amountCents) {
        return orderId + ", " + amountCents + " cents, " + footer;
    }
}
