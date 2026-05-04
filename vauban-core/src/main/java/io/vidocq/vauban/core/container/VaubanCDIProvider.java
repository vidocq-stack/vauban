package io.vidocq.vauban.core.container;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;

/**
 * ServiceLoader-discovered CDI provider that returns the Vauban CDI instance.
 */
public class VaubanCDIProvider implements CDIProvider {

    @Override
    public CDI<Object> getCDI() {
        // Return null when no container is running so that CDI.current() throws
        // IllegalStateException, allowing SeContainerInitializer.newInstance().initialize()
        // to be called by the application (CDI SE bootstrap pattern).
        return VaubanContainer.current() != null ? VaubanCDI.INSTANCE : null;
    }

    @Override
    public int getPriority() {
        return DEFAULT_CDI_PROVIDER_PRIORITY + 100;
    }
}
