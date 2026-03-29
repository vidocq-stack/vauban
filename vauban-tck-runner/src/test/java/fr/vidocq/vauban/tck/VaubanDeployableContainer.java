package fr.vidocq.vauban.tck;

import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import fr.vidocq.vauban.core.container.VaubanContainer;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Arquillian container adapter for Vauban.
 * Extracts classes from ShrinkWrap archives, scans them, and bootstraps a VaubanContainer.
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
        try {
            var classBytecodeMap = new LinkedHashMap<String, byte[]>();
            var classNames = new ArrayList<String>();

            // Extract all .class files from the ShrinkWrap archive
            for (Map.Entry<ArchivePath, Node> entry : archive.getContent().entrySet()) {
                var path = entry.getKey().get();
                if (path.endsWith(".class") && !path.contains("module-info")) {
                    var node = entry.getValue();
                    var asset = node.getAsset();
                    if (asset != null) {
                        try (InputStream is = asset.openStream()) {
                            var bytes = is.readAllBytes();
                            try {
                                var classInfo = ClassFileScanner.scan(bytes);
                                var className = classInfo.name().value();
                                classBytecodeMap.put(className, bytes);
                                classNames.add(className);
                            } catch (Exception e) {
                                // Skip malformed class files
                            }
                        }
                    }
                }
            }

            // Build a ClassLoader that can serve both loadClass and getResourceAsStream
            // for the extracted bytecode
            var archiveClassLoader = new ByteArrayClassLoader(
                    Thread.currentThread().getContextClassLoader(), classBytecodeMap);

            // Use VaubanContainer.Builder with the loaded classes
            var builder = VaubanContainer.builder();
            for (var className : classNames) {
                try {
                    var clazz = archiveClassLoader.loadClass(className);
                    builder.addBeanClass(clazz);
                } catch (ClassNotFoundException e) {
                    // Skip classes that can't be loaded
                }
            }

            var container = builder.build();
            // Activate request context for TCK tests (most tests expect it active)
            container.requestContext().activate();
            ContainerHolder.set(container);

        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy archive: " + archive.getName(), e);
        }

        return new ProtocolMetaData();
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        try {
            var container = ContainerHolder.get();
            if (container != null) {
                container.close();
            }
        } finally {
            ContainerHolder.clear();
        }
    }

    /**
     * ClassLoader that loads classes from bytecode byte arrays
     * and serves getResourceAsStream for .class resources.
     */
    static class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        ByteArrayClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var bytes = classes.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            // Serve .class resources from our bytecode map so that
            // VaubanContainer.Builder can scan them via clazz.getClassLoader().getResourceAsStream()
            if (name.endsWith(".class")) {
                var className = name.replace('/', '.').replace(".class", "");
                var bytes = classes.get(className);
                if (bytes != null) {
                    return new ByteArrayInputStream(bytes);
                }
            }
            return super.getResourceAsStream(name);
        }
    }
}
