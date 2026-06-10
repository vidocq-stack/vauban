/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.container;

import java.io.IOException;

/**
 * Bean-class discovery on disk: package/directory/JAR scanning, build-time
 * {@code vauban-beans.list} and BCE ServiceLoader resources, forced discovery of
 * {@code beans.xml} archives, and encrypted SJAR handling through the plugin SPI.
 * Extracted from {@link VaubanContainerBuilder} (which keeps the public fluent
 * API and delegates); every discovered class lands in the host via
 * {@link VaubanContainerBuilder#addBeanClass(Class)}.
 */
final class ContainerScanner {

    private static final System.Logger LOG = System.getLogger(ContainerScanner.class.getName());

    private final VaubanContainerBuilder host;

    ContainerScanner(VaubanContainerBuilder host) {
        this.host = host;
    }

    private ClassLoader effectiveClassLoader() {
        return host.classLoader != null ? host.classLoader
                : Thread.currentThread().getContextClassLoader();
    }

    void scanPackage(String packageName) {
        var cl = effectiveClassLoader();
        var packagePath = packageName.replace('.', '/');
        try {
            var resources = cl.getResources(packagePath);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectory(java.nio.file.Path.of(url.toURI()), packageName, cl);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJarEntries(url, packagePath, cl);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan package: " + packageName, e);
        }
    }

