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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression for VAU-PROXY-INTERFACE: a normal-scoped bean whose bean type is an <em>interface</em>
 * must receive a client proxy. The subclass-based {@code RuntimeClientProxyGenerator} cannot subclass
 * an interface, so the container falls back to {@link java.lang.reflect.Proxy}, which delegates each
 * call to the contextual instance resolved in the active scope.
 *
 * <p>Concrete case: a {@code @RequestScoped @Produces} method returning an interface — exactly
 * MicroProfile JWT's {@code @Produces @RequestScoped JsonWebToken}. Before the fix, injecting the
 * interface-typed bean into a wider-scoped consumer failed (unproxyable / null field); after it, the
 * proxy resolves the per-request instance lazily.</p>
 */
@DisplayName("VAU-PROXY-INTERFACE - client proxy for an interface-typed normal-scoped bean")
class InterfaceClientProxyTest {

    /** An interface bean type (mirrors org.eclipse.microprofile.jwt.JsonWebToken). */
    public interface Identity {
        String name();

        /** What the contextual instance throws must reach the caller as it is (BUG-20261113-02). */
        default String refuse() {
            throw new IllegalStateException("refused by the contextual instance");
        }

        default void check() throws java.io.IOException {
            throw new java.io.IOException("checked by the contextual instance");
        }
    }

    @ApplicationScoped
    public static class IdentityProducer {
        @Produces
        @RequestScoped
        public Identity currentIdentity() {
            return () -> "alice";
        }
    }

    /** @ApplicationScoped consumer holding an interface-typed @RequestScoped dependency. */
    @ApplicationScoped
    public static class IdentityConsumer {
        @Inject
        Identity identity;

        public Identity identity() {
            return identity;
        }

        public String who() {
            return identity.name();
        }
    }

    @ApplicationScoped
    public static class GreetingProducer {
        @Produces
        @RequestScoped
        public java.util.function.Supplier<String> greeting() {
            return () -> "hello";
        }
    }

    @ApplicationScoped
    public static class GreetingConsumer {
        @Inject
        java.util.function.Supplier<String> greeting;

        public String greet() {
            return greeting.get();
        }
    }

