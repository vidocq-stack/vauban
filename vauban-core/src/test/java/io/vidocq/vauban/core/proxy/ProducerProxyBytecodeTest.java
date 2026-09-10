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
        protected void secret() {}
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

        var proxy = proxyClass.getDeclaredConstructor().newInstance();
        proxyClass.getMethod("$$setDelegate", Supplier.class)
                .invoke(proxy, (Supplier<?>) Greeter::new);

        // Forwarding: calling the produced-type method on the proxy runs on the delegate instance.
        assertEquals("hi", ((Greeter) proxy).greet());
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
