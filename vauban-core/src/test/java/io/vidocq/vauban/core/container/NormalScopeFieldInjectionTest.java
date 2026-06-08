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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD for VAU-INJ-001: a field @Inject of a normal-scope bean must return
 * a lazy client proxy, never materialize the instance at boot.
 *
 * <p>Before fix: the field stayed null (ContextNotActiveException swallowed).
 * After fix: the field holds a proxy; out-of-scope access throws
 * ContextNotActiveException; in-scope access returns the right instance.
 */
@DisplayName("VAU-INJ-001 - normal-scope bean field injection must return a lazy client proxy")
class NormalScopeFieldInjectionTest {

    // --- Test beans ---

    @RequestScoped
    public static class RequestScopedService {
        private int counter = 0;

        public String compute() {
            return "result-" + (++counter);
        }

        public int getCounter() {
            return counter;
        }
    }

    /**
     * @ApplicationScoped bean with an @Inject field toward a @RequestScoped bean.
     * At boot, no request context is active — this is the bug scenario.
     */
    @ApplicationScoped
    public static class AppServiceWithRequestDep {
        @Inject
        RequestScopedService requestDep;

        public RequestScopedService getRequestDep() {
            return requestDep;
        }

        public String callRequestDep() {
            return requestDep.compute();
        }
    }

    // --- Tests ---

    @Nested
    @DisplayName("normal-scope field injection")
    class FieldInjection {

