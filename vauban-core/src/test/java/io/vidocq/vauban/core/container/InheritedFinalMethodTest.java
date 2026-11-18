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

import io.vidocq.vauban.core.container.finalpackage.PackageFinalBase;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.UnproxyableResolutionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean that inherits a final method, neither private nor static, is unproxyable (CDI 4.1 §3.10), as one that declares
 * the method is: an injected normal-scoped one and an intercepted one fail the deployment, and a lookup of a
 * normal-scoped one throws. The container used to read the
 * bean class's own methods only, so such a bean started and a call of the method ran on the proxy or the subclass
 * itself (BUG-20261118-19). The superclasses here are no beans of the deployment: the index the validator walks does
 * not hold them, as for a superclass of a library.
 */
@DisplayName("A bean that inherits a final method is unproxyable")
class InheritedFinalMethodTest {

    private static final String FINAL_METHOD = "has final method tag";

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Counted {}

    @Counted
    @Interceptor
    @Priority(1)
    public static class CountingInterceptor {
        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    public static class FinalBase {
        public final String tag() {
            return "final";
        }
    }

    @ApplicationScoped
    public static class InheritsFinal extends FinalBase {
    }

    @Counted
    @Dependent
    public static class InterceptedInheritsFinal extends FinalBase {
    }

    /** Injects the normal-scoped bean: an unproxyable one then fails the deployment (CDI 4.1 §3.10). */
    @Dependent
    public static class Consumer {
        @Inject
        InheritsFinal bean;
    }

    /** Inherits nothing of {@link PackageFinalBase#tagOf}'s package, yet its package-private final method counts. */
    @ApplicationScoped
    public static class InheritsPackageFinal extends PackageFinalBase {
    }

    @Dependent
    public static class PackageFinalConsumer {
        @Inject
        InheritsPackageFinal bean;
    }

    /** What a bean whose class inherits a final method is looked up and injected as: an interface, proxyable. */
    public interface Api {
        String other();
    }

    @ApplicationScoped
    public static class Svc extends FinalBase implements Api {
        @Override
        public String other() {
            return "other";
        }
    }

    @Dependent
    public static class ApiConsumer {
        @Inject
        Api api;
    }

    @Dependent
    public static class SvcConsumer {
        @Inject
        Svc svc;
    }

    /** Produced, not a bean of its own: its class inherits a final method, its interface is proxyable. */
    public static class Product extends FinalBase implements Api {
        @Override
        public String other() {
            return "product";
        }
    }

    @Dependent
    public static class Products {
        @Produces
        @ApplicationScoped
        Product product() {
            return new Product();
        }
    }

    /** Its final {@code state()} reads what the contextual instance was given. */
    public static class StatefulFinalBase {
        private String state;

        @jakarta.annotation.PostConstruct
        void init() {
            state = "instance";
        }

        public final String state() {
            return state;
        }
    }

    public interface Stateful {
        String state();
    }

    /** Implements {@link Stateful#state()} with the final method it inherits: no subclass proxy can forward it. */
    @ApplicationScoped
    public static class StatefulSvc extends StatefulFinalBase implements Stateful {
    }

    /** A private and a static final method: no subclass overrides them, and no proxy needs to. */
    public static class HarmlessFinalBase {
        private final String hidden() {
            return "hidden";
        }

        public static final String shared() {
            return "shared";
        }

        public String reveal() {
            return hidden();
        }
    }

    @ApplicationScoped
    public static class InheritsHarmlessFinal extends HarmlessFinalBase {
    }

    @Test
    @DisplayName("an injected normal-scoped bean that inherits a final method fails the deployment")
    void normalScopedInjected() {
        var builder = VaubanContainer.builder().addBeanClass(InheritsFinal.class).addBeanClass(Consumer.class);
        var failure = assertThrows(DeploymentException.class, builder::build);
        assertTrue(messages(failure).contains(FINAL_METHOD), messages(failure));
    }

