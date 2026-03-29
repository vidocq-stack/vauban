package fr.vidocq.vauban.tck;

import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;

public class VaubanArquillianExtension implements LoadableExtension {
    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, VaubanDeployableContainer.class);
    }
}
