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
    void greetingServiceWorks() {
        var service = container.select(GreetingService.class);
        assertEquals("Bonjour, Vauban !", service.greet("Vauban"));
    }

    @Test
    void timeServiceReturnsSomething() {
        var service = container.select(TimeService.class);
        assertNotNull(service.now());
        assertTrue(service.now().matches("\\d{2}:\\d{2}:\\d{2}"));
    }
}
