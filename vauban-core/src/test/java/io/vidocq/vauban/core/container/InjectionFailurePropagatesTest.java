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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection that fails must say so. Deployment validation catches what it can see at boot; what
 * it cannot — a producer that throws — surfaces while a bean is being built, and the container used
 * to log it and hand out the bean with the field left {@code null} (BUG-20260914-17). The failure
 * then reappeared as a {@code NullPointerException} in application code, with a stack trace naming
 * neither the field nor the cause.
 */
@DisplayName("BUG-20260914-17: an injection failure reaches the caller, it is not left in a log")
class InjectionFailurePropagatesTest {

    public interface Ledger {
        String id();
    }

    /** Valid at boot — the producer exists — and fatal when it actually runs. */
    @Dependent
    public static class FailingProducer {

        @Produces
        @Dependent
        public Ledger ledger() {
            throw new IllegalStateException("the ledger is unavailable");
        }
    }

    @Dependent
    public static class Accountant {

        @Inject
        public Ledger ledger;
    }

    @Dependent
    public static class Bookkeeper {

        @Inject
        public Accountant accountant;
    }

    @Test
    @DisplayName("a producer that throws fails the lookup, instead of yielding a bean with a null field")
    void aFailingProducerFailsTheLookup() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(FailingProducer.class)
                .addBeanClass(Accountant.class)
                .build()) {

            var thrown = assertThrows(RuntimeException.class,
                    () -> container.select(Accountant.class),
                    "the field cannot be injected, so no Accountant can be handed out");

            assertTrue(causeChain(thrown).contains("the ledger is unavailable"),
                    "the original cause must still be reachable, not only in a log: " + causeChain(thrown));
        }
    }

    @Test
    @DisplayName("and it reaches the caller through a bean that only depends on the broken one")
    void itPropagatesThroughTheGraph() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(FailingProducer.class)
                .addBeanClass(Accountant.class)
                .addBeanClass(Bookkeeper.class)
                .build()) {

            var thrown = assertThrows(RuntimeException.class,
                    () -> container.select(Bookkeeper.class));

            assertTrue(causeChain(thrown).contains("the ledger is unavailable"), causeChain(thrown));
        }
    }

    @Test
    @DisplayName("a bean whose injections all succeed is unaffected")
    void theHappyPathIsUntouched() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(WorkingProducer.class)
                .addBeanClass(Accountant.class)
                .build()) {

            var accountant = container.select(Accountant.class);

            assertNotNull(accountant.ledger, "the field must be injected");
            assertEquals("ok", accountant.ledger.id());
        }
    }

    @Dependent
    public static class WorkingProducer {

        @Produces
        @Dependent
        public Ledger ledger() {
            return () -> "ok";
        }
    }

    private static String causeChain(Throwable thrown) {
        var text = new StringBuilder();
        for (var t = thrown; t != null && text.length() < 2000; t = t.getCause()) {
            text.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        return text.toString();
    }
}
