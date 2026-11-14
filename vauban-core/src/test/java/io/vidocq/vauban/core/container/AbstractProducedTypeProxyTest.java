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

import io.vidocq.vauban.core.container.abstractproduced.Meter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261114-02: a normal-scoped producer of an abstract class gets a client proxy at run time, when the build left
 * none. CDI 4.1 §3.10 does not count an abstract class as unproxyable; the proxy forwards its abstract methods to
 * the contextual instance. A produced type of the boot class loader ({@code java.time.Clock}) gets its proxy in the
 * producer's package, where the build would have put it, since no class can be defined in {@code java.time}.
 */
@DisplayName("BUG-20261114-02 — a normal-scoped producer of an abstract class gets a client proxy")
class AbstractProducedTypeProxyTest {

    @ApplicationScoped
    public static class ClockProducer {
        @Produces
        @ApplicationScoped
        public Clock clock() {
            return Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        }
    }

    /** Produces an abstract class of another package. */
    @ApplicationScoped
    public static class MeterProducer {
        @Produces
        @ApplicationScoped
        public Meter meter() {
            return new FixedMeter();
        }
    }

    /** The contextual instance behind the meter's proxy. */
    public static class FixedMeter extends Meter {
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

    /**
     * {@code java.util.Random} has a protected method, {@code next(int)}: its proxy must live in {@code java.util},
     * where no class can be defined.
     */
    @ApplicationScoped
    public static class RandomProducer {
        @Produces
        @ApplicationScoped
        public Random random() {
            return new Random(1);
        }
    }

    @Test
    @DisplayName("java.time.Clock: the proxy is defined in the producer's package and forwards to the instance")
    void aJdkAbstractClassIsProxiedFromTheProducersPackage() {
        try (var container = VaubanContainer.builder().addBeanClass(ClockProducer.class).build()) {
            Clock clock = container.select(Clock.class);

            assertEquals(Instant.EPOCH, clock.instant());
            assertEquals(ZoneOffset.UTC, clock.getZone());
            assertNotSame(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC).getClass(), clock.getClass(),
                    "a normal-scoped bean is injected through its client proxy");
            assertEquals(ClockProducer.class.getPackageName(), clock.getClass().getPackageName());
            assertTrue(container.runtimeGeneration().announced().contains(new RuntimeGeneration.Generated(
                    RuntimeGeneration.Kind.CLIENT_PROXY, clock.getClass().getName())),
                    "the boot announces the proxy it generates: " + container.runtimeGeneration().announced());
            assertTrue(container.runtimeGeneration().announced().stream()
                            .noneMatch(generated -> generated.className().startsWith("java.time.")),
                    "no class is announced in java.time: " + container.runtimeGeneration().announced());
        }
    }

    @Test
    @DisplayName("an abstract class of another package: every method reaches the instance, abstract ones included")
    void anAbstractClassForwardsEveryMethod() {
        try (var container = VaubanContainer.builder().addBeanClass(MeterProducer.class).build()) {
            Meter meter = container.select(Meter.class);

            assertEquals(42, meter.reading(), "an abstract method of the class");
            assertEquals("meter", meter.get(), "an abstract method of an interface the class does not implement");
            assertEquals("MWh", meter.unit(), "a concrete method");
        }
    }

    @Test
    @DisplayName("a JDK type no proxy can serve: a DeploymentException that names it and the scopes that need none")
    void aJdkTypeNoProxyCanServeIsADeploymentProblem() {
        try (var container = VaubanContainer.builder().addBeanClass(RandomProducer.class).build()) {
            var problem = assertThrows(DeploymentException.class, () -> container.select(Random.class));

            assertTrue(problem.getMessage().contains("java.util.Random"), problem.getMessage());
            assertTrue(problem.getMessage().contains("@Singleton") && problem.getMessage().contains("@Dependent"),
                    problem.getMessage());
            for (Throwable cause = problem; cause != null; cause = cause.getCause()) {
                assertFalse(cause instanceof NullPointerException, "no NullPointerException in the chain");
            }
        }
    }
}