    @Test
    @DisplayName("a normal-scoped bean that inherits a final method is not resolved by a lookup")
    void normalScopedLookedUp() {
        try (var container = VaubanContainer.builder().addBeanClass(InheritsFinal.class).build()) {
            var failure = assertThrows(UnproxyableResolutionException.class,
                    () -> container.select(InheritsFinal.class));
            assertTrue(messages(failure).contains(FINAL_METHOD), messages(failure));
        }
    }

    @Test
    @DisplayName("a package-private final method of a superclass in another package counts")
    void packagePrivateOfAnotherPackage() {
        var builder = VaubanContainer.builder()
                .addBeanClass(InheritsPackageFinal.class)
                .addBeanClass(PackageFinalConsumer.class);
        var failure = assertThrows(DeploymentException.class, builder::build);
        assertTrue(messages(failure).contains(FINAL_METHOD), messages(failure));
    }

    @Test
    @DisplayName("a bean whose class inherits a final method, injected and looked up as an interface, works")
    void requestedAsAnInterface() {
        try (var container = VaubanContainer.builder().addBeanClass(Svc.class).addBeanClass(ApiConsumer.class)
                .build()) {
            assertEquals("other", container.select(ApiConsumer.class).api.other());
            assertEquals("other", container.select(Api.class).other());
        }
    }

    @Test
    @DisplayName("the same bean, injected as its class, fails the deployment")
    void injectedAsItsClass() {
        var builder = VaubanContainer.builder().addBeanClass(Svc.class).addBeanClass(SvcConsumer.class);
        var failure = assertThrows(DeploymentException.class, builder::build);
        assertTrue(messages(failure).contains(FINAL_METHOD), messages(failure));
    }

    @Test
    @DisplayName("the same bean, looked up as its class, is not resolved")
    void lookedUpAsItsClass() {
        try (var container = VaubanContainer.builder().addBeanClass(Svc.class).build()) {
            var failure = assertThrows(UnproxyableResolutionException.class, () -> container.select(Svc.class));
            assertTrue(messages(failure).contains(FINAL_METHOD), messages(failure));
        }
    }

    @Test
    @DisplayName("a normal-scoped producer of such a class, looked up as the interface, works; as the class, not")
    void producer() {
        try (var container = VaubanContainer.builder().addBeanClass(Products.class).build()) {
            assertEquals("product", container.select(Api.class).other());
            var failure = assertThrows(UnproxyableResolutionException.class, () -> container.select(Product.class));
            assertTrue(messages(failure).contains(FINAL_METHOD), messages(failure));
        }
    }

    @Test
    @DisplayName("an intercepted bean that inherits a final method fails the deployment")
    void intercepted() {
        var builder = VaubanContainer.builder()
                .addBeanClass(CountingInterceptor.class)
                .addBeanClass(InterceptedInheritsFinal.class);
        var failure = assertThrows(DeploymentException.class, builder::build);
        assertTrue(messages(failure).contains(FINAL_METHOD), messages(failure));
    }

    @Test
    @DisplayName("a private or static final method of a superclass leaves the bean proxyable")
    void privateOrStatic() {
        try (var container = VaubanContainer.builder().addBeanClass(InheritsHarmlessFinal.class).build()) {
            assertEquals("hidden", container.select(InheritsHarmlessFinal.class).reveal());
        }
    }

    @Test
    @Disabled("BUG-20261118-20: the client proxy subclasses the bean class and cannot override state(), which then"
            + " runs on the proxy; an interface-typed proxy would forward it")
    @DisplayName("looked up as an interface the inherited final method implements, the call reaches the instance")
    void anInterfaceMethodTheFinalMethodImplements() {
        try (var container = VaubanContainer.builder().addBeanClass(StatefulSvc.class).build()) {
            assertEquals("instance", container.select(Stateful.class).state());
        }
    }

    /** The messages of {@code failure} and of its causes, which the deployment may wrap. */
    private static String messages(Throwable failure) {
        var all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }
}
