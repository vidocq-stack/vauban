package fr.vidocq.vauban.tck;

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
import java.util.jar.JarInputStream;

/**
 * Arquillian container adapter for Vauban.
 * Extracts classes from ShrinkWrap archives (including nested JARs in WEB-INF/lib),
 * scans them, and bootstraps a VaubanContainer.
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

            for (Map.Entry<ArchivePath, Node> entry : archive.getContent().entrySet()) {
                var path = entry.getKey().get();
                var node = entry.getValue();
                var asset = node.getAsset();
                if (asset == null) continue;

                // Extract .class files directly in the archive
                if (path.endsWith(".class") && !path.contains("module-info")) {
                    extractClass(asset, classBytecodeMap, classNames);
                }

                // Extract .class files from nested JARs (WEB-INF/lib/*.jar)
                if (path.endsWith(".jar") && path.contains("lib")) {
                    try (var is = asset.openStream();
                         var jarIs = new JarInputStream(is)) {
                        var jarEntry = jarIs.getNextJarEntry();
                        while (jarEntry != null) {
                            if (jarEntry.getName().endsWith(".class")
                                    && !jarEntry.getName().contains("module-info")) {
                                var bytes = jarIs.readAllBytes();
                                try {
                                    var classInfo = ClassFileScanner.scan(bytes);
                                    var className = classInfo.name().value();
                                    classBytecodeMap.put(className, bytes);
                                    classNames.add(className);
                                } catch (Exception e) {
                                    // Skip malformed class files
                                }
                            }
                            jarEntry = jarIs.getNextJarEntry();
                        }
                    }
                }
            }

            var archiveClassLoader = new ByteArrayClassLoader(
                    Thread.currentThread().getContextClassLoader(), classBytecodeMap);

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
            container.requestContext().activate();
            ContainerHolder.set(container);

        } catch (jakarta.enterprise.inject.spi.DefinitionException e) {
            throw new DeploymentException("CDI DefinitionException: " + e.getMessage(), e);
        } catch (jakarta.enterprise.inject.spi.DeploymentException e) {
            throw new DeploymentException("CDI DeploymentException: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Unwrap some RuntimeExceptions that might be CDI exceptions
            if (e.getCause() instanceof jakarta.enterprise.inject.spi.DefinitionException) {
                throw new DeploymentException("CDI DefinitionException: " + e.getCause().getMessage(), e.getCause());
            }
            if (e.getCause() instanceof jakarta.enterprise.inject.spi.DeploymentException) {
                throw new DeploymentException("CDI DeploymentException: " + e.getCause().getMessage(), e.getCause());
            }
            throw new DeploymentException("Unexpected RuntimeException during deployment: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy archive: " + archive.getName(), e);
        }

        return new ProtocolMetaData();
    }

    private void extractClass(org.jboss.shrinkwrap.api.asset.Asset asset,
            Map<String, byte[]> classBytecodeMap, ArrayList<String> classNames) {
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
        } catch (Exception e) {
            // Skip unreadable assets
        }
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
