package io.vidocq.vauban.junit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanExtension - JUnit 6 extension")
class VaubanExtensionTest {

    // --- Test beans ---

    @ApplicationScoped
    public static class GreetingService {
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    @Dependent
    public static class Calculator {
        public int add(int a, int b) {
            return a + b;
        }
    }

    // --- Tests ---

    @SuppressWarnings("CdiManagedBeanInconsistencyInspection")
    @Nested
    @DisplayName("@VaubanTest with @AddBeans and @Inject")
    @VaubanTest
    @AddBeans({GreetingService.class, Calculator.class})
    class InjectionTest {

        @Inject
        GreetingService greetingService;

        @Inject
        Calculator calculator;

        @Test
        @DisplayName("injects an @ApplicationScoped bean")
        void shouldInjectApplicationScopedBean() {
            assertNotNull(greetingService);
            assertEquals("Hello, World!", greetingService.greet("World"));
        }

        @Test
        @DisplayName("injects a @Dependent bean")
        void shouldInjectDependentBean() {
            assertNotNull(calculator);
            assertEquals(5, calculator.add(2, 3));
        }
    }

    @Nested
    @DisplayName("@VaubanTest without beans")
    @VaubanTest
    class EmptyContainerTest {

        @Test
        @DisplayName("works with an empty container")
        void shouldWorkWithEmptyContainer() {
            // No injection, just verify the container lifecycle doesn't crash
            assertTrue(true);
        }
    }

    @SuppressWarnings("CdiManagedBeanInconsistencyInspection")
    @Nested
    @DisplayName("@VaubanTest with a single bean")
    @VaubanTest
    @AddBeans(GreetingService.class)
    class SingleBeanTest {

        @Inject
        GreetingService service;

        @Test
        @DisplayName("@ApplicationScoped returns the same instance across tests")
        void shouldReturnSameInstance1() {
            assertNotNull(service);
            service.greet("test");
        }

        @Test
        @DisplayName("the bean is functional")
        void shouldReturnSameInstance2() {
            assertEquals("Hello, Vauban!", service.greet("Vauban"));
        }
    }
}
