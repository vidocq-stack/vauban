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
package it.beanb;

import it.liba.Meter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Normal-scoped producers of two abstract classes of other modules: {@code java.time.Clock}, of the boot class loader,
 * and {@code it.liba.Meter}, of a module that opens nothing (BUG-20261114-02).
 */
@ApplicationScoped
public class InstrumentProducer {

    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    }

    @Produces
    @ApplicationScoped
    public Meter meter() {
        return new FixedMeter();
    }

    /** The contextual instance behind the meter's proxy. */
    static final class FixedMeter extends Meter {
        @Override
        public int reading() {
            return 42;
        }

        @Override
        public String get() {
            return "meter";
        }

        @Override
        public String unit() {
            return "MWh";
        }
    }
}
