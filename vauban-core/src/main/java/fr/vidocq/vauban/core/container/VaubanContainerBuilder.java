package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.BeanId;
import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.spi.CreationalContext;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VaubanContainerBuilder {


    private final List<Class<?>> beanClasses = new ArrayList<>();
    private final Set<String> bceProcessedSources = new java.util.HashSet<>();
    private final Map<DotName, BeanFactory<?>> factories = new LinkedHashMap<>();
    private java.util.function.BiFunction<String, byte[], Class<?>> classDefiner;
    private ClassLoader classLoader;
    private java.lang.invoke.MethodHandles.Lookup lookup;
    private boolean isBeanArchive = true;
    private VaubanLookup builderLookup;
    private final List<fr.vidocq.vauban.classloader.spi.ByteSourcePlugin> byteSourcePlugins = new ArrayList<>();
    private fr.vidocq.vauban.classloader.spi.PluginContext pluginContext;

    private VaubanLookup getBuilderLookup() {
        if (builderLookup == null) {
            builderLookup = new VaubanLookup(lookup != null ? lookup : java.lang.invoke.MethodHandles.lookup());
        }
        return builderLookup;
    }

    public VaubanContainerBuilder beanArchive(boolean isBeanArchive) {
        this.isBeanArchive = isBeanArchive;
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
                    fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.BCE_PROCESSED_MARKER);
            while (markerUrls.hasMoreElements()) {
                bceProcessedSources.add(extractSourceRoot(markerUrls.nextElement().toString(),
                        fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.BCE_PROCESSED_MARKER));
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

        return this;
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
                        System.err.println("[WARN] Vauban: cannot decrypt encrypted JAR " + jarPath.getFileName()
                                + " — " + e.getMessage());
                        System.err.println("[WARN] Vauban: set VAUBAN_SJAR_KEY environment variable "
                                + "or call builder.pluginContext() with the decryption key.");
                        System.err.println("[WARN] Vauban: beans from this JAR will NOT be available.");
                    } catch (IllegalStateException e) {
                        System.err.println("[WARN] Vauban: encrypted JAR " + jarPath.getFileName()
                                + " found but no ByteSourcePlugin available.");
                        System.err.println("[WARN] Vauban: add vauban-sjar to the classpath/module path.");
                    } catch (Exception e) {
                        System.err.println("[WARN] Vauban: failed to load encrypted JAR "
                                + jarPath.getFileName() + " — " + e.getMessage());
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
    public VaubanContainerBuilder addByteSourcePlugin(fr.vidocq.vauban.classloader.spi.ByteSourcePlugin plugin) {
        byteSourcePlugins.add(plugin);
        return this;
    }

    /**
     * Set the plugin context providing keys and configuration for byte source plugins.
     */
    public VaubanContainerBuilder pluginContext(fr.vidocq.vauban.classloader.spi.PluginContext context) {
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
            java.util.ServiceLoader.load(fr.vidocq.vauban.classloader.spi.ByteSourcePlugin.class)
                    .forEach(byteSourcePlugins::add);
            byteSourcePlugins.sort(java.util.Comparator.comparingInt(
                    fr.vidocq.vauban.classloader.spi.ByteSourcePlugin::priority));
        }
    }

    private fr.vidocq.vauban.classloader.spi.PluginContext getPluginContext() {
        if (pluginContext != null) return pluginContext;
        // Try to create a default key provider (resolves from env/system properties)
        try {
            var providerClass = Class.forName("fr.vidocq.vauban.sjar.SjarKeyProvider");
            return (fr.vidocq.vauban.classloader.spi.PluginContext) providerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return fr.vidocq.vauban.classloader.spi.PluginContext.empty();
        }
    }

    private static ClassLoader createPluginClassLoader(
            fr.vidocq.vauban.classloader.spi.ArchiveReader reader, ClassLoader parent) throws IOException {
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
                        indexBuilder.add(fr.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
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
                factories.put(DotName.of(clazz.getName()), () -> {
                    try {
                        return lkp.newInstance(beanClass2);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new jakarta.enterprise.inject.CreationException(e);
                    }
                });
            }
        }

        // Set TCCL to a ClassLoader that can resolve classes from ALL sources
        // (plain beans + encrypted SJAR beans may use different ClassLoaders)
        var previousCl = Thread.currentThread().getContextClassLoader();
        ClassLoader discoveryClassLoader = buildCompositeClassLoader();
        Thread.currentThread().setContextClassLoader(discoveryClassLoader);

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

        // If ALL sources are pre-processed AND we have a replay list, skip full BCE
        // lifecycle (cleaner: BCEs from pre-processed JARs are already digested).
        boolean allSourcesProcessed = unprocessedArchiveClasses.isEmpty() && !bceProcessedSources.isEmpty();
        if (allSourcesProcessed) {
            bceClasses = List.of();
        }

        fr.vidocq.vauban.core.extensions.BceProcessor.DiscoveryResult discoveryResult = null;
        if (!bceClasses.isEmpty()) {
            var tempIndex = indexBuilder.build();
            var tempLookup = new fr.vidocq.vauban.core.langmodel.IndexLookup(tempIndex);
            discoveryResult = fr.vidocq.vauban.core.extensions.BceProcessor.processDiscovery(bceClasses, tempLookup);

            // Add scanned classes to the index
            for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
                try {
                    var cls = Class.forName(className, false, discoveryClassLoader);
                    String resource = className.replace('.', '/') + ".class";
                    try (var is = discoveryClassLoader.getResourceAsStream(resource)) {
                        if (is != null) {
                            indexBuilder.add(fr.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
                        }
                    }
                    if (!factories.containsKey(DotName.of(className))) {
                        var lkp = getBuilderLookup();
                        factories.put(DotName.of(className), () -> {
                            try {
                                return lkp.newInstance(cls);
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new jakarta.enterprise.inject.CreationException(e);
                            }
                        });
                    }
                } catch (Exception e) {
                    // Class not found — skip
                }
            }
        }

        var index = indexBuilder.build();

        // Validate class-level CDI rules (before bean discovery)
        try {
            var classErrors = fr.vidocq.vauban.core.bean.validation.ClassValidator.validate(index);
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
            var combinedEnhMods = new java.util.HashMap<DotName, List<fr.vidocq.vauban.core.extensions.VaubanClassConfig>>();

            // BCEs available for the full Enhancement scan = explicit beanClasses BCEs
            // + BCEs declared in the runtime-list (they live in pre-processed JARs).
            var fullBceClasses = new java.util.LinkedHashSet<>(bceClasses);
            for (var pair : runtimeReplayPairs) {
                fullBceClasses.add(pair.getKey());
            }

            if (!unprocessedArchiveClasses.isEmpty() && !fullBceClasses.isEmpty()) {
                var enhMods = fr.vidocq.vauban.core.extensions.BceProcessor.processEnhancementOnly(
                        List.copyOf(fullBceClasses), unprocessedArchiveClasses, index,
                        beanClasses.isEmpty() ? discoveryClassLoader : beanClasses.getFirst().getClassLoader());
                enhMods.forEach((k, v) -> combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
            }

            if (!runtimeReplayPairs.isEmpty()) {
                var replayMods = fr.vidocq.vauban.core.extensions.BceProcessor.replayEnhancementForTargets(
                        runtimeReplayPairs, index);
                replayMods.forEach((k, v) -> combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
            }

            // Rebuild index with synthetic annotations added by Enhancement (full or replay)
            if (!combinedEnhMods.isEmpty()) {
                var enrichedBuilder = new IndexBuilder();
                for (var classInfo : index.getKnownClasses()) {
                    var mods = combinedEnhMods.get(classInfo.name());
                    if (mods != null) {
                        var newAnnotations = new java.util.ArrayList<>(classInfo.annotations());
                        for (var config : mods) {
                            for (var ann : config.getAddedAnnotations()) {
                                newAnnotations.add(new fr.vidocq.vauban.indexer.model.AnnotationInfo(
                                        DotName.of(ann.getName()), java.util.Map.of()));
                            }
                        }
                        enrichedBuilder.add(new fr.vidocq.vauban.indexer.model.ClassInfo(
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
                fr.vidocq.vauban.core.bean.resolution.QualifierMatcher.setCustomNonbindingMembers(
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
            var descriptors = new ArrayList<>(discovery.discoverBeans());
            var observers = new ArrayList<>(discovery.discoverObservers());
            var interceptors = discovery.discoverInterceptors();
            var disposers = discovery.discoverDisposerMethods();
            var syntheticDisposers = new LinkedHashMap<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>>();

            // --- Build Compatible Extensions (BCE) — remaining phases ---
            if (!bceClasses.isEmpty()) {
                var bceResult = fr.vidocq.vauban.core.extensions.BceProcessor.process(
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
                var modified = fr.vidocq.vauban.core.extensions.BceProcessor.applyEnhancements(
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

                interceptors = new ArrayList<>(fr.vidocq.vauban.core.extensions.BceProcessor.applyInterceptorEnhancements(
                        interceptors, combinedEnhMods));

                var modifiedObservers = fr.vidocq.vauban.core.extensions.BceProcessor.applyObserverEnhancements(
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
            var validator = new fr.vidocq.vauban.core.bean.validation.DeploymentValidator(
                    descriptors, tempResolver);
            var errors = validator.validate();
            if (!errors.isEmpty()) {
                var definitionErrors = errors.stream()
                        .filter(e -> e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEFINITION_ERROR)
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
                        .filter(e -> e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEPLOYMENT_ERROR
                                || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY
                                || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.AMBIGUOUS_DEPENDENCY
                                || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.CIRCULAR_DEPENDENCY)
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
            var container = new VaubanContainer(index, descriptors, observers, interceptors, disposers, factories, syntheticDisposers, beanClassLoader, classDefiner, vaubanLookup);

            // Register custom contexts from Build Compatible Extensions
            if (discoveryResult != null) {
                for (var reg : discoveryResult.metaAnnotations().getCustomContexts()) {
                    try {
                        var ctx = (jakarta.enterprise.context.spi.Context) vaubanLookup.newInstance(reg.contextClass());
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
                                               List<fr.vidocq.vauban.core.bean.model.ObserverDescriptor> observers) {
        try {
            var urls = cl.getResources(fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.METADATA_PATH);
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
                                       List<fr.vidocq.vauban.core.bean.model.ObserverDescriptor> observers) {
        try (var is = url.openStream()) {
            var props = new java.util.Properties();
            props.load(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));

            for (var synDesc : fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.readBeans(props)) {
                registerAptBean(synDesc, cl, descriptors, factories, syntheticDisposers);
            }
            for (var synDesc : fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.readObservers(props)) {
                registerAptObserver(synDesc, cl, observers);
            }
        } catch (java.io.IOException _) {
            // unreadable resource — skip
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerAptBean(fr.vidocq.vauban.core.extensions.SyntheticBeanDescriptor synDesc,
                                  ClassLoader cl,
                                  List<BeanDescriptor> descriptors,
                                  Map<DotName, BeanFactory<?>> factories,
                                  Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers) {
        try {
            var beanClass = Class.forName(synDesc.beanClassName(), false, cl);
            var builder = new fr.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder(beanClass);

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
    private void registerAptObserver(fr.vidocq.vauban.core.extensions.SyntheticObserverDescriptor synDesc,
                                      ClassLoader cl,
                                      List<fr.vidocq.vauban.core.bean.model.ObserverDescriptor> observers) {
        try {
            var eventClass = Class.forName(synDesc.eventTypeName(), false, cl);
            var builder = new fr.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder(eventClass);
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

    private static void applyParam(fr.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder builder,
                                    String key, String encoded) {
        var decoded = fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.decodeParam(encoded);
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
    private static fr.vidocq.vauban.core.bean.model.ScopeInfo extractEnhancedScope(
            java.util.List<fr.vidocq.vauban.core.extensions.VaubanClassConfig> configs) {
        for (var config : configs) {
            for (var ann : config.getAddedAnnotations()) {
                if (ann.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                    return new fr.vidocq.vauban.core.bean.model.ScopeInfo(
                            DotName.of(ann.getName()), true);
                }
                if (ann.isAnnotationPresent(jakarta.inject.Scope.class)) {
                    return new fr.vidocq.vauban.core.bean.model.ScopeInfo(
                            DotName.of(ann.getName()), false);
                }
                // Explicit well-known scope check (for annotations without meta-annotations)
                String name = ann.getName();
                if (name.equals("jakarta.enterprise.context.RequestScoped")
                        || name.equals("jakarta.enterprise.context.ApplicationScoped")
                        || name.equals("jakarta.enterprise.context.SessionScoped")
                        || name.equals("jakarta.enterprise.context.ConversationScoped")) {
                    return new fr.vidocq.vauban.core.bean.model.ScopeInfo(
                            DotName.of(name), true);
                }
                if (name.equals("jakarta.enterprise.context.Dependent")) {
                    return fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT;
                }
                if (name.equals("jakarta.inject.Singleton")) {
                    return fr.vidocq.vauban.core.bean.model.ScopeInfo.SINGLETON;
                }
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ObserverDescriptor buildSyntheticObserver(
            fr.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder<?> synObs) {
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
                var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(params);
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
            fr.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?> synBean,
            List<BeanDescriptor> descriptors,
            Map<DotName, BeanFactory<?>> factories,
            Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers) {
        var beanClass = synBean.getBeanClass();
        var beanName = DotName.of(beanClass.getName());

        // Build bean types from the builder's types
        var beanTypes = new java.util.LinkedHashSet<TypeInfo>();
        for (var type : synBean.getTypes()) {
            if (type instanceof Class<?> cls) {
                beanTypes.add(new TypeInfo.ClassType(DotName.of(cls.getName())));
            }
        }
        if (beanTypes.isEmpty()) {
            beanTypes.add(new TypeInfo.ClassType(beanName));
            beanTypes.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
        }

        // Determine scope
        var scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT;
        if (synBean.getScopeAnnotation() != null) {
            var scopeAnn = synBean.getScopeAnnotation();
            if (scopeAnn == jakarta.enterprise.context.ApplicationScoped.class) {
                scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.APPLICATION;
            } else if (scopeAnn == jakarta.enterprise.context.RequestScoped.class) {
                scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.REQUEST;
            } else if (scopeAnn == jakarta.inject.Singleton.class) {
                scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.SINGLETON;
            }
        }

        // Build qualifiers from builder's qualifier set
        var qualifiers = new java.util.LinkedHashSet<QualifierInstance>();
        boolean hasExplicitQualifier = false;
        for (var q : synBean.getQualifiers()) {
            var qName = DotName.of(q.annotationType().getName());
            if (!qName.equals(QualifierInstance.DEFAULT_NAME) && !qName.equals(QualifierInstance.ANY_NAME)) {
                hasExplicitQualifier = true;
            }
            qualifiers.add(new QualifierInstance(qName, java.util.Map.of()));
        }
        if (!hasExplicitQualifier) {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
        qualifiers.add(QualifierInstance.ANY);

        // Use unique key to avoid collisions when multiple synthetic beans share the same type
        var syntheticKey = DotName.of(beanName.value() + "#synthetic#" + descriptors.size());
        var descriptor = new BeanDescriptor(
                new BeanId(syntheticKey.value()),
                beanName,
                BeanDescriptor.BeanKind.SYNTHETIC,
                beanTypes,
                qualifiers,
                scope,
                synBean.isAlternative(),
                synBean.getPriority(),
                List.of(),
                synBean.getName()
        );
        descriptors.add(descriptor);

        // Create factory using SyntheticBeanCreator
        var creatorClass = synBean.getCreatorClass();
        var creatorParams = synBean.getParams();
        var isDependent = scope.equals(fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT);
        factories.put(syntheticKey, new BeanFactory<Object>() {
            @Override
            public Object create() { return create((CreationalContext<Object>) null); }
            @Override
            public Object create(CreationalContext<Object> ctx) {
                try {
                    @SuppressWarnings("unchecked")
                    var creator = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator<Object>)
                            creatorClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    ScopedValue.CallableOp<Object, Exception> createAction = () -> {
                        var parentCtx = ctx instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> cci ? cci : null;
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
                    var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    var lookup = new InstanceImpl<>(container, Object.class, new Annotation[]{jakarta.enterprise.inject.Default.Literal.INSTANCE}, null, (fr.vidocq.vauban.core.context.CreationalContextImpl<?>) ctx);
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
