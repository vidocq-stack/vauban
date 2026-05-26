package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Instance<T> - programmatic lookup")
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
    @DisplayName("Instance.get() returns a contextual instance")
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
    @DisplayName("Instance.isResolvable() returns true for a present bean")
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
    @DisplayName("Instance.isUnsatisfied() returns true for an absent type")
    void shouldBeUnsatisfied() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Greeter.class)
                .build()) {
            var instance = new InstanceImpl<>(container, String.class);
            assertTrue(instance.isUnsatisfied());
        }
    }

    @Test
    @DisplayName("Instance.select(subtype) returns an instance of the subtype")
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
    @DisplayName("Instance iterates over all instances")
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
    @DisplayName("Instance.getHandle() returns a functional handle")
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
    @DisplayName("BeanManager.createInstance() returns a functional Instance")
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
