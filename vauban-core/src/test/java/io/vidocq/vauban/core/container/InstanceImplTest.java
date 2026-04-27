package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Instance<T> - lookup programmatique")
class InstanceImplTest {

    @ApplicationScoped
    public static class Greeter {
        public String greet() {
            return "hello";
        }
    }

    @ApplicationScoped
    public static class Consumer {
        @Inject
        Instance<Greeter> greeterInstance;

        public String greetViaInstance() {
            return greeterInstance.get().greet();
        }

        public boolean isResolvable() {
            return greeterInstance.isResolvable();
        }
    }

    @Test
    @DisplayName("Instance.get() retourne une instance contextuelle")
    void shouldGetInstance() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .addBeanClass(Consumer.class)
                .build()) {
            var consumer = container.select(Consumer.class);
            assertEquals("hello", consumer.greetViaInstance());
        }
    }

    @Test
    @DisplayName("Instance.isResolvable() retourne true pour un bean present")
    void shouldBeResolvable() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .addBeanClass(Consumer.class)
                .build()) {
            var consumer = container.select(Consumer.class);
            assertTrue(consumer.isResolvable());
        }
    }

    @Test
    @DisplayName("Instance.isUnsatisfied() retourne true pour un type absent")
    void shouldBeUnsatisfied() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .build()) {
            var instance = new InstanceImpl<>(container, String.class);
            assertTrue(instance.isUnsatisfied());
        }
    }

    @Test
    @DisplayName("Instance.select(subtype) retourne une instance du sous-type")
    void shouldSelectSubtype() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .build()) {
            Instance<Object> instance = new InstanceImpl<>(container, Object.class);
            var greeterInstance = instance.select(Greeter.class);
            assertTrue(greeterInstance.isResolvable());
            assertEquals("hello", greeterInstance.get().greet());
        }
    }

    @Test
    @DisplayName("Instance itere sur toutes les instances")
    void shouldIterateOverInstances() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .build()) {
            var instance = new InstanceImpl<>(container, Greeter.class);
            int count = 0;
            for (var g : instance) {
                assertNotNull(g);
                count++;
            }
            assertEquals(1, count);
        }
    }

    @Test
    @DisplayName("Instance.getHandle() retourne un handle fonctionnel")
    void shouldGetHandle() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .build()) {
            var instance = new InstanceImpl<>(container, Greeter.class);
            var handle = instance.getHandle();
            assertNotNull(handle.get());
            assertNotNull(handle.getBean());
        }
    }

    @Test
    @DisplayName("BeanManager.createInstance() retourne un Instance fonctionnel")
    void shouldCreateInstanceFromBeanManager() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .build()) {
            var bm = container.getBeanManager();
            var instance = bm.createInstance();
            assertNotNull(instance);
            var greeterInstance = instance.select(Greeter.class);
            assertTrue(greeterInstance.isResolvable());
        }
    }
}
