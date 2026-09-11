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
 * The hard case of jakartaee/cdi#1015: a public class of a CDI-agnostic library whose risk score is
 * <em>package-private</em> — a collaboration point for {@link FraudPolicy}, not an API for callers.
 * A client proxy placed anywhere but this package can neither override nor forward it, so a proxy
 * for this type must live here; under Weld that takes {@code --add-opens}.
 */
public class FraudScreen {

    private final long limitCents;

    public FraudScreen(long limitCents) {
        this.limitCents = limitCents;
    }

    /** Whether an amount passes the screen. */
    public boolean accepts(long amountCents) {
        return score(amountCents) < 5;
    }

    /**
     * Risk score from 0 to 9, relative to the configured limit. Package-private on purpose, and
     * derived from state: called on a proxy that does not forward it, it would read the proxy's own
     * zero limit and answer 9.
     */
    int score(long amountCents) {
        return (int) Math.min(9, amountCents * 10 / Math.max(1, limitCents));
    }
}
