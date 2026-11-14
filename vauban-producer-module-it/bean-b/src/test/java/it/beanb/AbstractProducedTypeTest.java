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

import io.vidocq.vauban.core.container.VaubanContainer;
import it.liba.Meter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261114-02, on the MODULE PATH: a normal-scoped producer of an abstract class of another module resolves
 * through the client proxy the processor generated in the producer's package, with zero {@code opens}. Before the
 * fix, {@code java.time.Clock} failed with a {@code NullPointerException} (no class loader to define its proxy in),
 * and {@code it.liba.Meter} needed {@code opens it.liba}.
 */
@DisplayName("BUG-20261114-02 — abstract produced types of other modules resolve with zero opens")
class AbstractProducedTypeTest {

    @Test
    @DisplayName("@Produces @ApplicationScoped java.time.Clock and it.liba.Meter resolve through build-time proxies")
    void abstractProducedTypesResolveThroughBuildTimeProxies() {
        try (VaubanContainer container =
                     VaubanContainer.builder().addBeanClass(InstrumentProducer.class).build()) {
            Clock clock = container.select(Clock.class);
            Meter meter = container.select(Meter.class);

            assertEquals(Instant.EPOCH, clock.instant());
            assertEquals(ZoneOffset.UTC, clock.getZone());
            assertEquals(42, meter.reading());
            assertEquals("meter", meter.get());
            assertEquals("MWh", meter.unit());
            for (Object proxy : new Object[] {clock, meter}) {
                assertEquals("it.beanb", proxy.getClass().getPackageName(),
                        "the build-time proxy lives in the producer's package: " + proxy.getClass().getName());
            }
        }
    }
}
