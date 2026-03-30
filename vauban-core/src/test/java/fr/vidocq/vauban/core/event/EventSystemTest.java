package fr.vidocq.vauban.core.event;

import fr.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Systeme d'evenements CDI")
class EventSystemTest {

    // Event payload
    public static class OrderCreated {
        public final String orderId;

        public OrderCreated(String orderId) {
            this.orderId = orderId;
        }
    }

    // Observer bean
    @ApplicationScoped
    public static class OrderListener {
        public String lastOrderId;

        public void onOrderCreated(@Observes OrderCreated event) {
            lastOrderId = event.orderId;
        }

        public String getLastOrderId() { return lastOrderId; }
    }

    // Producer bean that fires events
    @ApplicationScoped
    public static class OrderService {
        @Inject
        Event<OrderCreated> orderEvents;

        public void createOrder(String id) {
            orderEvents.fire(new OrderCreated(id));
        }
    }

    @ApplicationScoped
    public static class AuditListener {
        public boolean audited;

        public void onOrder(@Observes OrderCreated event) {
            audited = true;
        }

        public boolean isAudited() { return audited; }
    }

    @Test
    @DisplayName("un evenement est recu par l'observer")
    void shouldDeliverEventToObserver() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(OrderService.class)
                .addBeanClass(OrderListener.class)
                .build()) {

            var service = container.select(OrderService.class);
            var listener = container.select(OrderListener.class);

            service.createOrder("ORD-001");

            assertEquals("ORD-001", listener.getLastOrderId());
        }
    }

    @Test
    @DisplayName("un evenement sans observer ne cause pas d'erreur")
    void shouldNotFailWithoutObserver() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(OrderService.class)
                .build()) {

            var service = container.select(OrderService.class);
            assertDoesNotThrow(() -> service.createOrder("ORD-002"));
        }
    }

    @Test
    @DisplayName("plusieurs observers recoivent le meme evenement")
    void shouldNotifyMultipleObservers() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(OrderService.class)
                .addBeanClass(OrderListener.class)
                .addBeanClass(AuditListener.class)
                .build()) {

            var service = container.select(OrderService.class);
            var orderListener = container.select(OrderListener.class);
            var auditListener = container.select(AuditListener.class);

            service.createOrder("ORD-003");

            assertEquals("ORD-003", orderListener.getLastOrderId());
            assertTrue(auditListener.isAudited());
        }
    }
}