        @Test
        @DisplayName("the injected field is NOT null after injection (a proxy is injected)")
        void injectedFieldIsNotNull() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                // VAU-INJ-001: before fix this field was null
                assertNotNull(appService.getRequestDep(),
                        "The @Inject field toward a @RequestScoped bean must not be null — a proxy must be injected");
            }
        }

        @Test
        @DisplayName("invoking the proxy outside the request scope throws ContextNotActiveException")
        void invokingProxyOutsideScopeLazilyThrows() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                // The proxy is non-null but the invocation must fail out-of-scope
                assertNotNull(appService.getRequestDep());
                assertThrows(ContextNotActiveException.class,
                        appService::callRequestDep,
                        "Invoking a method on the proxy out-of-scope must throw ContextNotActiveException");
            }
        }

        @Test
        @DisplayName("invoking the proxy inside an active scope returns the right instance")
        void invokingProxyInsideActiveScopeWorks() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                container.requestContext().runInScope(() -> {
                    assertNotNull(appService.getRequestDep());
                    assertEquals("result-1", appService.callRequestDep());
                    // Same instance within the same scope: the counter increments
                    assertEquals("result-2", appService.callRequestDep());
                });
            }
        }

        @Test
        @DisplayName("new instance per scope: two distinct scopes see different instances")
        void differentScopesGetDifferentInstances() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                // First scope
                int[] counterAfterScope1 = {0};
                container.requestContext().runInScope(() -> {
                    appService.callRequestDep(); // result-1
                    appService.callRequestDep(); // result-2
                    counterAfterScope1[0] = appService.getRequestDep().getCounter();
                });
                assertEquals(2, counterAfterScope1[0]);

                // Second scope: new instance, counter restarts from zero
                container.requestContext().runInScope(() -> {
                    String first = appService.callRequestDep();
                    assertEquals("result-1", first,
                            "A new scope must create a new @RequestScoped instance");
                });
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Fix 4 — regression: @ApplicationScoped intercepted bean with
    //   (a) @Inject Instance<T> where T is @Singleton-produced
    //   (b) @Inject SomeService where SomeService is @Singleton @Intercepted
    // Both fields must NOT be null after container startup.
    // ---------------------------------------------------------------------------

    /** A simple interceptor binding annotation used in tests below. */
    @InterceptorBinding
    @Inherited
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Logged {}

    /** Interceptor that records invocations but does nothing else. */
    @Logged
    @Interceptor
    public static class LoggingInterceptor {
        static final List<String> calls = new ArrayList<>();

        @AroundInvoke
        public Object intercept(InvocationContext ctx) throws Exception {
            calls.add(ctx.getMethod().getName());
            return ctx.proceed();
        }
    }

    /** A simple service produced as @Singleton. */
    public static class SingletonService {
        public String hello() { return "hello"; }
    }

    /** Producer that provides SingletonService as a @Singleton bean. */
    @ApplicationScoped
    public static class SingletonServiceProducer {
        @Produces
        @Singleton
        public SingletonService produce() { return new SingletonService(); }
    }

    /**
     * A @Singleton bean that also carries an interceptor binding.
     * Models ProductRepositoryImpl: @Singleton @Transactional.
     */
    @Singleton
    @Logged
    public static class InterceptedSingletonService {
        @Logged
        public String greet() { return "greet"; }
    }

    /**
     * An @ApplicationScoped JAX-RS-like resource that:
     *   - is intercepted (has @Logged methods)
     *   - injects Instance<SingletonService>
     *   - injects InterceptedSingletonService directly
     *
     * Models DatabaseInspectorResource + ProductResource combined.
     */
    @ApplicationScoped
    @Logged
    public static class InterceptedResource {

        @Inject
        Instance<SingletonService> singletonInstance;  // NPE case 1

        @Inject
        InterceptedSingletonService singletonDep;       // NPE case 2

        @Logged
        public String callInstance() {
            return singletonInstance.get().hello();
        }

        @Logged
        public String callDirect() {
            return singletonDep.greet();
        }

        public Instance<SingletonService> getSingletonInstance() { return singletonInstance; }
        public InterceptedSingletonService getSingletonDep() { return singletonDep; }
    }

    @Nested
    @DisplayName("Fix 4 - regression: intercepted @ApplicationScoped bean fields not null")
    class Fix4RegressionTest {

        @Test
        @DisplayName("@Inject Instance<T> field must not be null in intercepted @ApplicationScoped bean")
        void instanceFieldNotNullInInterceptedAppScopedBean() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(InterceptedResource.class)
                    .addBeanClass(SingletonServiceProducer.class)
                    .addBeanClass(InterceptedSingletonService.class)
                    .addBeanClass(LoggingInterceptor.class)
                    .build()) {

                var resource = container.select(InterceptedResource.class);
                assertNotNull(resource.getSingletonInstance(),
                        "@Inject Instance<SingletonService> must not be null in intercepted @ApplicationScoped bean");
            }
        }

        @Test
        @DisplayName("@Inject Instance<T>.get() must return the @Singleton instance")
        void instanceFieldGetWorksInInterceptedAppScopedBean() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(InterceptedResource.class)
                    .addBeanClass(SingletonServiceProducer.class)
                    .addBeanClass(InterceptedSingletonService.class)
                    .addBeanClass(LoggingInterceptor.class)
                    .build()) {

                var resource = container.select(InterceptedResource.class);
                assertEquals("hello", resource.callInstance(),
                        "Instance<SingletonService>.get().hello() must work");
            }
        }

        @Test
        @DisplayName("@Inject @Singleton-intercepted field must not be null in intercepted @ApplicationScoped bean")
        void singletonInterceptedDepNotNullInInterceptedAppScopedBean() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(InterceptedResource.class)
                    .addBeanClass(SingletonServiceProducer.class)
                    .addBeanClass(InterceptedSingletonService.class)
                    .addBeanClass(LoggingInterceptor.class)
                    .build()) {

                var resource = container.select(InterceptedResource.class);
                assertNotNull(resource.getSingletonDep(),
                        "@Inject InterceptedSingletonService must not be null in intercepted @ApplicationScoped bean");
            }
        }

        @Test
        @DisplayName("@Inject @Singleton-intercepted field must be callable in intercepted @ApplicationScoped bean")
        void singletonInterceptedDepCallableInInterceptedAppScopedBean() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(InterceptedResource.class)
                    .addBeanClass(SingletonServiceProducer.class)
                    .addBeanClass(InterceptedSingletonService.class)
                    .addBeanClass(LoggingInterceptor.class)
                    .build()) {

                var resource = container.select(InterceptedResource.class);
                assertEquals("greet", resource.callDirect(),
                        "singletonDep.greet() must return 'greet'");
            }
        }
    }
}