    private void scanDirectory(java.nio.file.Path dir, String packageName, ClassLoader cl) throws Exception {
        if (!java.nio.file.Files.isDirectory(dir)) return;
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        var relative = dir.relativize(p).toString();
                        var className = packageName + "." + relative
                                .replace(java.io.File.separatorChar, '.')
                                .replace(".class", "");
                        tryAddBeanClass(className, cl);
                    });
        }
    }

    private void scanJarEntries(java.net.URL jarUrl, String packagePath, ClassLoader cl) throws Exception {
        var connection = (java.net.JarURLConnection) jarUrl.openConnection();
        try (var jarFile = connection.getJarFile()) {
            jarFile.entries().asIterator().forEachRemaining(entry -> {
                var name = entry.getName();
                if (name.startsWith(packagePath) && name.endsWith(".class")) {
                    var className = name.replace('/', '.').replace(".class", "");
                    tryAddBeanClass(className, cl);
                }
            });
        }
    }

    private void tryAddBeanClass(String className, ClassLoader cl) {
        try {
            // Skip generated proxies and interceptor subclasses
            if (className.contains("_ClientProxy") || className.contains("$Intercepted")
                    || className.contains("$$")) return;
            var clazz = Class.forName(className, false, cl);
            if (clazz.isAnnotation() || clazz.isInterface() || clazz.isSynthetic()) return;
            host.addBeanClass(clazz);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip unloadable classes
        }
    }

    /**
     * Extracts the JAR/directory root from a resource URL.
     * E.g. "jar:file:/path/to/my.jar!/META-INF/marker" → "file:/path/to/my.jar"
     *      "file:/path/to/classes/META-INF/marker" → "file:/path/to/classes/"
     */
    private static String extractSourceRoot(String resourceUrl, String resourcePath) {
        // jar:file:/path/to/my.jar!/META-INF/...
        if (resourceUrl.startsWith("jar:")) {
            int bangIdx = resourceUrl.indexOf('!');
            return bangIdx > 0 ? resourceUrl.substring(4, bangIdx) : resourceUrl;
        }
        // file:/path/to/classes/META-INF/...
        int idx = resourceUrl.indexOf(resourcePath);
        return idx > 0 ? resourceUrl.substring(0, idx) : resourceUrl;
    }

    void scanClasspath() {
        var cl = effectiveClassLoader();

        // 1. Auto-detect encrypted JARs on the classpath and register them
        scanEncryptedJarsOnClasspath(cl);

        // 2. Track which classpath sources have the BCE-processed marker
        try {
            var markerUrls = cl.getResources(
                    io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.BCE_PROCESSED_MARKER);
            while (markerUrls.hasMoreElements()) {
                host.bceProcessedSources.add(extractSourceRoot(markerUrls.nextElement().toString(),
                        io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.BCE_PROCESSED_MARKER));
            }
        } catch (java.io.IOException ignored) {}

        // 3. Read vauban-beans.list files from all JARs/directories
        try {
            var urls = cl.getResources("META-INF/vauban-beans.list");
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    reader.lines()
                            .map(String::strip)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(className -> tryAddBeanClass(className, cl));
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to scan classpath for vauban-beans.list", e);
        }

        // 4. Read META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
        // (CDI 4.1 standard ServiceLoader contract for BCEs). These classes are not beans themselves
        // but Vauban's BCE pipeline needs them registered as such so the discovery phase can find
        // them via ReflectionValidator.isBuildCompatibleExtension().
        scanBuildCompatibleExtensionsServiceLoader(cl);
    }

    /**
     * Reads {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
     * from every classpath source and adds each declared BCE class as a "bean class" so the runtime
     * BCE pipeline ({@link io.vidocq.vauban.core.extensions.BceProcessor}) can pick it up.
     *
     * <p>Without this step Vauban silently ignores BCEs declared via the standard CDI 4.1
     * ServiceLoader contract — they are only discovered when listed in {@code vauban-beans.list},
     * which is non-standard and surprises users porting from Weld/OpenWebBeans.
     */
    private void scanBuildCompatibleExtensionsServiceLoader(ClassLoader cl) {
        try {
            var urls = cl.getResources(
                    "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension");
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    reader.lines()
                            .map(line -> {
                                int hash = line.indexOf('#');
                                return (hash >= 0 ? line.substring(0, hash) : line).strip();
                            })
                            .filter(line -> !line.isEmpty())
                            .forEach(className -> tryAddBeanClass(className, cl));
                }
            }
        } catch (java.io.IOException _) {
            // non-fatal — classpath scan failure
        }
    }

    void scanBeanArchivesFromClasspath() {
        var cl = effectiveClassLoader();
        try {
            var beansXmlUrls = cl.getResources("META-INF/beans.xml");
            while (beansXmlUrls.hasMoreElements()) {
                var beansXmlUrl = beansXmlUrls.nextElement();
                var urlStr = beansXmlUrl.toString();
                try {
                    if (urlStr.startsWith("jar:")) {
                        int bangIdx = urlStr.indexOf('!');
                        if (bangIdx > 0) {
                            // jar:file:/path/to.jar!/META-INF/beans.xml  or  jar:nested:...
                            var fileStart = urlStr.indexOf("file:");
                            var jarFilePath = fileStart >= 0
                                    ? urlStr.substring(fileStart + "file:".length(), bangIdx)
                                    : null;
                            if (jarFilePath != null) {
                                var jarRootUrl = new java.net.URL("jar:file:" + jarFilePath + "!/");
                                scanJarEntriesForced(jarRootUrl, cl);
                            }
                        }
                    } else if (urlStr.startsWith("file:")) {
                        // file:/path/classes/META-INF/beans.xml
                        var beansXmlPath = java.nio.file.Path.of(beansXmlUrl.toURI());
                        var classesRoot = beansXmlPath.getParent().getParent();
                        scanAllDirectoryForced(classesRoot, cl);
                    }
                } catch (Exception e) { /* skip problematic archives */ }
            }
        } catch (java.io.IOException e) {
            // non-fatal — classpath scan failure
        }
    }

    private void scanJarEntriesForced(java.net.URL jarRootUrl, ClassLoader cl) throws Exception {
        var connection = (java.net.JarURLConnection) jarRootUrl.openConnection();
        try (var jarFile = connection.getJarFile()) {
            jarFile.entries().asIterator().forEachRemaining(entry -> {
                var name = entry.getName();
                if (!name.endsWith(".class")) return;
                if (name.contains("module-info") || name.contains("package-info")) return;
                var className = name.replace('/', '.').replace(".class", "");
                tryAddForcedBeanClass(className, cl);
            });
        }
    }

    private void scanAllDirectoryForced(java.nio.file.Path rootDir, ClassLoader cl) {
        if (!java.nio.file.Files.isDirectory(rootDir)) return;
        try (var stream = java.nio.file.Files.walk(rootDir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        var relative = rootDir.relativize(p).toString();
                        var className = relative
                                .replace(java.io.File.separatorChar, '.')
                                .replace('/', '.')
                                .replace(".class", "");
                        tryAddForcedBeanClass(className, cl);
                    });
        } catch (Exception e) { /* skip */ }
    }

    private void tryAddForcedBeanClass(String className, ClassLoader cl) {
        try {
            if (className.contains("_ClientProxy") || className.contains("$Intercepted")
                    || className.contains("$$")) return;
            var clazz = Class.forName(className, false, cl);
            if (clazz.isAnnotation() || clazz.isInterface() || clazz.isSynthetic()) return;
            if (clazz.isAnonymousClass() || clazz.isLocalClass()) return;
            if (java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) return;
            host.addBeanClass(clazz);
            host.forcedDiscoveryClasses.add(clazz);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip unloadable classes
        }
    }

    /**
     * Scan the classpath for JARs containing {@code META-INF/vauban.encrypted}
     * and automatically load their encrypted classes via the plugin system.
     */
    private void scanEncryptedJarsOnClasspath(ClassLoader cl) {
        try {
            var markers = cl.getResources("META-INF/vauban.encrypted");
            while (markers.hasMoreElements()) {
                var markerUrl = markers.nextElement().toString();
                if (markerUrl.startsWith("jar:file:")) {
                    var jarPath = java.nio.file.Path.of(
                            markerUrl.substring("jar:file:".length(), markerUrl.indexOf('!')));
                    try {
                        loadPluginsIfNeeded();
                        scanSjar(jarPath);
                    } catch (SecurityException e) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Cannot decrypt encrypted JAR {0}: {1}", jarPath.getFileName(), e.getMessage());
                        LOG.log(System.Logger.Level.WARNING,
                                "Set VAUBAN_SJAR_KEY environment variable or call builder.pluginContext() with the decryption key.");
                        LOG.log(System.Logger.Level.WARNING,
                                "Beans from this JAR will NOT be available.");
                    } catch (IllegalStateException e) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Encrypted JAR {0} found but no ByteSourcePlugin available.", jarPath.getFileName());
                        LOG.log(System.Logger.Level.WARNING,
                                "Add vauban-sjar to the classpath/module path.");
                    } catch (Exception e) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Failed to load encrypted JAR " + jarPath.getFileName(), e);
                    }
                }
            }
        } catch (java.io.IOException e) {
            // Classpath scan failure — non-fatal
        }
    }

    void scanSjar(java.nio.file.Path sjarPath) {
        loadPluginsIfNeeded();
        var ctx = getPluginContext();
        var cl = effectiveClassLoader();

        for (var plugin : host.byteSourcePlugins) {
            if (plugin.handles(sjarPath)) {
                try (var reader = plugin.open(sjarPath, ctx)) {
                    // Create an SjarClassLoader for these classes
                    var sjarClassLoader = createPluginClassLoader(reader, cl);
                    for (var entry : reader.classEntries()) {
                        var className = entry.replace('/', '.').replace(".class", "");
                        tryAddBeanClass(className, sjarClassLoader);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to scan SJAR: " + sjarPath, e);
                }
                return;
            }
        }
        throw new IllegalStateException("No plugin can handle: " + sjarPath
                + ". Register a ByteSourcePlugin or add vauban-sjar to the module path.");
    }

    private void loadPluginsIfNeeded() {
        if (host.byteSourcePlugins.isEmpty()) {
            java.util.ServiceLoader.load(io.vidocq.vauban.classloader.spi.ByteSourcePlugin.class)
                    .forEach(host.byteSourcePlugins::add);
            host.byteSourcePlugins.sort(java.util.Comparator.comparingInt(
                    io.vidocq.vauban.classloader.spi.ByteSourcePlugin::priority));
        }
    }

    private io.vidocq.vauban.classloader.spi.PluginContext getPluginContext() {
        if (host.pluginContext != null) return host.pluginContext;
        // Try to create a default key provider (resolves from env/system properties)
        try {
            var providerClass = Class.forName("io.vidocq.vauban.sjar.SjarKeyProvider");
            return (io.vidocq.vauban.classloader.spi.PluginContext) providerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return io.vidocq.vauban.classloader.spi.PluginContext.empty();
        }
    }

    private static ClassLoader createPluginClassLoader(
            io.vidocq.vauban.classloader.spi.ArchiveReader reader, ClassLoader parent) throws IOException {
        // Build an in-memory classloader with decrypted bytes
        var classBytes = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        for (var entry : reader.classEntries()) {
            var className = entry.replace('/', '.').replace(".class", "");
            classBytes.put(className, reader.readClass(entry));
        }
        return new ClassLoader(parent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                var bytes = classBytes.get(name);
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }

            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                if (name.endsWith(".class")) {
                    var cn = name.replace('/', '.').replace(".class", "");
                    var bytes = classBytes.get(cn);
                    if (bytes != null) return new java.io.ByteArrayInputStream(bytes);
                }
                return super.getResourceAsStream(name);
            }
        };
    }
}
