package io.vidocq.vauban.core.container;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the container instantiates a bean through a
 * {@link io.vidocq.vauban.core.VaubanComponentProvider} (loaded via ServiceLoader) instead
 * of reflection — i.e. without needing {@code opens … to io.vidocq.vauban.core}.
 *
 * <p>{@link CountingComponentProvider} is registered as a service and records the call when
 * it instantiates {@link ProvidedBean}.
 */
@DisplayName("VaubanComponentProvider - runtime instantiation")
class ComponentProviderRuntimeTest {

    @Test
    @DisplayName("a bean owned by a provider is created by the provider, not by reflection")
    void instantiatesViaProvider() {
        CountingComponentProvider.CREATED.clear();

        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addComponentProvider(new CountingComponentProvider())
                .addBeanClass(ProvidedBean.class)
                .build()) {

            ProvidedBean bean = container.select(ProvidedBean.class);

            assertNotNull(bean, "container should produce a ProvidedBean instance");
            assertEquals("hello", bean.hello());
            assertTrue(CountingComponentProvider.CREATED.contains(ProvidedBean.class.getName()),
                    "the bean must be instantiated through the VaubanComponentProvider "
                            + "(generated, in-module) rather than reflective newInstance");
        }
    }

    @Test
    @DisplayName("an @Inject-constructor bean is created by the provider with container-resolved args")
    void instantiatesConstructorBeanViaProvider() {
        CountingComponentProvider.CREATED.clear();

        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addComponentProvider(new CountingComponentProvider())
                .addBeanClass(ProvidedConstructorBean.class)
                .addBeanClass(ProvidedDependency.class)
                .build()) {

            ProvidedConstructorBean bean = container.select(ProvidedConstructorBean.class);

            assertNotNull(bean, "container should produce a ProvidedConstructorBean instance");
            assertEquals("bean+dep", bean.describe());
            assertTrue(CountingComponentProvider.CREATED.contains(ProvidedConstructorBean.class.getName()),
                    "the @Inject-constructor bean must be instantiated through the provider "
                            + "(in-module new X(args)) rather than reflective newInstance");
        }
    }
}
