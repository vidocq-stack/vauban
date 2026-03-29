package fr.vidocq.vauban.tck;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;

/**
 * Minimal Arquillian container adapter for Vauban.
 * Handles ShrinkWrap deployment by extracting classes and bootstrapping Vauban.
 */
public class VaubanDeployableContainer implements DeployableContainer<VaubanContainerConfig> {

    @Override
    public Class<VaubanContainerConfig> getConfigurationClass() {
        return VaubanContainerConfig.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Local");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        // TODO: Extract classes from ShrinkWrap archive, bootstrap Vauban
        // This is the most complex part - will be implemented incrementally
        return new ProtocolMetaData();
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        // Shutdown container
    }
}
