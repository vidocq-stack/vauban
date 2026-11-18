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
package io.vidocq.vauban.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A client proxy states the class it stands for, which it may not extend: past a final method, or for a final bean
 * class, it extends a class above the bean class, or {@code Object} (BUG-20261118-20, BUG-20261118-21). Every path
 * generates it final; a producer's proxy names the produced class by the hash of its binary name.
 */
class ClientProxyClassTest {

    /** A bean class, and its proxy as the common case makes it: a subclass. */
    static class Plain {
    }

    static final class Plain_ClientProxy extends Plain {
    }

    /** A bean class with a final method, and its proxy, which extends a class above it. */
    static class Above {
    }

    static class Sealed extends Above {
        public final String sealedOff() {
            return "sealed";
        }
    }

    static final class Sealed_ClientProxy extends Above {
    }

    /** A final bean class, whose proxy extends Object. */
    static final class Closed {
    }

    static final class Closed_ClientProxy {
    }

    /** A producer's proxy: named after the produced class with the hash of its binary name, it extends that class. */
    static class Made {
    }

    static final class Made$$39b65daf_ClientProxy extends Made {
    }

    /** A producer's proxy past a final method of the produced class: it extends a class above, which it names not. */
    static class Top {
    }

    static final class Made$$39b65daf$Past_ClientProxy extends Top {
    }

    /** Named like a proxy, but no class goes by the name it is made from, and it extends Object. */
    static final class Orphan_ClientProxy {
    }

    /** Named like a proxy, the class of that name is no subclass of what it extends. */
    static class Other {
    }

    static class Plain2 {
    }

    static final class Plain2_ClientProxy extends Other {
    }

    /** Named like the proxy of {@link Plain}, but not final, as no generated proxy is: a class of the application. */
    static class Lookalike {
    }

    static class Lookalike_ClientProxy extends Lookalike {
    }

    @Test
    void theProxyOfABeanClassItExtends() {
        assertEquals(Optional.of(Plain.class), ClientProxyClass.beanClassOf(Plain_ClientProxy.class));
    }

    @Test
    void theProxyOfABeanClassWithAFinalMethod() {
        assertEquals(Optional.of(Sealed.class), ClientProxyClass.beanClassOf(Sealed_ClientProxy.class));
    }

    @Test
    void theProxyOfAFinalBeanClass() {
        assertEquals(Optional.of(Closed.class), ClientProxyClass.beanClassOf(Closed_ClientProxy.class));
    }

    @Test
    void theProxyOfAProducer() {
        assertEquals(Optional.of(Made.class), ClientProxyClass.beanClassOf(Made$$39b65daf_ClientProxy.class));
    }

    @Test
    void theProxyOfAProducerPastAFinalMethodNamesNothing() {
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(Made$$39b65daf$Past_ClientProxy.class));
    }

    @Test
    void noProxy() {
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(Plain.class));
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(Object.class));
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(Runnable.class));
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(int.class));
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(Orphan_ClientProxy.class));
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(Lookalike_ClientProxy.class), "not final");
    }

    @Test
    void aClassOfTheNameThatIsNoSubclassOfWhatTheProxyExtends() {
        assertEquals(Optional.empty(), ClientProxyClass.beanClassOf(Plain2_ClientProxy.class));
    }
}
