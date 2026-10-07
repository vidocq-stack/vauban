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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A qualifier whose own name contains {@code $}, with members of such types, resolves through the
 * annotation artefacts the processor renders, with no reflection on annotations; and a producer of a
 * nested class or interface gets the client proxy rendered at build time (BUG-20261007-01).
 */
@DisplayName("Qualifier named with '$' and producers of nested types — module path")
class DollarQualifierAndNestedProducerModulePathTest {

    /** Carries the qualifiers the test selects with, built by the JDK from the same declaration. */
    static final class Written {

        @Dollar$Rank(grade = Dollar$Grade.HIGH)
        void high() {}

        @Dollar$Rank
        void low() {}
    }

    private static Annotation qualifier(String carrier) {
        try {
            return Written.class.getDeclaredMethod(carrier).getDeclaredAnnotations()[0];
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no carrier method " + carrier, e);
        }
    }

    @Test
    @DisplayName("a qualifier whose name contains '$' resolves by its enum member")
    void dollarQualifier() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(HighRanked.class)
                .addBeanClass(LowRanked.class)
                .build()) {
            assertEquals("forbid", System.getProperty("vauban.annotations.reflection"));
            assertEquals("high", CDI.current().select(Ranked.class, qualifier("high")).get().rank());
            assertEquals("low", CDI.current().select(Ranked.class, qualifier("low")).get().rank());
        }
    }

    @Test
    @DisplayName("a producer of a nested class gets its build-time proxy")
    void producerOfNestedClass() {
        try (var container = VaubanContainer.builder().addBeanClass(NestedProducers.class).build()) {
            var proxy = container.select(DollarHolder.Value.class);
            assertTrue(proxy.getClass().getName().startsWith("io.vidocq.vauban.moduleit.DollarHolder_Value$$"),
                    "the producer's build-time proxy: " + proxy.getClass().getName());
            assertEquals("value", proxy.value());
        }
    }

    @Test
    @DisplayName("a producer of a nested interface gets its build-time proxy")
    void producerOfNestedInterface() {
        try (var container = VaubanContainer.builder().addBeanClass(NestedProducers.class).build()) {
            var proxy = container.select(DollarHolder.Port.class);
            assertTrue(proxy.getClass().getName().startsWith("io.vidocq.vauban.moduleit.DollarHolder_Port$$"),
                    "the producer's build-time proxy: " + proxy.getClass().getName());
            assertEquals("port", proxy.port());
        }
    }
}
