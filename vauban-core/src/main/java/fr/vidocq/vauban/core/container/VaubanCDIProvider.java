package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;

/**
 * ServiceLoader-discovered CDI provider that returns the Vauban CDI instance.
 */
public class VaubanCDIProvider implements CDIProvider {

    @Override
    public CDI<Object> getCDI() {
        return VaubanCDI.INSTANCE;
    }

    @Override
    public int getPriority() {
        return DEFAULT_CDI_PROVIDER_PRIORITY + 100;
    }
}
