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
package io.vidocq.vauban.bench;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.concurrent.atomic.LongAdder;

/** Counts deliveries, so the benchmark can check that an event reached exactly the observers it should. */
@ApplicationScoped
public class OrderListeners {

    private final LongAdder card = new LongAdder();
    private final LongAdder wire = new LongAdder();
    private final LongAdder every = new LongAdder();

    void onCard(@Observes @Channel("card") OrderPlaced order) {
        card.increment();
    }

    void onWire(@Observes @Channel("wire") OrderPlaced order) {
        wire.increment();
    }

    void onEvery(@Observes OrderPlaced order) {
        every.increment();
    }

    public long card() {
        return card.sum();
    }

    public long wire() {
        return wire.sum();
    }

    public long every() {
        return every.sum();
    }
}
