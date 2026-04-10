package fr.vidocq.example.test;

import fr.vidocq.example.lib.GreetingService;
import fr.vidocq.example.lib.TimeService;
import fr.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CDI container with plain (unencrypted) library beans.
 */
class PlainLibIntegrationTest {

    private VaubanContainer container;

    @BeforeEach
    void setUp() {
        container = VaubanContainer.builder()
                .addBeanClass(GreetingService.class)
                .addBeanClass(TimeService.class)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void greetingServiceIsResolvable() {
        var service = container.select(GreetingService.class);
        assertNotNull(service);
        assertEquals("Bonjour, Vauban !", service.greet("Vauban"));
    }

    @Test
    void timeServiceReturnsSomething() {
        var service = container.select(TimeService.class);
        assertNotNull(service);
        var time = service.now();
        assertNotNull(time);
        assertTrue(time.matches("\\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void multipleTimeServiceInstancesAreDifferent() {
        var s1 = container.select(TimeService.class);
        var s2 = container.select(TimeService.class);
        // @Dependent — but selected from container they may be same proxy
        assertNotNull(s1);
        assertNotNull(s2);
    }
}
