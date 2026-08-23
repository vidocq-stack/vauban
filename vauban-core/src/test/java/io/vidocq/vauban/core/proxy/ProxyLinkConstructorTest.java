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

import io.vidocq.vauban.api.ProxyLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The generated client proxy must chain to the bean's {@link ProxyLink} marker constructor
 * when one is declared, instead of replaying a business constructor with default arguments.
 *
 * <h2>Context (Vidocq/vauban#24)</h2>
 * The proxy is a subclass of the bean, and the JVM forces its {@code <init>} to chain to a
 * constructor of the bean. Chaining to a business constructor has two observable defects:
 * the bean's construction side effects run once per proxy on top of the contextual
 * instance, and a constructor that dereferences an (injected) parameter throws
 * {@code NullPointerException} because the proxy passes defaults. The opt-in marker
 * constructor gives the proxy a side-effect-free entry point.
 */
@DisplayName("Client proxy — ProxyLink marker constructor")
class ProxyLinkConstructorTest {

    /** Bean with construction side effects and a marker constructor. */
    public static class Counted {
        static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        public Counted() {
            CONSTRUCTIONS.incrementAndGet();
        }

        protected Counted(ProxyLink link) {
            // Client-proxy entry point — no side effects.
        }

        public String greet() {
            return "hi";
        }
    }

    public static class Collaborator {
        public String name() {
            return "collab";
        }
    }

    /** The single-@Inject-constructor style: the only business constructor uses its parameter. */
    public static class DerefsItsParameter {
        private final String name;

        public DerefsItsParameter(Collaborator collaborator) {
            this.name = collaborator.name();
        }

        protected DerefsItsParameter(ProxyLink link) {
            this.name = null;
        }

        public String name() {
            return name;
        }
    }

    /** No marker: the historical default-argument chaining must keep working unchanged. */
    public static class NoMarker {
        static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        public NoMarker() {
            CONSTRUCTIONS.incrementAndGet();
        }

        public String greet() {
            return "hi";
        }
    }

    @Test
    @DisplayName("creating the proxy does not run the bean's business constructor")
    void markerConstructorSparesTheBusinessConstructor() throws Exception {
        Counted.CONSTRUCTIONS.set(0);
        Counted real = new Counted();
        assertEquals(1, Counted.CONSTRUCTIONS.get());

        Object proxy = instantiate(define(Counted.class), () -> real);

        assertEquals(1, Counted.CONSTRUCTIONS.get(),
                "the proxy must chain to the ProxyLink constructor, not replay the no-arg one");
        assertEquals("hi", invoke(proxy, "greet"), "delegation must still reach the contextual instance");
    }

    @Test
    @DisplayName("a bean whose only business constructor dereferences its parameter proxies without NPE")
    void injectStyleConstructorBeanProxiesThroughTheMarker() throws Exception {
        Class<?> proxyClass = define(DerefsItsParameter.class);

        Object proxy = assertDoesNotThrow(
                () -> instantiate(proxyClass, () -> new DerefsItsParameter(new Collaborator())),
                "the proxy must not chain to the parameter-dereferencing constructor");
        assertEquals("collab", invoke(proxy, "name"));
    }

    @Test
    @DisplayName("a bean without marker keeps the historical no-arg chaining")
    void beanWithoutMarkerIsUnchanged() throws Exception {
        NoMarker.CONSTRUCTIONS.set(0);
        NoMarker real = new NoMarker();

        Object proxy = instantiate(define(NoMarker.class), () -> real);

        // Documented phase-2 gap: without a marker the bean constructor still runs for the
        // proxy shell (2 constructions for one contextual instance).
        assertEquals(2, NoMarker.CONSTRUCTIONS.get());
        assertEquals("hi", invoke(proxy, "greet"));
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static Class<?> define(Class<?> beanClass) throws Exception {
        var generated = RuntimeClientProxyGenerator.generate(beanClass);
        var lookup = MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup());
        return lookup.defineClass(generated.bytecode());
    }

    private static Object instantiate(Class<?> proxyClass, Supplier<Object> delegate) throws Exception {
        Object proxy = proxyClass.getDeclaredConstructor().newInstance();
        proxyClass.getMethod(ClientProxyShape.SET_DELEGATE_METHOD, Supplier.class)
                .invoke(proxy, delegate);
        return proxy;
    }

    private static Object invoke(Object proxy, String method) throws Exception {
        return proxy.getClass().getMethod(method).invoke(proxy);
    }
}
