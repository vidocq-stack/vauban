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

import io.vidocq.vauban.core.BeanFactory;
import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.spi.CreationalContext;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VaubanContainerBuilder {

    private static final System.Logger LOG = System.getLogger(VaubanContainerBuilder.class.getName());

    private final List<Class<?>> beanClasses = new ArrayList<>();
    private final Set<String> bceProcessedSources = new java.util.HashSet<>();
    private final Map<DotName, BeanFactory<?>> factories = new LinkedHashMap<>();
    private java.util.function.BiFunction<String, byte[], Class<?>> classDefiner;
    private ClassLoader classLoader;
    private java.lang.invoke.MethodHandles.Lookup lookup;
    private boolean isBeanArchive = true;
    private boolean strictScannedDiscovery = false;
    private final Set<Class<?>> forcedDiscoveryClasses = new java.util.LinkedHashSet<>();
    private VaubanLookup builderLookup;
    private final List<io.vidocq.vauban.classloader.spi.ByteSourcePlugin> byteSourcePlugins = new ArrayList<>();
    private io.vidocq.vauban.classloader.spi.PluginContext pluginContext;
    // Module-supplied component providers (ServiceLoader). Consulted before reflective
    // instantiation so application modules need not open their packages to the container.
    private ComponentProviders componentProviders = new ComponentProviders(List.of());
    private final List<io.vidocq.vauban.api.VaubanComponentProvider> componentProviderList = new ArrayList<>();
    // Classes already reported as falling back to reflective instantiation — traced once each so
    // the residual (jars not frozen by APT/plugin) is visible without spamming per-instance logs.
    private final java.util.Set<String> reflectiveFallbacks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private VaubanLookup getBuilderLookup() {
        if (builderLookup == null) {
            builderLookup = new VaubanLookup(lookup != null ? lookup : java.lang.invoke.MethodHandles.lookup());
        }
        return builderLookup;
    }

    /**
     * Instantiates {@code cls} preferring an APT-generated
     * {@link io.vidocq.vauban.api.VaubanComponentProvider} (no reflection, no {@code opens})
     * and falling back to reflective {@link VaubanLookup}
     * when no provider owns the class (class path, unnamed modules, jars not yet processed).
     */
    private Object instantiate(Class<?> cls, VaubanLookup lkp) {
        var provided = componentProviders.create(cls.getName());
        if (provided != null) return provided;
        traceReflectiveFallback(cls);
        try {
            return lkp.newInstance(cls);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.inject.CreationException(e);
        }
    }

    /**
     * Records (once per class) that {@code cls} is being instantiated reflectively because no
     * generated {@link io.vidocq.vauban.api.VaubanComponentProvider} owns it. This is a valid
     * permanent fallback for the class path, open/automatic modules, and jars not yet processed
     * by the APT or the packaging plugin — but it is the residual that prevents a fully static,
     * AOT-friendly, opens-free deployment, so it is surfaced at DEBUG rather than left silent.
     */
    private void traceReflectiveFallback(Class<?> cls) {
        if (reflectiveFallbacks.add(cls.getName())) {
            LOG.log(System.Logger.Level.DEBUG, () ->
                    "No generated VaubanComponentProvider for " + cls.getName()
                    + " — instantiating reflectively (requires an open/automatic module or "
                    + "`opens … to io.vidocq.vauban.core`). Generate a provider via the APT or the "
                    + "packaging plugin to make it static and remove the opens.");
        }
    }

    /**
     * Registers a {@link io.vidocq.vauban.api.VaubanComponentProvider} programmatically. It is
     * consulted before the {@link java.util.ServiceLoader}-discovered providers and before
     * reflective instantiation. Mainly for tests and advanced embedding; application modules
     * normally declare their generated provider via {@code provides … with} in module-info.
     */
    public VaubanContainerBuilder addComponentProvider(io.vidocq.vauban.api.VaubanComponentProvider provider) {
        componentProviderList.add(provider);
        return this;
    }

    public VaubanContainerBuilder beanArchive(boolean isBeanArchive) {
        this.isBeanArchive = isBeanArchive;
        return this;
    }

    /**
     * Picks the bean-discovery mode for a non-bean archive whose discovery is restricted to a
     * BCE-scanned set ({@code beanArchive(false)} + {@code ScannedClasses.add}).
     * {@code true} = CDI-Lite "none" mode (discover only explicitly-contributed classes — used by the
     * CDI TCK's {@code withoutBeansXml()} BCE archives); {@code false} (default) = "annotated" mode
     * (a bean-defining annotation still triggers discovery — Mansart Data relies on this).
     * See {@link io.vidocq.vauban.core.bean.discovery.BeanDiscovery#setStrictScannedDiscovery(boolean)}.
     */
    public VaubanContainerBuilder strictScannedDiscovery(boolean strict) {
        this.strictScannedDiscovery = strict;
        return this;
    }

    /**
     * Add a bean class. The container will scan it and create a default factory.
     */
    public VaubanContainerBuilder addBeanClass(Class<?> beanClass) {
        // Idempotent by class name (not identity) — same class from different ClassLoaders is deduplicated
        if (beanClasses.stream().noneMatch(c -> c.getName().equals(beanClass.getName()))) {
            beanClasses.add(beanClass);
        }
        return this;
    }

    /**
     * Register a custom factory for a bean class.
     */
    /**
     * Set a custom class definer for dynamically generated classes (e.g., interceptor subclasses).
     * The function takes (className, bytecode) and returns the defined Class.
     */
    public VaubanContainerBuilder classDefiner(java.util.function.BiFunction<String, byte[], Class<?>> definer) {
        this.classDefiner = definer;
        return this;
    }

    public VaubanContainerBuilder classLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    /**
     * Provide a {@link java.lang.invoke.MethodHandles.Lookup} from the user's module.
     * This enables JPMS-compliant access to private bean members without
     * {@code setAccessible(true)}. The lookup should be obtained via
     * {@code MethodHandles.lookup()} in the user's code.
     *
     * <p>If not provided, {@link #scanLocal()} will auto-capture one from the caller.
     * For classpath mode (unnamed modules), a default lookup is used.
     */
    public VaubanContainerBuilder lookup(java.lang.invoke.MethodHandles.Lookup lookup) {
        this.lookup = lookup;
        return this;
    }

    public <T> VaubanContainerBuilder addFactory(Class<T> beanClass, BeanFactory<T> factory) {
        factories.put(DotName.of(beanClass.getName()), factory);
        return this;
    }

    /**
     * Scan the caller's package (and sub-packages) for CDI bean classes and add them.
     * Uses stack walking to determine the calling class's package.
     * <p>Example:
     * <pre>{@code
     * // In com.example.MyApp — scans com.example.**
     * VaubanContainer.builder().scanLocal().build();
     * }</pre>
     */
    public VaubanContainerBuilder scanLocal() {
        var callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(c -> c != VaubanContainer.class && c != VaubanContainerBuilder.class)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Cannot determine caller package")));
        // Auto-capture a Lookup from the caller's module if not already set
        if (this.lookup == null) {
            try {
                this.lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                        callerClass, java.lang.invoke.MethodHandles.lookup());
            } catch (IllegalAccessException e) {
                // Fallback: use default lookup (unnamed modules / classpath)
                this.lookup = java.lang.invoke.MethodHandles.lookup();
            }
        }
        return scanPackage(callerClass.getPackageName());
    }

    /**
     * Scan all classes in the given package (and sub-packages) for CDI bean annotations
     * and add them as bean classes.
     *
     * @param packageName the root package to scan (e.g. "com.example")
     */
    public VaubanContainerBuilder scanPackage(String packageName) {
        var cl = this.classLoader != null ? this.classLoader
                : Thread.currentThread().getContextClassLoader();
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
        return this;
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
            addBeanClass(clazz);
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

    /**
     * Loads {@code META-INF/vauban-bce-runtime.list} from every source on the classpath.
     * Each non-comment line has the format {@code <bceFqn>;<targetFqn>}. Pairs whose
     * BCE class or target class cannot be resolved are skipped silently (partial JARs,
     * stripped distributions, etc.).
     */
    private static List<Map.Entry<Class<?>, Class<?>>> loadRuntimeReplayList(ClassLoader cl) {
        var pairs = new ArrayList<Map.Entry<Class<?>, Class<?>>>();
        try {
            var urls = cl.getResources("META-INF/vauban-bce-runtime.list");
            while (urls.hasMoreElements()) {
                parseRuntimeListResource(urls.nextElement(), cl, pairs);
            }
        } catch (IOException _) {
            // classpath scan failure — non-fatal
        }
        return pairs;
    }

    private static void parseRuntimeListResource(java.net.URL url, ClassLoader cl,
                                                  List<Map.Entry<Class<?>, Class<?>>> pairs) {
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sep = line.indexOf(';');
                if (sep <= 0 || sep == line.length() - 1) continue;
                var pair = resolvePair(line.substring(0, sep).strip(),
                        line.substring(sep + 1).strip(), cl);
                if (pair != null) pairs.add(pair);
            }
        } catch (IOException _) {
            // unreadable resource — skip
        }
    }

    private static Map.Entry<Class<?>, Class<?>> resolvePair(String bceFqn, String targetFqn, ClassLoader cl) {
        try {
            return java.util.Map.entry(
                    Class.forName(bceFqn, false, cl),
                    Class.forName(targetFqn, false, cl));
        } catch (ClassNotFoundException | NoClassDefFoundError _) {
            // partial classpath — skip pair
            return null;
        }
    }

    /**
     * Loads the frozen {@code @Enhancement} patch ({@code META-INF/vauban-enhancements.properties})
     * from every source on the classpath, merged into {@code target -> added annotation DotNames}.
     * This is the build-time <em>result</em> of the @Enhancement phases; applying it lets the
     * container skip the reflective BCE replay — and therefore the {@code opens ... to
     * io.vidocq.vauban.core} that replay required on the module path.
     */
    private static Map<DotName, List<DotName>> loadEnhancementPatch(ClassLoader cl) {
        var merged = new java.util.LinkedHashMap<DotName, List<DotName>>();
        try {
            var urls = cl.getResources(
                    io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH);
            while (urls.hasMoreElements()) {
                try (var is = urls.nextElement().openStream()) {
                    var patch = io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.read(is);
                    patch.forEach((target, anns) -> {
                        var list = merged.computeIfAbsent(DotName.of(target), _ -> new ArrayList<>());
                        for (var ann : anns) {
                            var d = DotName.of(ann);
                            if (!list.contains(d)) list.add(d);
                        }
                    });
                } catch (IOException _) {
                    // unreadable patch resource — skip
                }
            }
        } catch (IOException _) {
            // classpath scan failure — non-fatal
        }
        return merged;
    }

    /**
     * Checks whether a class comes from a source that was already BCE-processed at compile time.
     */
    private boolean isClassFromBceProcessedSource(Class<?> clazz) {
        try {
            var codeSource = clazz.getProtectionDomain().getCodeSource();
            if (codeSource == null) return false;
            var location = codeSource.getLocation();
            if (location == null) return false;
            return bceProcessedSources.contains(location.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Scan the classpath for {@code META-INF/vauban-beans.list} files and add
     * all listed bean classes. These files are generated at build time by the
     * {@code vauban-maven-plugin:generate} goal.
     */
    public VaubanContainerBuilder scanClasspath() {
        var cl = this.classLoader != null ? this.classLoader
                : Thread.currentThread().getContextClassLoader();

        // 1. Auto-detect encrypted JARs on the classpath and register them
        scanEncryptedJarsOnClasspath(cl);

        // 2. Track which classpath sources have the BCE-processed marker
        try {
            var markerUrls = cl.getResources(
                    io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.BCE_PROCESSED_MARKER);
            while (markerUrls.hasMoreElements()) {
                bceProcessedSources.add(extractSourceRoot(markerUrls.nextElement().toString(),
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

        return this;
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
     * Scan all bean archives accessible from the current ClassLoader.
     * An archive is a bean archive if it contains {@code META-INF/beans.xml}.
     * All concrete classes from discovered archives are added and forced through
     * bean discovery (equivalent to {@code bean-discovery-mode=all}).
     * <p>
     * Used by {@link io.vidocq.vauban.core.container.VaubanSeContainerInitializer}
     * when no explicit configuration is provided.
     */
    public VaubanContainerBuilder scanBeanArchivesFromClasspath() {
        var cl = this.classLoader != null ? this.classLoader
                : Thread.currentThread().getContextClassLoader();
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
        return this;
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
            addBeanClass(clazz);
            forcedDiscoveryClasses.add(clazz);
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

    /**
     * Register a byte source plugin for custom archive formats (e.g., encrypted SJARs).
     */
    public VaubanContainerBuilder addByteSourcePlugin(io.vidocq.vauban.classloader.spi.ByteSourcePlugin plugin) {
        byteSourcePlugins.add(plugin);
        return this;
    }

    /**
     * Set the plugin context providing keys and configuration for byte source plugins.
     */
    public VaubanContainerBuilder pluginContext(io.vidocq.vauban.classloader.spi.PluginContext context) {
        this.pluginContext = context;
        return this;
    }

    /**
     * Scan an SJAR (Secure JAR) file for CDI bean classes.
     * Requires a byte source plugin that handles .sjar files and
     * a plugin context with the decryption key.
     *
     * @param sjarPath path to the .sjar file
     */
    public VaubanContainerBuilder scanSjar(java.nio.file.Path sjarPath) {
        loadPluginsIfNeeded();
        var ctx = getPluginContext();
        var cl = this.classLoader != null ? this.classLoader
                : Thread.currentThread().getContextClassLoader();

        for (var plugin : byteSourcePlugins) {
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
                return this;
            }
        }
        throw new IllegalStateException("No plugin can handle: " + sjarPath
                + ". Register a ByteSourcePlugin or add vauban-sjar to the module path.");
    }

    private void loadPluginsIfNeeded() {
        if (byteSourcePlugins.isEmpty()) {
            java.util.ServiceLoader.load(io.vidocq.vauban.classloader.spi.ByteSourcePlugin.class)
                    .forEach(byteSourcePlugins::add);
            byteSourcePlugins.sort(java.util.Comparator.comparingInt(
                    io.vidocq.vauban.classloader.spi.ByteSourcePlugin::priority));
        }
    }

    private io.vidocq.vauban.classloader.spi.PluginContext getPluginContext() {
        if (pluginContext != null) return pluginContext;
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

    private ClassLoader buildCompositeClassLoader() {
        if (beanClasses.isEmpty()) {
            return this.classLoader != null ? this.classLoader
                    : Thread.currentThread().getContextClassLoader();
        }
        // Collect unique ClassLoaders from all bean classes
        var loaders = new java.util.LinkedHashSet<ClassLoader>();
        if (this.classLoader != null) loaders.add(this.classLoader);
        for (var clazz : beanClasses) {
            if (clazz.getClassLoader() != null) loaders.add(clazz.getClassLoader());
        }
        if (loaders.size() <= 1) return loaders.iterator().next();
        // Composite ClassLoader that delegates to all bean ClassLoaders
        var loaderList = java.util.List.copyOf(loaders);
        return new ClassLoader(loaderList.getFirst()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                for (var loader : loaderList) {
                    try { return loader.loadClass(name); } catch (ClassNotFoundException ignored) { }
                }
                throw new ClassNotFoundException(name);
            }
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                for (var loader : loaderList) {
                    var is = loader.getResourceAsStream(name);
                    if (is != null) return is;
                }
                return super.getResourceAsStream(name);
            }
        };
    }

    public VaubanContainer build() {
        var indexBuilder = new IndexBuilder();

        for (var clazz : beanClasses) {
            try {
                String resource = clazz.getName().replace('.', '/') + ".class";
                try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
                    if (is != null) {
                        indexBuilder.add(io.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
                    } else {
                        // Fallback for classes not in resources (e.g. dynamic or some test classes)
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan class: " + clazz.getName(), e);
            }

            // Auto-register factory — will be replaced with constructor-aware
            // version after discovery if @Inject constructor is found
            if (!factories.containsKey(DotName.of(clazz.getName()))) {
                var beanClass2 = clazz;
                var lkp = getBuilderLookup();
                factories.put(DotName.of(clazz.getName()), () -> instantiate(beanClass2, lkp));
            }
        }

        // Set TCCL to a ClassLoader that can resolve classes from ALL sources
        // (plain beans + encrypted SJAR beans may use different ClassLoaders)
        var previousCl = Thread.currentThread().getContextClassLoader();
        ClassLoader discoveryClassLoader = buildCompositeClassLoader();
        Thread.currentThread().setContextClassLoader(discoveryClassLoader);

        // Load module-supplied component providers once; the bean factories (deferred lambdas)
        // read this field at creation time, preferring generated instantiation over reflection.
        componentProviders = ComponentProviders.load(discoveryClassLoader, componentProviderList);

        // --- Identify BCE classes and unprocessed archive classes ---
        var bceClasses = beanClasses.stream()
                .filter(c -> ReflectionValidator.isBuildCompatibleExtension(c))
                .toList();

        // Classes from sources WITHOUT vauban-bce-processed marker need runtime @Enhancement
        var unprocessedArchiveClasses = beanClasses.stream()
                .filter(c -> !ReflectionValidator.isBuildCompatibleExtension(c))
                .filter(c -> !isClassFromBceProcessedSource(c))
                .toList();

        // Load (bceFqn, targetFqn) pairs from META-INF/vauban-bce-runtime.list
        // Pre-processed JARs ship this list so the runtime can replay BCE @Enhancement
        // on a precise set of classes (no re-scan, no full BCE lifecycle).
        var runtimeReplayPairs = loadRuntimeReplayList(discoveryClassLoader);

        // Frozen @Enhancement result (target -> added annotation FQNs). When present for a
        // target, it replaces the reflective replay below: we apply the annotations directly
        // and never re-instantiate the BCE (no `opens` needed on the module path).
        var enhancementPatch = loadEnhancementPatch(discoveryClassLoader);
        var effectiveReplayPairs = runtimeReplayPairs.stream()
                .filter(p -> !enhancementPatch.containsKey(DotName.of(p.getValue().getName())))
                .toList();

        // If ALL sources are pre-processed AND we have a replay list, skip full BCE
        // lifecycle (cleaner: BCEs from pre-processed JARs are already digested).
        boolean allSourcesProcessed = unprocessedArchiveClasses.isEmpty() && !bceProcessedSources.isEmpty();
        if (allSourcesProcessed) {
            bceClasses = List.of();
        }

        io.vidocq.vauban.core.extensions.BceProcessor.DiscoveryResult discoveryResult = null;
        if (!bceClasses.isEmpty()) {
            var tempIndex = indexBuilder.build();
            var tempLookup = new io.vidocq.vauban.core.langmodel.IndexLookup(tempIndex);
            discoveryResult = io.vidocq.vauban.core.extensions.BceProcessor.processDiscovery(bceClasses, tempLookup);

            // Add scanned classes to the index
            for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
                try {
                    var cls = Class.forName(className, false, discoveryClassLoader);
                    String resource = className.replace('.', '/') + ".class";
                    try (var is = discoveryClassLoader.getResourceAsStream(resource)) {
                        if (is != null) {
                            indexBuilder.add(io.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
                        }
                    }
                    if (!factories.containsKey(DotName.of(className))) {
                        var lkp = getBuilderLookup();
                        factories.put(DotName.of(className), () -> instantiate(cls, lkp));
                    }
                } catch (Exception e) {
                    // Class not found — skip
                }
            }
        }

        var index = indexBuilder.build();

        // Validate class-level CDI rules (before bean discovery)
        try {
            var classErrors = io.vidocq.vauban.core.bean.validation.ClassValidator.validate(index);
            if (!classErrors.isEmpty()) {
                var msg = new StringBuilder("CDI definition validation failed:\n");
                for (var error : classErrors) {
                    msg.append("  - ").append(error).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
            }

            // Reflection-based validation (for generic signatures not in bytecode index)
            // Exclude BCE classes from validation (they are not beans)
            var nonBceClasses = beanClasses.stream()
                    .filter(c -> !ReflectionValidator.isBuildCompatibleExtension(c))
                    .toList();
            var reflectionErrors = ReflectionValidator.validateWithReflection(nonBceClasses);
            if (!reflectionErrors.isEmpty()) {
                var msg = new StringBuilder("CDI definition validation failed:\n");
                for (var error : reflectionErrors) {
                    msg.append("  - ").append(error).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
            }

            // --- Collect Enhancement modifications: full BCE for unprocessed JARs +
            //     targeted replay for pre-processed JARs (vauban-bce-runtime.list) ---
            var combinedEnhMods = new java.util.HashMap<DotName, List<io.vidocq.vauban.core.extensions.VaubanClassConfig>>();

            // BCEs available for the full Enhancement scan = explicit beanClasses BCEs
            // + BCEs declared in the runtime-list (they live in pre-processed JARs).
            var fullBceClasses = new java.util.LinkedHashSet<>(bceClasses);
            for (var pair : runtimeReplayPairs) {
                fullBceClasses.add(pair.getKey());
            }

            if (!unprocessedArchiveClasses.isEmpty() && !fullBceClasses.isEmpty()) {
                var enhMods = io.vidocq.vauban.core.extensions.BceProcessor.processEnhancementOnly(
                        List.copyOf(fullBceClasses), unprocessedArchiveClasses, index,
                        beanClasses.isEmpty() ? discoveryClassLoader : beanClasses.getFirst().getClassLoader());
                enhMods.forEach((k, v) -> combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
            }

            // Legacy reflective replay — only for targets NOT covered by the frozen patch.
            if (!effectiveReplayPairs.isEmpty()) {
                var replayMods = io.vidocq.vauban.core.extensions.BceProcessor.replayEnhancementForTargets(
                        effectiveReplayPairs, index);
                replayMods.forEach((k, v) -> combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
            }

            // Rebuild index with annotations added by Enhancement: live modifications
            // (full scan for unprocessed JARs / legacy replay) plus the frozen patch.
            if (!combinedEnhMods.isEmpty() || !enhancementPatch.isEmpty()) {
                var enrichedBuilder = new IndexBuilder();
                for (var classInfo : index.getKnownClasses()) {
                    var mods = combinedEnhMods.get(classInfo.name());
                    var patched = enhancementPatch.get(classInfo.name());
                    if (mods != null || patched != null) {
                        var newAnnotations = new java.util.ArrayList<>(classInfo.annotations());
                        if (mods != null) {
                            for (var config : mods) {
                                for (var ann : config.getAddedAnnotations()) {
                                    newAnnotations.add(new io.vidocq.vauban.indexer.model.AnnotationInfo(
                                            DotName.of(ann.getName()), java.util.Map.of()));
                                }
                            }
                        }
                        if (patched != null) {
                            for (var ann : patched) {
                                newAnnotations.add(new io.vidocq.vauban.indexer.model.AnnotationInfo(
                                        ann, java.util.Map.of()));
                            }
                        }
                        enrichedBuilder.add(new io.vidocq.vauban.indexer.model.ClassInfo(
                                classInfo.name(), classInfo.superName(), classInfo.interfaces(),
                                classInfo.accessFlags(), classInfo.fields(), classInfo.methods(),
                                newAnnotations, classInfo.kind()));
                    } else {
                        enrichedBuilder.add(classInfo);
                    }
                }
                index = enrichedBuilder.build();
            }

            var discovery = new BeanDiscovery(index);
            discovery.setStrictScannedDiscovery(strictScannedDiscovery);

            // Apply @Discovery results to BeanDiscovery
            if (discoveryResult != null) {
                var meta = discoveryResult.metaAnnotations();
                discovery.setCustomQualifiers(
                        meta.getCustomQualifiers().stream()
                                .map(c -> DotName.of(c.getName()))
                                .collect(java.util.stream.Collectors.toSet()));
                discovery.setCustomInterceptorBindings(
                        meta.getCustomInterceptorBindings().stream()
                                .map(c -> DotName.of(c.getName()))
                                .collect(java.util.stream.Collectors.toSet()));
                discovery.setCustomStereotypes(
                        meta.getCustomStereotypes().stream()
                                .map(c -> DotName.of(c.getName()))
                                .collect(java.util.stream.Collectors.toSet()));
                var stereotypeAnns = new java.util.HashMap<DotName, java.util.Set<Class<? extends java.lang.annotation.Annotation>>>();
                for (var entry : meta.getStereotypeAnnotations().entrySet()) {
                    stereotypeAnns.put(DotName.of(entry.getKey().getName()), entry.getValue());
                }
                discovery.setCustomStereotypeAnnotations(stereotypeAnns);
                discovery.setCustomNonbindingMembers(meta.getNonbindingMembersPerQualifier());
                io.vidocq.vauban.core.bean.resolution.QualifierMatcher.setCustomNonbindingMembers(
                        meta.getNonbindingMembersPerQualifier());
                VaubanBeanManager.setCustomQualifierTypes(meta.getCustomQualifiers());
                VaubanBeanManager.setCustomInterceptorBindingTypes(meta.getCustomInterceptorBindings());
                VaubanBeanManager.setCustomStereotypeTypes(meta.getCustomStereotypes());

                // Classes added via ScannedClasses bypass annotation check
                if (!discoveryResult.scannedClasses().getAddedClasses().isEmpty()) {
                    var scannedDotNames = discoveryResult.scannedClasses().getAddedClasses().stream()
                            .map(DotName::of)
                            .collect(java.util.stream.Collectors.toSet());
                    discovery.setForcedBeanClasses(scannedDotNames);
                    // When not a bean archive, also restrict discovery to scanned classes only
                    if (!isBeanArchive) {
                        discovery.setScannedClassesFilter(scannedDotNames);
                    }
                }
            }

            // Force bean classes from bean-discovery-mode=all archives (e.g. from scanBeanArchivesFromClasspath)
            if (!forcedDiscoveryClasses.isEmpty()) {
                var dotNames = forcedDiscoveryClasses.stream()
                        .map(c -> DotName.of(c.getName()))
                        .collect(java.util.stream.Collectors.toSet());
                discovery.addForcedBeanClasses(dotNames);
            }

            var descriptors = new ArrayList<>(discovery.discoverBeans());
            var observers = new ArrayList<>(discovery.discoverObservers());
            var interceptors = discovery.discoverInterceptors();
            var disposers = discovery.discoverDisposerMethods();
            var syntheticDisposers = new LinkedHashMap<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>>();

            // --- Build Compatible Extensions (BCE) — remaining phases ---
            if (!bceClasses.isEmpty()) {
                var bceResult = io.vidocq.vauban.core.extensions.BceProcessor.process(
                        bceClasses, descriptors, observers, interceptors, index,
                        beanClasses.isEmpty() ? Thread.currentThread().getContextClassLoader()
                                : beanClasses.getFirst().getClassLoader(),
                        discoveryResult.bceInstances(),
                        nonBceClasses);

                // BCE definition errors → DefinitionException
                if (!bceResult.definitionErrors().isEmpty()) {
                    var msg = new StringBuilder("CDI definition validation failed:\n");
                    for (var error : bceResult.definitionErrors()) {
                        msg.append("  - ").append(error).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
                }

                // BCE deployment errors → DeploymentException
                if (!bceResult.deploymentErrors().isEmpty()) {
                    var msg = new StringBuilder("CDI deployment validation failed:\n");
                    for (var error : bceResult.deploymentErrors()) {
                        msg.append("  - ").append(error).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                }

                // Register synthetic beans
                for (var synBean : bceResult.syntheticBeans()) {
                    registerSyntheticBean(synBean, descriptors, factories, syntheticDisposers);
                }

                // Register synthetic observers
                for (var synObs : bceResult.syntheticObservers()) {
                    observers.add(buildSyntheticObserver(synObs));
                }

                // Merge BCE enhancement modifications into the combined map (replay + full)
                bceResult.enhancementModifications().forEach((k, v) ->
                        combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
            }

            // Apply ALL enhancement modifications (replay + full BCE) to descriptors
            if (!combinedEnhMods.isEmpty()) {
                var modified = io.vidocq.vauban.core.extensions.BceProcessor.applyEnhancements(
                        descriptors, combinedEnhMods);
                descriptors.clear();
                descriptors.addAll(modified);

                // Create beans for non-bean classes that gained a scope via Enhancement
                // (e.g. @Path classes that receive @RequestScoped from a BCE)
                var existingBeanClasses = descriptors.stream()
                        .map(BeanDescriptor::beanClass)
                        .collect(java.util.stream.Collectors.toSet());
                for (var entry : combinedEnhMods.entrySet()) {
                    if (existingBeanClasses.contains(entry.getKey())) continue;
                    var enhancedScope = extractEnhancedScope(entry.getValue());
                    if (enhancedScope == null) continue;
                    var classInfo = index.getClassByName(entry.getKey()).orElse(null);
                    if (classInfo == null) continue;
                    var newBean = discovery.buildManagedBean(classInfo);
                    newBean = new BeanDescriptor(
                            newBean.id(), newBean.beanClass(), newBean.kind(), newBean.types(),
                            newBean.qualifiers(), enhancedScope, newBean.isAlternative(),
                            newBean.priority(), newBean.injectionPoints(), newBean.name(),
                            newBean.interceptorBindings(), newBean.constructorBindings(),
                            newBean.interceptorBindingAnnotations());
                    descriptors.add(newBean);
                }

                interceptors = new ArrayList<>(io.vidocq.vauban.core.extensions.BceProcessor.applyInterceptorEnhancements(
                        interceptors, combinedEnhMods));

                var modifiedObservers = io.vidocq.vauban.core.extensions.BceProcessor.applyObserverEnhancements(
                        observers, combinedEnhMods);
                observers.clear();
                observers.addAll(modifiedObservers);
            }

            // Load synthetic beans/observers from APT-generated metadata (if BCE was processed at compile time)
            if (!bceProcessedSources.isEmpty()) {
                loadSyntheticMetadataFromApt(discoveryClassLoader, descriptors, factories, syntheticDisposers, observers);
            }

            // Validate observer/disposer method parameters (CDI spec)
            VaubanContainer.validateObserverParameters(observers, descriptors, index);
            DisposerInvoker.validateDisposerParameters(disposers, descriptors, index);
            // Validate disposer method definitions (CDI 4.1 Section 3.5)
            DisposerInvoker.validateDisposerDefinitions(disposers, descriptors);


            // Validate deployment — throw if there are errors
            var assignability = new AssignabilityRules(index);
            var tempResolver = new BeanResolver(descriptors, interceptors, assignability);
            var validator = new io.vidocq.vauban.core.bean.validation.DeploymentValidator(
                    descriptors, tempResolver);
            var errors = validator.validate();
            if (!errors.isEmpty()) {
                var definitionErrors = errors.stream()
                        .filter(e -> e.kind() == io.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEFINITION_ERROR)
                        .toList();

                if (!definitionErrors.isEmpty()) {
                    var msg = new StringBuilder("CDI definition validation failed:\n");
                    for (var error : definitionErrors) {
                        msg.append("  - ").append(error.message()).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
                }

                // Ambiguous or unsatisfied dependencies are DeploymentExceptions in CDI
                var deploymentErrors = errors.stream()
                        .filter(e -> e.kind() == io.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEPLOYMENT_ERROR
                                || e.kind() == io.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY
                                || e.kind() == io.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.AMBIGUOUS_DEPENDENCY
                                || e.kind() == io.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.CIRCULAR_DEPENDENCY)
                        .toList();

                if (!deploymentErrors.isEmpty()) {
                    var msg = new StringBuilder("CDI deployment validation failed:\n");
                    for (var error : deploymentErrors) {
                        msg.append("  - ").append(error.message()).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                }

                // Default fallback
                var msg = new StringBuilder("CDI validation failed:\n");
                for (var error : errors) {
                    msg.append("  - ").append(error.message()).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
            }

            var beanClassLoader = discoveryClassLoader;
            var vaubanLookup = getBuilderLookup();
            var container = new VaubanContainer(index, descriptors, observers, interceptors, disposers, factories, syntheticDisposers, beanClassLoader, classDefiner, vaubanLookup, componentProviders);

            // Register custom contexts from Build Compatible Extensions
            if (discoveryResult != null) {
                for (var reg : discoveryResult.metaAnnotations().getCustomContexts()) {
                    try {
                        var contextClass = reg.contextClass();
                        var provided = componentProviders.create(contextClass.getName());
                        var ctx = (jakarta.enterprise.context.spi.Context)
                                (provided != null ? provided : vaubanLookup.newInstance(contextClass));
                        container.contexts.computeIfAbsent(reg.scopeAnnotation(), k -> new java.util.ArrayList<>()).add(ctx);
                    } catch (Exception e) {
                        // Skip context if instantiation fails
                    }
                }
            }

            return container;
        } finally {
            Thread.currentThread().setContextClassLoader(previousCl);
        }
    }

    /**
     * Load synthetic beans/observers from APT-generated metadata.
     * Called when BCE was already processed at compile time.
     */
    private void loadSyntheticMetadataFromApt(ClassLoader cl,
                                               List<BeanDescriptor> descriptors,
                                               Map<DotName, BeanFactory<?>> factories,
                                               Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
                                               List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers) {
        try {
            var urls = cl.getResources(io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.METADATA_PATH);
            while (urls.hasMoreElements()) {
                loadMetadataResource(urls.nextElement(), cl, descriptors, factories, syntheticDisposers, observers);
            }
        } catch (java.io.IOException _) {
            // No metadata file or read error — skip
        }
    }

    private void loadMetadataResource(java.net.URL url, ClassLoader cl,
                                       List<BeanDescriptor> descriptors,
                                       Map<DotName, BeanFactory<?>> factories,
                                       Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
                                       List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers) {
        try (var is = url.openStream()) {
            var props = new java.util.Properties();
            props.load(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));

            for (var synDesc : io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.readBeans(props)) {
                registerAptBean(synDesc, cl, descriptors, factories, syntheticDisposers);
            }
            for (var synDesc : io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.readObservers(props)) {
                registerAptObserver(synDesc, cl, observers);
            }
        } catch (java.io.IOException _) {
            // unreadable resource — skip
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerAptBean(io.vidocq.vauban.core.extensions.SyntheticBeanDescriptor synDesc,
                                  ClassLoader cl,
                                  List<BeanDescriptor> descriptors,
                                  Map<DotName, BeanFactory<?>> factories,
                                  Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers) {
        try {
            var beanClass = Class.forName(synDesc.beanClassName(), false, cl);
            var builder = new io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder(beanClass);

            if (synDesc.creatorClassName() != null) {
                builder.createWith((Class) Class.forName(synDesc.creatorClassName(), false, cl));
            }
            if (synDesc.disposerClassName() != null) {
                builder.disposeWith((Class) Class.forName(synDesc.disposerClassName(), false, cl));
            }
            if (synDesc.scopeAnnotation() != null) {
                builder.scope((Class) Class.forName(synDesc.scopeAnnotation(), false, cl));
            }
            for (var typeName : synDesc.types()) {
                resolveOptionalClass(typeName, cl, builder::type);
            }
            for (var qualName : synDesc.qualifiers()) {
                resolveOptionalClass(qualName, cl, c -> builder.qualifier((Class) c));
            }
            if (synDesc.name() != null) builder.name(synDesc.name());
            builder.alternative(synDesc.alternative());
            builder.priority(synDesc.priority());

            for (var paramEntry : synDesc.params().entrySet()) {
                applyParam(builder, paramEntry.getKey(), paramEntry.getValue());
            }

            registerSyntheticBean(builder, descriptors, factories, syntheticDisposers);
        } catch (ClassNotFoundException _) {
            // Synthetic bean class not found — skip
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerAptObserver(io.vidocq.vauban.core.extensions.SyntheticObserverDescriptor synDesc,
                                      ClassLoader cl,
                                      List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers) {
        try {
            var eventClass = Class.forName(synDesc.eventTypeName(), false, cl);
            var builder = new io.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder(eventClass);
            if (synDesc.observerClassName() != null) {
                builder.observeWith(Class.forName(synDesc.observerClassName(), false, cl));
            }
            for (var qualName : synDesc.qualifiers()) {
                resolveOptionalClass(qualName, cl, c -> builder.qualifier((Class) c));
            }
            builder.priority(synDesc.priority());
            builder.async(synDesc.async());

            observers.add(buildSyntheticObserver(builder));
        } catch (ClassNotFoundException _) {
            // Synthetic observer class not found — skip
        }
    }

    private static void resolveOptionalClass(String fqn, ClassLoader cl, java.util.function.Consumer<Class<?>> sink) {
        try {
            sink.accept(Class.forName(fqn, false, cl));
        } catch (ClassNotFoundException _) {
            // silently skip: classpath partial, JAR may not include this class
        }
    }

    private static void applyParam(io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder builder,
                                    String key, String encoded) {
        var decoded = io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.decodeParam(encoded);
        switch (decoded) {
            case String s -> builder.withParam(key, s);
            case Boolean b -> builder.withParam(key, b);
            case Integer i -> builder.withParam(key, i);
            case Long l -> builder.withParam(key, l);
            case Double d -> builder.withParam(key, d);
            default -> { /* unsupported decoded type — ignored */ }
        }
    }

    /**
     * Extract the scope added by Enhancement, if any. Returns null if no scope was added.
     */
    private static io.vidocq.vauban.core.bean.model.ScopeInfo extractEnhancedScope(
            java.util.List<io.vidocq.vauban.core.extensions.VaubanClassConfig> configs) {
        for (var config : configs) {
            for (var ann : config.getAddedAnnotations()) {
                if (ann.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                    return new io.vidocq.vauban.core.bean.model.ScopeInfo(
                            DotName.of(ann.getName()), true);
                }
                if (ann.isAnnotationPresent(jakarta.inject.Scope.class)) {
                    return new io.vidocq.vauban.core.bean.model.ScopeInfo(
                            DotName.of(ann.getName()), false);
                }
                // Explicit well-known scope check (for annotations without meta-annotations)
                String name = ann.getName();
                if (name.equals("jakarta.enterprise.context.RequestScoped")
                        || name.equals("jakarta.enterprise.context.ApplicationScoped")
                        || name.equals("jakarta.enterprise.context.SessionScoped")
                        || name.equals("jakarta.enterprise.context.ConversationScoped")) {
                    return new io.vidocq.vauban.core.bean.model.ScopeInfo(
                            DotName.of(name), true);
                }
                if (name.equals("jakarta.enterprise.context.Dependent")) {
                    return io.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT;
                }
                if (name.equals("jakarta.inject.Singleton")) {
                    return io.vidocq.vauban.core.bean.model.ScopeInfo.SINGLETON;
                }
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ObserverDescriptor buildSyntheticObserver(
            io.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder<?> synObs) {
        var eventReflectType = synObs.getEventType();
        TypeInfo eventTypeInfo;
        if (eventReflectType instanceof Class<?> cls) {
            eventTypeInfo = new TypeInfo.ClassType(DotName.of(cls.getName()));
        } else if (eventReflectType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() instanceof Class<?> rawCls) {
            var typeArgs = new java.util.ArrayList<TypeInfo>();
            for (var arg : pt.getActualTypeArguments()) {
                if (arg instanceof Class<?> argCls) {
                    typeArgs.add(new TypeInfo.ClassType(DotName.of(argCls.getName())));
                } else {
                    typeArgs.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
                }
            }
            eventTypeInfo = new TypeInfo.ParameterizedType(DotName.of(rawCls.getName()), typeArgs);
        } else {
            eventTypeInfo = new TypeInfo.ClassType(DotName.of("java.lang.Object"));
        }

        var qualifiers = new java.util.ArrayList<QualifierInstance>();
        for (var q : synObs.getQualifiers()) {
            var qName = DotName.of(q.annotationType().getName());
            qualifiers.add(new QualifierInstance(qName, java.util.Map.of()));
        }

        var observerClass = synObs.getObserverClass();
        var params = synObs.getParams();

        java.util.function.BiConsumer<Object, java.lang.annotation.Annotation[]> invoker = (event, eventQualifiers) -> {
            try {
                var observer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver) observerClass.getDeclaredConstructor().newInstance();
                var vaubanParams = new io.vidocq.vauban.core.extensions.VaubanParameters(params);
                var metadata = new jakarta.enterprise.inject.spi.EventMetadata() {
                    @Override public java.util.Set<java.lang.annotation.Annotation> getQualifiers() {
                        var qs = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
                        if (eventQualifiers != null) {
                            for (var q : eventQualifiers) qs.add(q);
                        }
                        qs.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
                        return java.util.Set.copyOf(qs);
                    }
                    @Override public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() { return null; }
                    @Override public java.lang.reflect.Type getType() { return event.getClass(); }
                };
                var eventContext = new jakarta.enterprise.inject.spi.EventContext() {
                    @Override public Object getEvent() { return event; }
                    @Override public jakarta.enterprise.inject.spi.EventMetadata getMetadata() { return metadata; }
                };
                observer.observe(eventContext, vaubanParams);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new jakarta.enterprise.event.ObserverException("Synthetic observer failed", e);
            }
        };

        return new ObserverDescriptor(
                DotName.of(observerClass.getName()),
                "observe",
                eventTypeInfo,
                qualifiers,
                synObs.isAsync(),
                synObs.getPriority(),
                "ALWAYS",
                "IN_PROGRESS",
                invoker
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerSyntheticBean(
            io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?> synBean,
            List<BeanDescriptor> descriptors,
            Map<DotName, BeanFactory<?>> factories,
            Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers) {
        // Descriptor construction is shared with the APT pipeline so the deployment validator
        // sees the same view of synthetic beans at compile time and at runtime.
        var descriptor = io.vidocq.vauban.core.extensions.BceProcessor
                .toBeanDescriptor(synBean, descriptors.size());
        descriptors.add(descriptor);
        var syntheticKey = DotName.of(descriptor.id().value());

        // Create factory using SyntheticBeanCreator
        var creatorClass = synBean.getCreatorClass();
        var creatorParams = synBean.getParams();
        var isDependent = descriptor.scope().equals(io.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT);
        factories.put(syntheticKey, new BeanFactory<Object>() {
            @Override
            public Object create() { return create((CreationalContext<Object>) null); }
            @Override
            public Object create(CreationalContext<Object> ctx) {
                try {
                    @SuppressWarnings("unchecked")
                    var creator = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator<Object>)
                            creatorClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new io.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    ScopedValue.CallableOp<Object, Exception> createAction = () -> {
                        var parentCtx = ctx instanceof io.vidocq.vauban.core.context.CreationalContextImpl<?> cci ? cci : null;
                        var lookup = new InstanceImpl<>(container, Object.class, new Annotation[0], null, parentCtx);
                        return creator.create(lookup, vaubanParams);
                    };
                    if (VaubanContainer.getCurrentInjectionPoint() == null && isDependent) {
                        return VaubanContainer.callWithInjectionPoint(VaubanInjectionPoint.EMPTY, createAction);
                    }
                    return createAction.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new jakarta.enterprise.inject.CreationException(e);
                }
            }
        });

        // Register synthetic disposer if present
        var disposerClass = synBean.getDisposerClass();
        if (disposerClass != null) {
            syntheticDisposers.put(syntheticKey, (instance, ctx) -> {
                try {
                    @SuppressWarnings("unchecked")
                    var disposer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer<Object>)
                            disposerClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new io.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    var lookup = new InstanceImpl<>(container, Object.class, new Annotation[]{jakarta.enterprise.inject.Default.Literal.INSTANCE}, null, (io.vidocq.vauban.core.context.CreationalContextImpl<?>) ctx);
                    disposer.dispose(instance, lookup, vaubanParams);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    // CDI spec: exceptions in disposer methods are suppressed
                }
            });
        }
    }
}
