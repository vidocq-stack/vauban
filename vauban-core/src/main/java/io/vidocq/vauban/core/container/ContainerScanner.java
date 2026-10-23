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
import java.util.List;

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

    private static final String CLASS_SUFFIX = ".class";
    private static final String FILE_PREFIX = "file:";
    private static final String JAR_FILE_PREFIX = "jar:file:";

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
            throw new ContainerOperationException("Failed to scan package: " + packageName, e);
        }
    }

    private void scanDirectory(java.nio.file.Path dir, String packageName, ClassLoader cl) throws IOException {
        if (!java.nio.file.Files.isDirectory(dir)) return;
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(CLASS_SUFFIX))
                    .forEach(p -> {
                        var relative = dir.relativize(p).toString();
                        var className = packageName + "." + relative
                                .replace(java.io.File.separatorChar, '.')
                                .replace(CLASS_SUFFIX, "");
                        tryAddBeanClass(className, cl);
                    });
        }
    }

    private void scanJarEntries(java.net.URL jarUrl, String packagePath, ClassLoader cl) throws IOException {
        var connection = (java.net.JarURLConnection) jarUrl.openConnection();
        try (var jarFile = connection.getJarFile()) {
            jarFile.entries().asIterator().forEachRemaining(entry -> {
                var name = entry.getName();
                if (name.startsWith(packagePath) && name.endsWith(CLASS_SUFFIX)) {
                    var className = name.replace('/', '.').replace(CLASS_SUFFIX, "");
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
            // Skipped, but said: a whole archive's beans once vanished this way without a word,
            // their package split into another module of the layer (Vidocq/grimm#15).
            LOG.log(System.Logger.Level.WARNING,
                    "{0} is listed as a bean but cannot be loaded ({1}); its bean is skipped. On the module "
                            + "path, its package may be split with another module, or a module it needs missing.",
                    className, e.toString());
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
        } catch (java.io.IOException _) {
            // No marker can be read: no source counts as processed by a build compatible extension.
        }

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
            throw new java.io.UncheckedIOException("Failed to scan classpath for vauban-beans.list", e);
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

    /**
     * The {@code bean-discovery-mode} of a bean archive (CDI 4.1 §"Bean archives"). An empty
     * {@code beans.xml}, or one without the attribute, is {@link #ANNOTATED} since CDI 4.0.
     */
    enum BeanDiscoveryMode {
        ALL, ANNOTATED, NONE;

        private static final java.util.regex.Pattern COMMENT =
                java.util.regex.Pattern.compile("<!--.*?-->", java.util.regex.Pattern.DOTALL);
        private static final java.util.regex.Pattern ATTRIBUTE =
                java.util.regex.Pattern.compile("bean-discovery-mode\\s*=\\s*([\"'])\\s*([^\"']*?)\\s*\\1");

        static BeanDiscoveryMode of(String beansXml) {
            var matcher = ATTRIBUTE.matcher(COMMENT.matcher(beansXml).replaceAll(""));
            if (!matcher.find()) return ANNOTATED;
            return switch (matcher.group(2)) {
                case "all" -> ALL;
                case "annotated" -> ANNOTATED;
                case "none" -> NONE;
                default -> throw new IllegalArgumentException(
                        "invalid bean-discovery-mode \"" + matcher.group(2) + "\" (expected all, annotated or none)");
            };
        }
    }

    void scanBeanArchivesFromClasspath() {
        scanBeanArchives(false, archive -> { });
    }

    /**
     * Scans the bean archives that no Vauban build step processed: those without a Vauban index
     * ({@code META-INF/vauban-beans.list}), a generated component provider or the processed marker. Their
     * beans are not in {@link #scanClasspath()}'s indexes; a processed archive is left to it, since scanning
     * it too would register its beans twice. {@code onArchive} receives the root of every archive scanned.
     */
    void scanUnprocessedBeanArchives(java.util.function.Consumer<java.net.URL> onArchive) {
        scanBeanArchives(true, onArchive);
    }

    /** The entries whose presence means that a Vauban build step indexed the archive. */
    private static final List<String> PROCESSED_MARKERS = List.of("META-INF/vauban-beans.list",
            "META-INF/vauban-bce-processed", "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider");

    private void scanBeanArchives(boolean unprocessedOnly, java.util.function.Consumer<java.net.URL> onArchive) {
        var cl = effectiveClassLoader();
        try {
            var beansXmlUrls = cl.getResources("META-INF/beans.xml");
            while (beansXmlUrls.hasMoreElements()) {
                var beansXmlUrl = beansXmlUrls.nextElement();
                var mode = discoveryMode(beansXmlUrl);
                if (mode != BeanDiscoveryMode.NONE) {
                    scanBeanArchive(beansXmlUrl, cl, mode == BeanDiscoveryMode.ALL, unprocessedOnly, onArchive);
                }
            }
        } catch (java.io.IOException _) {
            // non-fatal — classpath scan failure
        }
    }

    /** The discovery mode a {@code beans.xml} declares; one that cannot be read fails the deployment. */
    private static BeanDiscoveryMode discoveryMode(java.net.URL beansXmlUrl) {
        try (var in = beansXmlUrl.openStream()) {
            return BeanDiscoveryMode.of(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                    "Cannot read the bean discovery mode of " + beansXmlUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Scans the archive a {@code beans.xml} belongs to, a jar or a directory of classes, unless
     * {@code unprocessedOnly} and a Vauban build step indexed it. An archive that cannot be read is skipped.
     */
    private void scanBeanArchive(java.net.URL beansXmlUrl, ClassLoader cl, boolean forced, boolean unprocessedOnly,
                                 java.util.function.Consumer<java.net.URL> onArchive) {
        var urlStr = beansXmlUrl.toString();
        try {
            if (urlStr.startsWith("jar:")) {
                scanJarBeanArchive(urlStr, cl, forced, unprocessedOnly, onArchive);
            } else if (urlStr.startsWith(FILE_PREFIX)) {
                scanDirectoryBeanArchive(beansXmlUrl, cl, forced, unprocessedOnly, onArchive);
            }
        } catch (Exception _) {
            // skip problematic archives
        }
    }

    /**
     * {@code jar:file:/path/to.jar!/META-INF/beans.xml}. An archive whose URL names no file, such as a
     * {@code jar:nested:} one, is left alone.
     */
    private void scanJarBeanArchive(String urlStr, ClassLoader cl, boolean forced, boolean unprocessedOnly,
                                    java.util.function.Consumer<java.net.URL> onArchive) throws IOException {
        int bangIdx = urlStr.indexOf('!');
        var fileStart = urlStr.indexOf(FILE_PREFIX);
        if (bangIdx <= 0 || fileStart < 0) return;
        var jarFilePath = urlStr.substring(fileStart + FILE_PREFIX.length(), bangIdx);
        var jarRootUrl = java.net.URI.create(JAR_FILE_PREFIX + jarFilePath + "!/").toURL();
        if (unprocessedOnly && processedJar(jarRootUrl)) return;
        scanJarEntries(jarRootUrl, cl, forced);
        onArchive.accept(jarRootUrl);
    }

    /** {@code file:/path/classes/META-INF/beans.xml}: the archive is the directory above {@code META-INF}. */
    private void scanDirectoryBeanArchive(java.net.URL beansXmlUrl, ClassLoader cl, boolean forced,
                                          boolean unprocessedOnly, java.util.function.Consumer<java.net.URL> onArchive)
            throws java.net.URISyntaxException, IOException {
        var beansXmlPath = java.nio.file.Path.of(beansXmlUrl.toURI());
        var classesRoot = beansXmlPath.getParent().getParent();
        if (unprocessedOnly && PROCESSED_MARKERS.stream()
                .anyMatch(marker -> java.nio.file.Files.exists(classesRoot.resolve(marker)))) return;
        scanDirectory(classesRoot, cl, forced);
        onArchive.accept(classesRoot.toUri().toURL());
    }

    private static boolean processedJar(java.net.URL jarRootUrl) throws IOException {
        var connection = (java.net.JarURLConnection) jarRootUrl.openConnection();
        connection.setUseCaches(false);
        try (var jarFile = connection.getJarFile()) {
            return PROCESSED_MARKERS.stream().anyMatch(marker -> jarFile.getEntry(marker) != null);
        }
    }

    private void scanJarEntries(java.net.URL jarRootUrl, ClassLoader cl, boolean forced) throws IOException {
        var connection = (java.net.JarURLConnection) jarRootUrl.openConnection();
        try (var jarFile = connection.getJarFile()) {
            jarFile.entries().asIterator().forEachRemaining(entry -> {
                var name = entry.getName();
                if (!name.endsWith(CLASS_SUFFIX)) return;
                if (name.contains("module-info") || name.contains("package-info")) return;
                var className = name.replace('/', '.').replace(CLASS_SUFFIX, "");
                tryAddArchiveClass(className, cl, forced);
            });
        }
    }

    private void scanDirectory(java.nio.file.Path rootDir, ClassLoader cl, boolean forced) {
        if (!java.nio.file.Files.isDirectory(rootDir)) return;
        try (var stream = java.nio.file.Files.walk(rootDir)) {
            stream.filter(p -> p.toString().endsWith(CLASS_SUFFIX))
                    .forEach(p -> {
                        var relative = rootDir.relativize(p).toString();
                        var className = relative
                                .replace(java.io.File.separatorChar, '.')
                                .replace('/', '.')
                                .replace(CLASS_SUFFIX, "");
                        tryAddArchiveClass(className, cl, forced);
                    });
        } catch (Exception _) {
            // An unreadable directory contributes no class.
        }
    }

    /**
     * Adds a class of a bean archive. In an {@code all} archive ({@code forced}) every concrete class
     * is a bean; in an {@code annotated} one, discovery keeps only those with a bean-defining annotation.
     */
    private void tryAddArchiveClass(String className, ClassLoader cl, boolean forced) {
        try {
            if (className.contains("_ClientProxy") || className.contains("$Intercepted")
                    || className.contains("$$")) return;
            var clazz = Class.forName(className, false, cl);
            if (clazz.isAnnotation() || clazz.isInterface() || clazz.isSynthetic()) return;
            if (clazz.isAnonymousClass() || clazz.isLocalClass()) return;
            if (java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) return;
            host.addBeanClass(clazz);
            if (forced) host.forcedDiscoveryClasses.add(clazz);
        } catch (ClassNotFoundException | NoClassDefFoundError _) {
            // Skip unloadable classes
        }
    }

    /**
     * Scan the classpath for JARs containing {@code META-INF/vauban.header}
     * and automatically load their encrypted classes via the plugin system.
     */
    private void scanEncryptedJarsOnClasspath(ClassLoader cl) {
        try {
            // SJAR v2 marker entry. Must match io.vidocq.vauban.sjar.SjarHeader.HEADER_ENTRY.
            // Referenced as a literal to avoid a vauban-core -> vauban-sjar module dependency.
            var markers = cl.getResources("META-INF/vauban.header");
            while (markers.hasMoreElements()) {
                var markerUrl = markers.nextElement().toString();
                if (markerUrl.startsWith(JAR_FILE_PREFIX)) {
                    var jarPath = java.nio.file.Path.of(
                            markerUrl.substring(JAR_FILE_PREFIX.length(), markerUrl.indexOf('!')));
                    loadEncryptedJar(jarPath);
                }
            }
        } catch (java.io.IOException _) {
            // Classpath scan failure — non-fatal
        }
    }

    /** Decrypts and scans one encrypted JAR; a failure is logged and leaves its beans out. */
    private void loadEncryptedJar(java.nio.file.Path jarPath) {
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
        } catch (IllegalStateException _) {
            LOG.log(System.Logger.Level.WARNING,
                    "Encrypted JAR {0} found but no ByteSourcePlugin available.", jarPath.getFileName());
            LOG.log(System.Logger.Level.WARNING,
                    "Add vauban-sjar to the classpath/module path.");
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to load encrypted JAR " + jarPath.getFileName(), e);
        }
    }

    void scanSjar(java.nio.file.Path sjarPath) {
        loadPluginsIfNeeded();
        var ctx = getPluginContext();
        var cl = effectiveClassLoader();

        var handled = host.byteSourcePlugins.stream().anyMatch(p -> p.handles(sjarPath));
        if (!handled) {
            throw new IllegalStateException("No plugin can handle: " + sjarPath
                    + ". Register a ByteSourcePlugin or add vauban-sjar to the module path.");
        }
        // Universal loader engine: byte-source resolution (sjar decryption) chained with
        // the class transformers (cdi-proxifier weaving) before definition — the
        // decrypt → weave composition is only possible here.
        try {
            var engineLoader = io.vidocq.vauban.classloader.VaubanClassLoader.of(
                    java.util.List.of(sjarPath), cl, ctx, host.byteSourcePlugins);
            for (var className : engineLoader.managedClassNames()) {
                tryAddBeanClass(className, engineLoader);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to scan SJAR: " + sjarPath, e);
        }
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
        } catch (Exception _) {
            // No vauban-sjar key provider: the plugins get an empty context.
            return io.vidocq.vauban.classloader.spi.PluginContext.empty();
        }
    }

}
