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
package io.vidocq.vauban.core.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1.6 (issue #42): the bytecode counterpart of the APT producer-proxy path. A producer proxy
 * is emitted at an explicit name in the producer's package while extending a fully-public produced
 * type from another package, and forwards to the contextual delegate. Also pins the reflection
 * {@link ProducerProxyEligibility} predicate.
 */
class ProducerProxyBytecodeTest {

    /** A fully-public produced type (public class, public no-arg ctor, public methods). */
    public static class Greeter {
        public String greet() {
            return "hi";
        }
    }

    public static final class FinalThing {}

    public static class ProtectedMethodThing {
        protected void secret() { /* no behaviour needed */ }
    }

    public interface AnInterface {
        void run();
    }

    /** Defines a class at an arbitrary package (cross-package), delegating to the test loader. */
    static final class ByteLoader extends ClassLoader {
        ByteLoader() {
            super(ProducerProxyBytecodeTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    @Test
    @DisplayName("a producer proxy emitted in another package extends the produced type and forwards")
    void producerProxyForwardsAcrossPackages() throws Exception {
        var proxyName = "gen.producer.Greeter$$deadbeef_ClientProxy";
        var generated = RuntimeClientProxyGenerator.generateProducerProxyAt(Greeter.class, proxyName);
        assertEquals(proxyName, generated.className());

        var proxyClass = new ByteLoader().define(proxyName, generated.bytecode());
        assertTrue(Greeter.class.isAssignableFrom(proxyClass),
                "the producer proxy must be a subtype of the produced type");

        var proxy = proxyClass.getDeclaredConstructor(Supplier.class).newInstance((Supplier<?>) Greeter::new);

        // Forwarding: calling the produced-type method on the proxy runs on the delegate instance.
        assertEquals("hi", ((Greeter) proxy).greet());
    }

    /** An abstract produced type: one abstract method of its own, one an interface leaves to it, one concrete. */
    public abstract static class Meter implements Supplier<String> {
        protected Meter() {
        }

        public abstract int reading();

        public String unit() {
            return "kWh";
        }
    }

    /** What the producer returns: the contextual instance the proxy forwards to. */
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

    @Test
    @DisplayName("an abstract produced type: the proxy forwards every method, abstract ones included (BUG-20261114-02)")
    void anAbstractProducedTypeIsForwardedWhole() throws Exception {
        var proxyName = "gen.producer.Meter$$cafe_ClientProxy";
        var generated = RuntimeClientProxyGenerator.generateProducerProxyAt(Meter.class, proxyName);

        var proxyClass = new ByteLoader().define(proxyName, generated.bytecode());
        var meter = (Meter) proxyClass.getDeclaredConstructor(Supplier.class)
                .newInstance((Supplier<?>) FixedMeter::new);

        assertEquals(42, meter.reading(), "an abstract method of the class");
        assertEquals("meter", meter.get(), "an abstract method of an interface the class does not implement");
        assertEquals("MWh", meter.unit(), "a concrete method");
    }

    @Test
    @DisplayName("java.time.Clock, abstract and of the boot loader: the proxy forwards to the instance")
    void aJdkAbstractClassIsForwardedWhole() throws Exception {
        var proxyName = "gen.producer.Clock$$beef_ClientProxy";
        var generated = RuntimeClientProxyGenerator.generateProducerProxyAt(Clock.class, proxyName);
        var fixed = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

        var proxyClass = new ByteLoader().define(proxyName, generated.bytecode());
        var clock = (Clock) proxyClass.getDeclaredConstructor(Supplier.class).newInstance((Supplier<?>) () -> fixed);

        assertEquals(Instant.EPOCH, clock.instant());
        assertEquals(ZoneOffset.UTC, clock.getZone());
        assertEquals(0L, clock.millis());
        assertEquals(fixed, clock.withZone(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("eligibility: fully-public class is ELIGIBLE, others are rejected with a reason")
    void eligibilityVerdicts() {
        assertEquals(ProducerProxyEligibility.Reason.ELIGIBLE,
                ProducerProxyEligibility.of(Greeter.class));
        assertEquals(ProducerProxyEligibility.Reason.FINAL_CLASS,
                ProducerProxyEligibility.of(FinalThing.class));
        assertEquals(ProducerProxyEligibility.Reason.PROTECTED_VIRTUALS,
                ProducerProxyEligibility.of(ProtectedMethodThing.class));
        assertEquals(ProducerProxyEligibility.Reason.NOT_A_CLASS,
                ProducerProxyEligibility.of(AnInterface.class));
    }
}
