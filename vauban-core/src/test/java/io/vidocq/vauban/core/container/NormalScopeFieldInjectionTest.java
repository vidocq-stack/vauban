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
 * TDD for VAU-INJ-001: field @Inject d'un bean normal-scope doit retourner
 * un client proxy paresseux, jamais materialiser l'instance au boot.
 *
 * <p>Avant fix : le field restait null (ContextNotActiveException avalee).
 * Apres fix : le field contient un proxy ; l'acces hors-scope leve
 * ContextNotActiveException ; l'acces dans le scope retourne la bonne instance.
 */
@DisplayName("VAU-INJ-001 - field injection de beans normal-scope doit retourner un client proxy paresseux")
class NormalScopeFieldInjectionTest {

    // --- Beans de test ---

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
     * Bean @ApplicationScoped avec un @Inject field vers un bean @RequestScoped.
     * Au boot, aucun contexte request n'est actif — c'est le scenario du bug.
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
    @DisplayName("injection field normal-scope")
    class FieldInjection {

        @Test
        @DisplayName("le field injecte n'est PAS null apres injection (un proxy est injecte)")
        void injectedFieldIsNotNull() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                // VAU-INJ-001 : avant fix ce field etait null
                assertNotNull(appService.getRequestDep(),
                        "Le field @Inject vers un bean @RequestScoped ne doit pas etre null — un proxy doit etre injecte");
            }
        }

        @Test
        @DisplayName("invoquer le proxy hors-scope request leve ContextNotActiveException")
        void invokingProxyOutsideScopeLazilyThrows() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                // Le proxy est non-null mais l'invocation doit echouer hors-scope
                assertNotNull(appService.getRequestDep());
                assertThrows(ContextNotActiveException.class,
                        appService::callRequestDep,
                        "Invoquer une methode sur le proxy hors-scope doit lever ContextNotActiveException");
            }
        }

        @Test
        @DisplayName("invoquer le proxy dans un scope actif retourne la bonne instance")
        void invokingProxyInsideActiveScopeWorks() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                container.requestContext().runInScope(() -> {
                    assertNotNull(appService.getRequestDep());
                    assertEquals("result-1", appService.callRequestDep());
                    // Meme instance dans le meme scope : le compteur s'incremente
                    assertEquals("result-2", appService.callRequestDep());
                });
            }
        }

        @Test
        @DisplayName("nouvelle instance par scope : deux scopes distincts voient des instances differentes")
        void differentScopesGetDifferentInstances() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppServiceWithRequestDep.class)
                    .addBeanClass(RequestScopedService.class)
                    .build()) {

                var appService = container.select(AppServiceWithRequestDep.class);

                // Premier scope
                int[] counterAfterScope1 = {0};
                container.requestContext().runInScope(() -> {
                    appService.callRequestDep(); // result-1
                    appService.callRequestDep(); // result-2
                    counterAfterScope1[0] = appService.getRequestDep().getCounter();
                });
                assertEquals(2, counterAfterScope1[0]);

                // Deuxieme scope : nouvelle instance, compteur repart de zero
                container.requestContext().runInScope(() -> {
                    String first = appService.callRequestDep();
                    assertEquals("result-1", first,
                            "Un nouveau scope doit creer une nouvelle instance @RequestScoped");
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
