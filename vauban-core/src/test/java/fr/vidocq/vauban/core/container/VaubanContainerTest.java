package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanContainer - conteneur CDI")
class VaubanContainerTest {

    @ApplicationScoped
    public static class AppService {
        public String hello() {
            return "hello";
        }
    }

    @Dependent
    public static class DependentHelper {
        private static int counter = 0;

        public DependentHelper() {
            counter++;
        }

        public int id() {
            return counter;
        }

        public static void resetCounter() {
            counter = 0;
        }
    }

    @RequestScoped
    public static class RequestBean {
        private String value = "request";
        public String getValue() { return value; }
    }

    @ApplicationScoped
    public static class Repository {
        public String findById(int id) { return "entity-" + id; }
    }

    @ApplicationScoped
    public static class Service {
        @Inject Repository repository;
        public String process(int id) { return repository.findById(id); }
    }

    @ApplicationScoped
    public static class CtorInjectedService {
        private final Repository repository;

        protected CtorInjectedService() { this.repository = null; }

        @Inject
        public CtorInjectedService(Repository repository) {
            this.repository = repository;
        }

        public String process(int id) { return repository.findById(id); }
    }

    @Nested
    @DisplayName("bootstrap et shutdown")
    class Lifecycle {

        @Test
        @DisplayName("demarre et arrete le conteneur")
        void shouldStartAndStop() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppService.class)
                    .build()) {
                assertTrue(container.isRunning());
            }
        }

        @Test
        @DisplayName("lookup un bean @ApplicationScoped")
        void shouldLookupApplicationScopedBean() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppService.class)
                    .build()) {
                var service = container.select(AppService.class);
                assertNotNull(service);
                assertEquals("hello", service.hello());
            }
        }

        @Test
        @DisplayName("@ApplicationScoped retourne la meme instance")
        void shouldReturnSameInstanceForApplicationScoped() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppService.class)
                    .build()) {
                var s1 = container.select(AppService.class);
                var s2 = container.select(AppService.class);
                assertSame(s1, s2);
            }
        }
    }

    @Nested
    @DisplayName("scopes")
    class Scopes {

        @Test
        @DisplayName("@Dependent cree une nouvelle instance a chaque lookup")
        void shouldCreateNewDependentInstance() {
            DependentHelper.resetCounter();
            try (var container = VaubanContainer.builder()
                    .addBeanClass(DependentHelper.class)
                    .build()) {
                var h1 = container.select(DependentHelper.class);
                var h2 = container.select(DependentHelper.class);
                assertNotSame(h1, h2);
            }
        }

        @Test
        @org.junit.jupiter.api.Disabled("RequestScoped proxy not returned by select() yet")
        @DisplayName("@RequestScoped fonctionne dans un contexte actif")
        void shouldWorkInActiveRequestContext() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(RequestBean.class)
                    .build()) {
                container.requestContext().activate();
                try {
                    var bean = container.select(RequestBean.class);
                    assertNotNull(bean);
                    assertEquals("request", bean.getValue());

                    var bean2 = container.select(RequestBean.class);
                    assertSame(bean, bean2);
                } finally {
                    container.requestContext().deactivate();
                }
            }
        }
    }

    @Nested
    @DisplayName("factory custom")
    class CustomFactory {

        @Test
        @DisplayName("utilise une factory custom")
        void shouldUseCustomFactory() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppService.class)
                    .addFactory(AppService.class, AppService::new)
                    .build()) {
                var service = container.select(AppService.class);
                assertNotNull(service);
            }
        }
    }

    @Nested
    @DisplayName("erreurs")
    class Errors {

        @Test
        @DisplayName("lance UnsatisfiedResolutionException pour type inconnu")
        void shouldThrowForUnknownType() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppService.class)
                    .build()) {
                assertThrows(jakarta.enterprise.inject.UnsatisfiedResolutionException.class,
                        () -> container.select(String.class));
            }
        }
    }

    @Nested
    @DisplayName("injection entre beans")
    class Injection {

        @Test
        @DisplayName("injecte les dependances @Inject fields")
        void shouldInjectFieldDependencies() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(Service.class)
                    .addBeanClass(Repository.class)
                    .build()) {
                var service = container.select(Service.class);
                assertEquals("entity-1", service.process(1));
            }
        }

        @Test
        @DisplayName("injecte les dependances via constructeur @Inject")
        void shouldInjectConstructorDependencies() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(CtorInjectedService.class)
                    .addBeanClass(Repository.class)
                    .build()) {
                var service = container.select(CtorInjectedService.class);
                assertNotNull(service);
                assertEquals("entity-42", service.process(42));
            }
        }
    }

    // --- Lifecycle test beans ---

    @ApplicationScoped
    public static class LifecycleBean {
        private boolean postConstructCalled;
        private boolean preDestroyCalled;
        public static boolean preDestroyCalledStatic;

        @jakarta.annotation.PostConstruct
        public void init() { postConstructCalled = true; }

        @jakarta.annotation.PreDestroy
        public void cleanup() { preDestroyCalled = true; preDestroyCalledStatic = true; }

        public boolean isPostConstructCalled() { return postConstructCalled; }
        public boolean isPreDestroyCalled() { return preDestroyCalled; }
    }

    @ApplicationScoped
    public static class InitMethodBean {
        private Repository repo;

        @Inject
        public void setRepo(Repository repo) { this.repo = repo; }

        public Repository getRepo() { return repo; }
    }

    @Nested
    @DisplayName("lifecycle callbacks")
    class LifecycleCallbacks {

        @Test
        @DisplayName("appelle @PostConstruct apres creation")
        void shouldCallPostConstruct() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(LifecycleBean.class)
                    .build()) {
                var bean = container.select(LifecycleBean.class);
                assertTrue(bean.isPostConstructCalled());
            }
        }

        @Test
        @DisplayName("appelle @PreDestroy a la fermeture du conteneur")
        void shouldCallPreDestroy() {
            // Use a static flag to verify @PreDestroy was called
            // (proxy becomes invalid after container close)
            LifecycleBean.preDestroyCalledStatic = false;
            try (var container = VaubanContainer.builder()
                    .addBeanClass(LifecycleBean.class)
                    .build()) {
                var bean = container.select(LifecycleBean.class);
                // Force proxy to create the real instance by calling a method
                bean.isPostConstructCalled();
                assertFalse(LifecycleBean.preDestroyCalledStatic);
            }
            assertTrue(LifecycleBean.preDestroyCalledStatic);
        }

        @Test
        @DisplayName("appelle les methodes @Inject initialisatrices")
        void shouldCallInitializerMethods() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(InitMethodBean.class)
                    .addBeanClass(Repository.class)
                    .build()) {
                var bean = container.select(InitMethodBean.class);
                assertNotNull(bean.getRepo());
            }
        }
    }

    // --- Producer test beans ---

    public static class DataSource {
        public final String url;
        public DataSource(String url) { this.url = url; }
    }

    @ApplicationScoped
    public static class ProducerConfig {
        @Produces
        @ApplicationScoped
        public DataSource createDataSource() {
            return new DataSource("jdbc:vauban:test");
        }
    }

    public static class AppInfo {
        public final String name;
        public AppInfo(String name) { this.name = name; }
    }

    @ApplicationScoped
    public static class FieldProducerConfig {
        @Produces
        public AppInfo appInfo = new AppInfo("Vauban");
    }

    @Nested
    @DisplayName("producer beans")
    class ProducerBeans {

        @Test
        @DisplayName("producer method cree un bean")
        void shouldCreateBeanFromProducerMethod() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(ProducerConfig.class)
                    .build()) {
                var ds = container.select(DataSource.class);
                assertNotNull(ds);
                assertEquals("jdbc:vauban:test", ds.url);
            }
        }

        @Test
        @DisplayName("producer method retourne la meme instance en @ApplicationScoped")
        void shouldReturnSameInstanceForApplicationScopedProducer() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(ProducerConfig.class)
                    .build()) {
                var ds1 = container.select(DataSource.class);
                var ds2 = container.select(DataSource.class);
                assertSame(ds1, ds2);
            }
        }

        @Test
        @DisplayName("producer field cree un bean")
        void shouldCreateBeanFromProducerField() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(FieldProducerConfig.class)
                    .build()) {
                var info = container.select(AppInfo.class);
                assertNotNull(info);
                assertEquals("Vauban", info.name);
            }
        }
    }

    @Nested
    @DisplayName("BeanManager")
    class BeanManagerTest {

        @Test
        @DisplayName("fournit un BeanManager fonctionnel")
        void shouldProvideBeanManager() {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(AppService.class)
                    .build()) {
                var bm = container.getBeanManager();
                assertNotNull(bm);

                var beans = bm.getBeans(AppService.class);
                assertFalse(beans.isEmpty());

                var bean = bm.resolve(beans);
                assertNotNull(bean);

                var ctx = bm.createCreationalContext(null);
                var ref = bm.getReference(bean, AppService.class, ctx);
                assertInstanceOf(AppService.class, ref);
            }
        }
    }
}
