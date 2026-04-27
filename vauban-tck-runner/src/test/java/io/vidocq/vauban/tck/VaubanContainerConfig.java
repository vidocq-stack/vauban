package io.vidocq.vauban.tck;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

public class VaubanContainerConfig implements ContainerConfiguration {
    @Override
    public void validate() throws ConfigurationException {
        // No validation needed
    }
}
