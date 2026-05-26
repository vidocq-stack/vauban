package io.vidocq.vauban.tck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TCK Infrastructure - infrastructure verification")
class TckInfrastructureTest {

    @Test
    @DisplayName("VaubanBeans SPI is functional")
    void beansSpiShouldWork() {
        var beans = new VaubanBeans();
        assertFalse(beans.isProxy(new Object()));
    }

    @Test
    @DisplayName("VaubanContexts SPI is functional")
    void contextsSpiShouldWork() {
        var contexts = new VaubanContexts();
        assertNotNull(contexts.getDependentContext());
    }

    @Test
    @DisplayName("VaubanContextuals SPI is functional")
    void contextualsSpiShouldWork() {
        var contextuals = new VaubanContextuals();
        var inspectable = contextuals.create("test", new io.vidocq.vauban.core.context.DependentContext());
        assertNotNull(inspectable);
        assertNull(inspectable.getCreationalContextPassedToCreate());
    }

    @Test
    @DisplayName("VaubanCreationalContexts SPI is functional")
    void creationalContextsSpiShouldWork() {
        var ccs = new VaubanCreationalContexts();
        var inspectable = ccs.create(null);
        assertNotNull(inspectable);
        assertFalse(inspectable.isPushCalled());
        inspectable.push(new Object());
        assertTrue(inspectable.isPushCalled());
    }
}
