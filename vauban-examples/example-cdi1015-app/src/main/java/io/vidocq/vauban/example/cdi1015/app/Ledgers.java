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

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/** Holder for the nested-bean case. */
public final class Ledgers {

    private Ledgers() {}

    /**
     * The nested-bean case. A static member class is a perfectly valid managed bean; what it costs
     * is that it has <em>two</em> names. {@code Ledgers$Ledger} is the binary one, which the
     * container looks components up by, and {@code Ledgers.Ledger} is the canonical one, the only
     * form that can appear in a {@code new} expression. The generated provider carries both.
     *
     * <p>Its proxy is ordinary source, named {@code Ledgers$Ledger_ClientProxy} — a top-level class
     * whose simple name contains a {@code $}, never a member of {@code Ledgers}: nothing can add a
     * member to a class that already exists.
     */
    @ApplicationScoped
    public static class Ledger {

        private final List<String> entries = new ArrayList<>();

        public String record(String entry) {
            entries.add(entry);
            return entry + "#" + entries.size();
        }

        public int size() {
            return entries.size();
        }
    }
}
