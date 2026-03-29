package fr.vidocq.vauban.tck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TCK Infrastructure - verification de l'infrastructure")
class TckInfrastructureTest {

    @Test
    @DisplayName("VaubanBeans SPI est fonctionnel")
    void beansSpiShouldWork() {
        var beans = new VaubanBeans();
        assertFalse(beans.isProxy(new Object()));
    }

    @Test
    @DisplayName("VaubanContexts SPI est fonctionnel")
    void contextsSpiShouldWork() {
        var contexts = new VaubanContexts();
        assertNotNull(contexts.getDependentContext());
    }

    @Test
    @DisplayName("VaubanContextuals SPI est fonctionnel")
    void contextualsSpiShouldWork() {
        var contextuals = new VaubanContextuals();
        var inspectable = contextuals.create("test", new fr.vidocq.vauban.core.context.DependentContext());
        assertNotNull(inspectable);
        assertNull(inspectable.getCreationalContextPassedToCreate());
    }

    @Test
    @DisplayName("VaubanCreationalContexts SPI est fonctionnel")
    void creationalContextsSpiShouldWork() {
        var ccs = new VaubanCreationalContexts();
        var inspectable = ccs.create(null);
        assertNotNull(inspectable);
        assertFalse(inspectable.isPushCalled());
        inspectable.push(new Object());
        assertTrue(inspectable.isPushCalled());
    }
}