    /**
     * A normal-scoped producer of a parameterized type has no bean type that is a class, and the container proxied the
     * class that declares the producer instead of the produced type ({@code InterceptorBeanWrapper
     * .resolveProxyTargetClass}): the injection point of {@code Supplier<String>} could not take the proxy
     * (BUG-20261117-18).
     */
    @Test
    @DisplayName("a normal-scoped producer of a parameterized interface injects as a proxy of that interface")
    void aNormalScopedProducerOfAParameterizedInterfaceInjectsAsAProxyOfIt() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(GreetingProducer.class)
                .addBeanClass(GreetingConsumer.class)
                .build()) {

            GreetingConsumer consumer = container.select(GreetingConsumer.class);

            container.requestContext().runInScope(() -> assertEquals("hello", consumer.greet()));
        }
    }

    /** A generic interface, and a class of it the producers below produce. */
    public interface Repo<T> {
        T first();
    }

    public static class Box<T> implements Repo<T> {
        private final T value;

        public Box(T value) {
            this.value = value;
        }

        @Override
        public T first() {
            return value;
        }
    }

    @ApplicationScoped
    public static class ListAndRepoProducer {
        @Produces
        @RequestScoped
        public Box<Integer> count() {
            return new Box<>(7);
        }

        @Produces
        @RequestScoped
        @jakarta.enterprise.inject.Typed(Repo.class)
        public Box<String> repo() {
            return new Box<>("grace");
        }
    }

    @ApplicationScoped
    public static class ListAndRepoConsumer {
        @Inject
        Repo<Integer> count;

        @Inject
        Repo<String> repo;

        public String read() {
            return count.first() + " " + repo.first();
        }
    }

    /**
     * The proxy of a producer of {@code Box<Integer>}, whose bean types are all parameterized, and of a
     * {@code @Typed(Repo.class)} producer of {@code Box<String>}, whose only type is {@code Repo<String>}, was one of
     * the class that declares the producer, and no {@code Repo} (BUG-20261117-18).
     */
    @Test
    @DisplayName("a producer of a parameterized class, or @Typed to a parameterized interface, injects as its type")
    void aNormalScopedProducerOfAParameterizedClassOrTypedInterfaceInjectsAsItsType() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(ListAndRepoProducer.class)
                .addBeanClass(ListAndRepoConsumer.class)
                .build()) {

            ListAndRepoConsumer consumer = container.select(ListAndRepoConsumer.class);

            container.requestContext().runInScope(() -> assertEquals("7 grace", consumer.read()));
        }
    }

    @ApplicationScoped
    public static class NamesProducer {
        @Produces
        @RequestScoped
        public java.util.ArrayList<String> names() {
            return new java.util.ArrayList<>(java.util.List.of("ada"));
        }
    }

    @ApplicationScoped
    public static class NamesConsumer {
        @Inject
        java.util.List<String> names;

        public String first() {
            return names.getFirst();
        }
    }

    /**
     * A normal-scoped producer of {@code ArrayList<String>} is proxyable (CDI 4.1, 3.10), and Weld deploys it.
     * Vauban cannot define a class in {@code java.util}: as Weld, it puts the proxy in the producer's package, which
     * forwards the public methods, and says that the package-private ones run on the proxy (BUG-20261118-24).
     */
    @Test
    @DisplayName("a normal-scoped producer of ArrayList<String> injects as a List, with a warning")
    void aNormalScopedProducerOfAJdkClassWithPackagePrivateMethodsInjects() {
        // The tests run inside io.vidocq.vauban.core, which does not read java.logging (LifecycleObserverFailureTest).
        getClass().getModule().addReads(ModuleLayer.boot().findModule("java.logging").orElseThrow());
        var out = new java.io.ByteArrayOutputStream();
        var handler = new java.util.logging.StreamHandler(out, new java.util.logging.SimpleFormatter());
        handler.setLevel(java.util.logging.Level.WARNING);
        var logger = java.util.logging.Logger.getLogger(InterceptorBeanWrapper.class.getName());
        logger.addHandler(handler);
        try (var container = VaubanContainer.builder()
                .addBeanClass(NamesProducer.class)
                .addBeanClass(NamesConsumer.class)
                .build()) {

            NamesConsumer consumer = container.select(NamesConsumer.class);

            container.requestContext().runInScope(() -> assertEquals("ada", consumer.first()));
        } finally {
            logger.removeHandler(handler);
        }

        handler.flush();
        String logged = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(logged.contains("java.util.ArrayList")
                && logged.contains(NamesProducer.class.getName()) && logged.contains("package-private"), logged);
    }

    /**
     * BUG-20261113-02: the proxy called the contextual instance through reflection and let its
     * {@code InvocationTargetException} escape, so the caller got an {@code UndeclaredThrowableException}
     * instead of the exception the instance threw.
     */
    @Test
    @DisplayName("an exception of the contextual instance reaches the caller of the interface proxy unwrapped")
    void anExceptionOfTheContextualInstanceReachesTheCallerUnwrapped() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(IdentityProducer.class)
                .addBeanClass(IdentityConsumer.class)
                .build()) {

            Identity identity = container.select(IdentityConsumer.class).identity();

            container.requestContext().runInScope(() -> {
                var unchecked = assertThrows(IllegalStateException.class, identity::refuse);
                assertEquals("refused by the contextual instance", unchecked.getMessage());
                var checked = assertThrows(java.io.IOException.class, identity::check);
                assertEquals("checked by the contextual instance", checked.getMessage());
            });
        }
    }

    @Test
    @DisplayName("interface-typed @RequestScoped bean injects as a proxy and resolves inside an active scope")
    void interfaceTypedRequestScopedBeanInjectsAndResolves() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(IdentityProducer.class)
                .addBeanClass(IdentityConsumer.class)
                .build()) {

            IdentityConsumer consumer = container.select(IdentityConsumer.class);

            // The field must be a non-null client proxy (interface -> java.lang.reflect.Proxy).
            assertNotNull(consumer.identity(),
                    "interface-typed @RequestScoped field must be injected as a non-null proxy");

            // Out of scope, invoking the proxy throws (no active request context).
            assertThrows(ContextNotActiveException.class, consumer::who,
                    "invoking the interface proxy out of scope must throw ContextNotActiveException");

            // In scope, the proxy delegates to the per-request produced instance.
            container.requestContext().runInScope(() ->
                    assertEquals("alice", consumer.who(),
                            "interface proxy must delegate to the contextual instance in the active request scope"));
        }
    }
}
