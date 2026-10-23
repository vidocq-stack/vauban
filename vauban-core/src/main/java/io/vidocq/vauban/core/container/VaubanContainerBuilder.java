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
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.validation.DeploymentValidator;
import io.vidocq.vauban.core.extensions.BceProcessor;
import io.vidocq.vauban.core.extensions.VaubanClassConfig;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;
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

    private static final String DEFINITION_FAILED = "CDI definition validation failed:\n";
    private static final String DEPLOYMENT_FAILED = "CDI deployment validation failed:\n";

    /** The validation errors CDI reports as a {@code DeploymentException}: ambiguous or unsatisfied dependencies. */
    private static final Set<DeploymentValidator.ValidationError.Kind> DEPLOYMENT_ERROR_KINDS = java.util.EnumSet.of(
            DeploymentValidator.ValidationError.Kind.DEPLOYMENT_ERROR,
            DeploymentValidator.ValidationError.Kind.UNPROXYABLE_BEAN,
            DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY,
            DeploymentValidator.ValidationError.Kind.AMBIGUOUS_DEPENDENCY,
            DeploymentValidator.ValidationError.Kind.CIRCULAR_DEPENDENCY);

    private final List<Class<?>> beanClasses = new ArrayList<>();
    final Set<String> bceProcessedSources = new java.util.HashSet<>();
    private final Map<DotName, BeanFactory<?>> factories = new LinkedHashMap<>();
    private java.util.function.BiFunction<String, byte[], Class<?>> classDefiner;
    ClassLoader classLoader;
    private java.lang.invoke.MethodHandles.Lookup lookup;
    private boolean isBeanArchive = true;
    private boolean strictScannedDiscovery = false;
    final Set<Class<?>> forcedDiscoveryClasses = new java.util.LinkedHashSet<>();
    private VaubanLookup builderLookup;
    final List<io.vidocq.vauban.classloader.spi.ByteSourcePlugin> byteSourcePlugins = new ArrayList<>();
    io.vidocq.vauban.classloader.spi.PluginContext pluginContext;
    // Disk/classpath bean-class discovery (extracted from this class — it stays the facade)
    private final ContainerScanner scanner = new ContainerScanner(this);
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
     * Add a class that is explicitly contributed to the deployment as if it belonged to a
     * synthetic bean archive with {@code bean-discovery-mode=all} — the CDI SE contract for
     * {@link jakarta.enterprise.inject.se.SeContainerInitializer#addBeanClasses}. Such a class
     * becomes a managed bean regardless of whether it carries a bean-defining annotation
     * (e.g. a plain dependency class with neither a scope nor an {@code @Inject} constructor,
     * such as the atinject TCK {@code FuelTank}). Build Compatible Extension classes are never
     * forced — they are wiring, not beans.
     */
    public VaubanContainerBuilder addSyntheticArchiveClass(Class<?> beanClass) {
        addBeanClass(beanClass);
        if (!ReflectionValidator.isBuildCompatibleExtension(beanClass)) {
            forcedDiscoveryClasses.add(beanClass);
        }
        return this;
    }

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
     * This enables Java Modules-compliant access to private bean members without
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
            } catch (IllegalAccessException _) {
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
        scanner.scanPackage(packageName);
        return this;
    }

    private static final DotName ACTIVATE_REQUEST_CONTEXT =
            DotName.of("jakarta.enterprise.context.control.ActivateRequestContext");

    /** Whether a class of {@code index}, or one of its methods, carries {@code binding}. */
    private static boolean usesBinding(io.vidocq.vauban.indexer.VaubanIndex index, DotName binding) {
        for (var classInfo : index.getKnownClasses()) {
            if (classInfo.hasAnnotation(binding)) return true;
            for (var method : classInfo.methods()) {
                for (var annotation : method.annotations()) {
                    if (annotation.name().equals(binding)) return true;
                }
            }
        }
        return false;
    }

    /** The managed bean each class of {@code index} would be, for the classes an Enhancement gives a scope. */
    private static java.util.function.Function<DotName, BeanDescriptor> managedBeans(
            io.vidocq.vauban.indexer.VaubanIndex index, BeanDiscovery discovery) {
        return name -> index.getClassByName(name).map(discovery::buildManagedBean).orElse(null);
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
                var pair = parseRuntimeListLine(line.strip(), cl);
                if (pair != null) pairs.add(pair);
            }
        } catch (IOException _) {
            // unreadable resource — skip
        }
    }

    /** The pair a stripped {@code <bceFqn>;<targetFqn>} line names, or {@code null} for a comment or blank line. */
    private static Map.Entry<Class<?>, Class<?>> parseRuntimeListLine(String line, ClassLoader cl) {
        if (line.isEmpty() || line.startsWith("#")) return null;
        int sep = line.indexOf(';');
        if (sep <= 0 || sep == line.length() - 1) return null;
        return resolvePair(line.substring(0, sep).strip(), line.substring(sep + 1).strip(), cl);
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
     * from every source on the classpath, merged into {@code target -> added annotations}, members
     * included.
     * This is the build-time <em>result</em> of the @Enhancement phases; applying it lets the
     * container skip the reflective BCE replay — and therefore the {@code opens ... to
     * io.vidocq.vauban.core} that replay required on the module path.
     */
    private static Map<DotName, List<io.vidocq.vauban.indexer.model.AnnotationInfo>> loadEnhancementPatch(
            ClassLoader cl) {
        var merged = new java.util.LinkedHashMap<DotName, List<io.vidocq.vauban.indexer.model.AnnotationInfo>>();
        try {
            var urls = cl.getResources(
                    io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH);
            while (urls.hasMoreElements()) {
                mergeEnhancementPatch(urls.nextElement(), merged);
            }
        } catch (IOException _) {
            // classpath scan failure — non-fatal
        }
        return merged;
    }

    /** Merges the patch {@code url} holds into {@code merged}, each added annotation once. */
    private static void mergeEnhancementPatch(java.net.URL url, Map<DotName, List<AnnotationInfo>> merged) {
        try (var is = url.openStream()) {
            var patch = io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.read(is);
            patch.forEach((target, anns) -> {
                var list = merged.computeIfAbsent(DotName.of(target), _ -> new ArrayList<>());
                for (var ann : anns) {
                    if (!list.contains(ann)) list.add(ann);
                }
            });
        } catch (IOException _) {
            // unreadable patch resource — skip
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
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * Scan the classpath for {@code META-INF/vauban-beans.list} files and add
     * all listed bean classes. These files are generated at build time by the
     * {@code vauban-maven-plugin:generate} goal.
     */
    public VaubanContainerBuilder scanClasspath() {
        scanner.scanClasspath();
        return this;
    }

    /**
     * Scan all bean archives accessible from the current ClassLoader.
     * An archive is a bean archive if it contains {@code META-INF/beans.xml}, whose
     * {@code bean-discovery-mode} is honoured: {@code all} forces every concrete class through bean
     * discovery, {@code annotated} (also an empty {@code beans.xml}) keeps only classes with a
     * bean-defining annotation, and {@code none} skips the archive.
     * <p>
     * Used by {@link io.vidocq.vauban.core.container.VaubanSeContainerInitializer}
     * when no explicit configuration is provided.
     */
    public VaubanContainerBuilder scanBeanArchivesFromClasspath() {
        scanner.scanBeanArchivesFromClasspath();
        return this;
    }

    /**
     * Scan the bean archives that no Vauban build step processed, as {@link #scanBeanArchivesFromClasspath()}
     * does, and only those: an archive with a Vauban index ({@code META-INF/vauban-beans.list}), a generated
     * {@code VaubanComponentProvider} or the {@code META-INF/vauban-bce-processed} marker is left to
     * {@link #scanClasspath()}, which reads its index, so that no bean is registered twice. For a runtime that
     * reads the build-time indexes and must still find the beans of a dependency that no build step indexed, as
     * an IDE build leaves them (Vidocq/vidocq#83).
     *
     * @param onArchive receives the root of each archive scanned ({@code jar:file:…!/} or {@code file:…/}), to
     *                  report them
     */
    public VaubanContainerBuilder scanUnprocessedBeanArchives(java.util.function.Consumer<java.net.URL> onArchive) {
        scanner.scanUnprocessedBeanArchives(onArchive);
        return this;
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
        scanner.scanSjar(sjarPath);
        return this;
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
        // Keep the caller-installed context loader in the composite: an embedded
        // deployment may contribute no class of its own (parent-first loaders
        // whose classes also exist on the application classpath) yet still
        // carry deployment resources — microprofile-config.properties,
        // META-INF/services — that libraries read through the build-time TCCL.
        var entryTccl = Thread.currentThread().getContextClassLoader();
        if (entryTccl != null) loaders.add(entryTccl);
        if (loaders.size() <= 1) return loaders.iterator().next();
        // Composite ClassLoader that delegates to all bean ClassLoaders — for
        // classes AND resources. Resources matter as much as classes: this
        // loader is installed as TCCL for the whole build, and libraries read
        // configuration through it (MP Config reads
        // META-INF/microprofile-config.properties and META-INF/services
        // ConfigSources via TCCL.getResources during BCE validation). The
        // default getResources only consults the parent and findResources, so
        // both findResource and findResources must aggregate every loader.
        return new CompositeClassLoader(java.util.List.copyOf(layerLoaderFirst(loaders)));
    }

    /**
     * {@code loaders}, with the first VaubanClassLoader (application module layer) moved first: it must become the
     * composite's PARENT, whatever the bean scan order put first. Module-mode ServiceLoader only walks the parent
     * chain's service catalogs, and the layer loader's own chain already reaches the application loader — a
     * composite parented elsewhere would hide the layer's providers (generated _VaubanComponents, Cassini adapters…).
     */
    private static java.util.SequencedSet<ClassLoader> layerLoaderFirst(java.util.LinkedHashSet<ClassLoader> loaders) {
        var layerLoader = loaders.stream()
                .filter(io.vidocq.vauban.classloader.VaubanClassLoader.class::isInstance)
                .findFirst();
        if (layerLoader.isEmpty() || loaders.getFirst().equals(layerLoader.get())) {
            return loaders;
        }
        var reordered = new java.util.LinkedHashSet<ClassLoader>();
        reordered.add(layerLoader.get());
        reordered.addAll(loaders);
        return reordered;
    }

    /** A class loader that finds classes and resources in each of its loaders, in order; parented by the first. */
    private static final class CompositeClassLoader extends ClassLoader {
        private final List<ClassLoader> loaderList;

        CompositeClassLoader(List<ClassLoader> loaderList) {
            super(loaderList.getFirst());
            this.loaderList = loaderList;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            for (var loader : loaderList) {
                try {
                    return loader.loadClass(name);
                } catch (ClassNotFoundException _) {
                    // not this loader's: try the next one
                }
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

        @Override
        protected java.net.URL findResource(String name) {
            for (var loader : loaderList) {
                var url = loader.getResource(name);
                if (url != null) return url;
            }
            return null;
        }

        @Override
        protected java.util.Enumeration<java.net.URL> findResources(String name) throws IOException {
            // Deduplicated by external form: URL.equals may resolve host names.
            var all = new java.util.LinkedHashMap<String, java.net.URL>();
            for (var loader : loaderList) {
                for (var url : java.util.Collections.list(loader.getResources(name))) {
                    all.putIfAbsent(url.toExternalForm(), url);
                }
            }
            // getResources() = parent.getResources() + findResources(): the
            // parent is loaderList.getFirst(), so drop its results here to
            // avoid duplicates in the concatenated enumeration.
            var parent = getParent();
            if (parent != null) {
                java.util.Collections.list(parent.getResources(name))
                        .forEach(url -> all.remove(url.toExternalForm()));
            }
            return java.util.Collections.enumeration(all.values());
        }
    }

    public VaubanContainer build() {
        var indexBuilder = new IndexBuilder();
        indexBeanClasses(indexBuilder);

        // Set TCCL to a ClassLoader that can resolve classes from ALL sources
        // (plain beans + encrypted SJAR beans may use different ClassLoaders)
        var previousCl = Thread.currentThread().getContextClassLoader();
        ClassLoader discoveryClassLoader = buildCompositeClassLoader();
        Thread.currentThread().setContextClassLoader(discoveryClassLoader);

        // Load module-supplied component providers once; the bean factories (deferred lambdas)
        // read this field at creation time, preferring generated instantiation over reflection.
        componentProviders = ComponentProviders.load(discoveryClassLoader, componentProviderList);

        // vauban#98: the build-compatible extension phase resolves a bean class the way the
        // container already knows it — a deployment spans several loaders (vidocq:dev defines
        // the application's classes in the layer while its libraries stay on the module path),
        // and one bean's loader cannot see the other side.
        var bceClassLoader =
                DeploymentClassLoader.forDeployment(beanClasses, this.classLoader, discoveryClassLoader);

        // --- Identify BCE classes and unprocessed archive classes ---
        var bceClasses = beanClasses.stream()
                .filter(ReflectionValidator::isBuildCompatibleExtension)
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
        var frozenEnhancementClasses = enhancementPatch.keySet().stream()
                .map(io.vidocq.vauban.core.extensions.EnhancementPatchSerializer::owner)
                .collect(java.util.stream.Collectors.toSet());
        var effectiveReplayPairs = runtimeReplayPairs.stream()
                .filter(p -> !frozenEnhancementClasses.contains(DotName.of(p.getValue().getName())))
                .toList();

        // If ALL sources are pre-processed AND we have a replay list, skip full BCE
        // lifecycle (cleaner: BCEs from pre-processed JARs are already digested).
        boolean allSourcesProcessed = unprocessedArchiveClasses.isEmpty() && !bceProcessedSources.isEmpty();
        if (allSourcesProcessed) {
            bceClasses = List.of();
        }

        BceProcessor.DiscoveryResult discoveryResult = null;
        var extensionAddedClasses = new ArrayList<Class<?>>();
        if (!bceClasses.isEmpty()) {
            var tempIndex = indexBuilder.build();
            var tempLookup = new io.vidocq.vauban.core.langmodel.IndexLookup(tempIndex);
            discoveryResult = BceProcessor.processDiscovery(bceClasses, tempLookup);

            // Add scanned classes to the index
            for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
                addExtensionScannedClass(className, discoveryClassLoader, bceClassLoader, indexBuilder,
                        extensionAddedClasses);
            }
        }

        var index = indexBuilder.build();

        // Extensions trusted with the managed classes' lookups (ModuleLookups) may ask from the
        // @Enhancement phase on; the container withdraws them at shutdown, a failed build right away.
        var moduleLookups = io.vidocq.vauban.core.access.ModuleLookups.register(
                componentProviders.providers(), beanClasses);
        extensionAddedClasses.forEach(moduleLookups::addManagedClass);

        try (var scope = new BuildScope(previousCl, moduleLookups)) {
            // Validate the class-level CDI rules before bean discovery. The validation reads
            // generic signatures by reflection, as the bytecode index does not hold them, and it
            // leaves out the build compatible extensions, which are not beans.
            var nonBceClasses = beanClasses.stream()
                    .filter(c -> !ReflectionValidator.isBuildCompatibleExtension(c))
                    .toList();
            validateClassDefinitions(index, nonBceClasses);

            // --- Collect Enhancement modifications: full BCE for unprocessed JARs +
            //     targeted replay for pre-processed JARs (vauban-bce-runtime.list) ---
            // BCEs available for the full Enhancement scan = explicit beanClasses BCEs
            // + BCEs declared in the runtime-list (they live in pre-processed JARs).
            var fullBceClasses = new java.util.LinkedHashSet<>(bceClasses);
            for (var pair : runtimeReplayPairs) {
                fullBceClasses.add(pair.getKey());
            }
            var combinedEnhMods = collectEnhancements(unprocessedArchiveClasses, fullBceClasses,
                    effectiveReplayPairs, enhancementPatch, index, bceClassLoader);
            // VAU-BCE-002: signatures of every synthetic bean registered during this boot,
            // shared between the runtime BCE path and the APT metadata path so identical
            // registrations (duplicated BCE discovery, wrapper subclasses) collapse to one.
            var seenSyntheticSignatures = new java.util.HashSet<String>();

            // Rebuild index with annotations added by Enhancement: live modifications
            // (full scan for unprocessed JARs / legacy replay) plus the frozen patch.
            index = enrichIndex(index, combinedEnhMods, enhancementPatch);

            var loadTimeWeaving = prepareLoadTimeWeaving();

            var discovery = new BeanDiscovery(index);
            configureDiscovery(discovery, discoveryResult);

            var descriptors = new ArrayList<>(discovery.discoverBeans());
            var observers = new ArrayList<>(discovery.discoverObservers());
            List<InterceptorDescriptor> interceptors =
                    new ArrayList<>(discovery.discoverInterceptors());
            addActivateRequestContextInterceptor(index, interceptors);
            var disposers = discovery.discoverDisposerMethods();
            var syntheticDisposers = new LinkedHashMap<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>>();

            // --- Build Compatible Extensions (BCE) — remaining phases ---
            if (!bceClasses.isEmpty()) {
                var bceResult = BceProcessor.process(
                        bceClasses, descriptors, observers, interceptors, index,
                        bceClassLoader,
                        discoveryResult.bceInstances(),
                        nonBceClasses,
                        managedBeans(index, discovery));
                failOnExtensionErrors(bceResult);
                registerExtensionResults(bceResult, descriptors, observers, syntheticDisposers,
                        seenSyntheticSignatures, combinedEnhMods);
            }

            // Apply ALL enhancement modifications (replay + full BCE) to descriptors
            if (!combinedEnhMods.isEmpty()) {
                interceptors = applyEnhancements(new Discovered(descriptors, observers, interceptors),
                        combinedEnhMods, managedBeans(index, discovery));
            }

            // Load synthetic beans/observers from APT-generated metadata (if BCE was processed at compile time)
            if (!bceProcessedSources.isEmpty()) {
                SyntheticComponentRegistrar.loadSyntheticMetadataFromApt(discoveryClassLoader, descriptors, factories,
                        syntheticDisposers, observers, seenSyntheticSignatures);
            }

            // One view of the annotation types for the whole container (vauban#70): the index first,
            // then the class files and declarations the discovery loader sees. The qualifier types of
            // an application or a TCK archive are invisible to vauban-core's own loader
            // (BUG-20260914-04), and the members extensions made non-binding belong to this container.
            var qualifierMatcher = new io.vidocq.vauban.core.bean.resolution.QualifierMatcher(
                    new io.vidocq.vauban.core.annotation.AnnotationTypes(index,
                            java.util.Arrays.asList(discoveryClassLoader, Thread.currentThread().getContextClassLoader(),
                                    VaubanContainerBuilder.class.getClassLoader()),
                            discovery.getCustomNonbindingMembers(),
                            // A module compiled with the Vauban processor ships what its annotation
                            // types declare, and reads and builds their instances itself.
                            componentProviders.providers()));

            // Validate observer/disposer method parameters (CDI spec)
            VaubanContainer.validateObserverParameters(observers, descriptors, index, qualifierMatcher);
            DisposerInvoker.validateDisposerParameters(disposers, descriptors, index, qualifierMatcher);
            // Validate disposer method definitions (CDI 4.1 Section 3.5)
            DisposerInvoker.validateDisposerDefinitions(disposers, descriptors);

            // Validate deployment — throw if there are errors
            validateDeployment(new Discovered(descriptors, observers, interceptors), index, qualifierMatcher,
                    loadTimeWeaving);

            var beanClassLoader = discoveryClassLoader;
            var vaubanLookup = getBuilderLookup();
            var container = new VaubanContainer(index, descriptors, observers, interceptors, disposers, factories, syntheticDisposers, beanClassLoader, classDefiner, vaubanLookup, componentProviders, qualifierMatcher);

            installContexts(container, contextRegistrations(discoveryResult, discoveryClassLoader), vaubanLookup);

            container.withdrawOnClose(moduleLookups);
            scope.built();
            return container;
        }
    }

    /**
     * What {@link #build()} undoes when it leaves: the module lookups, unless the container took them over, and the
     * context class loader, always.
     */
    private static final class BuildScope implements AutoCloseable {
        private final ClassLoader previousCl;
        private final io.vidocq.vauban.core.access.ModuleLookups.Registration moduleLookups;
        private boolean built;

        BuildScope(ClassLoader previousCl, io.vidocq.vauban.core.access.ModuleLookups.Registration moduleLookups) {
            this.previousCl = previousCl;
            this.moduleLookups = moduleLookups;
        }

        /** The container was built and withdraws the module lookups itself when it closes. */
        void built() {
            built = true;
        }

        @Override
        public void close() {
            try {
                if (!built) {
                    moduleLookups.close();
                }
            } finally {
                Thread.currentThread().setContextClassLoader(previousCl);
            }
        }
    }

    /** The beans, observers and interceptors discovery found, as the later phases rework them. */
    private record Discovered(List<BeanDescriptor> descriptors,
                              List<ObserverDescriptor> observers,
                              List<InterceptorDescriptor> interceptors) {}

    /** Indexes the class file of every bean class and gives each a default factory, unless it has one. */
    private void indexBeanClasses(IndexBuilder indexBuilder) {
        for (var clazz : beanClasses) {
            scanClassFile(clazz, indexBuilder);

            // Auto-register factory — will be replaced with constructor-aware
            // version after discovery if @Inject constructor is found
            if (!factories.containsKey(DotName.of(clazz.getName()))) {
                var lkp = getBuilderLookup();
                factories.put(DotName.of(clazz.getName()), () -> instantiate(clazz, lkp));
            }
        }
    }

    private static void scanClassFile(Class<?> clazz, IndexBuilder indexBuilder) {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
            // A class with no class file among the resources (e.g. dynamic or some test classes) is not indexed.
            if (is != null) {
                indexBuilder.add(io.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to scan class: " + clazz.getName(), e);
        }
    }

    /**
     * Loads a class an extension added through {@code ScannedClasses}, records it in {@code extensionAddedClasses},
     * indexes it and gives it a default factory. A class that cannot be loaded is skipped.
     */
    private void addExtensionScannedClass(String className, ClassLoader discoveryClassLoader,
            DeploymentClassLoader bceClassLoader, IndexBuilder indexBuilder, List<Class<?>> extensionAddedClasses) {
        try {
            var cls = Class.forName(className, false, discoveryClassLoader);
            // A class an extension added is part of the deployment from now on: the
            // later extension phases resolve it by identity, not by loading it again.
            bceClassLoader.register(cls);
            extensionAddedClasses.add(cls);
            indexClassResource(className, discoveryClassLoader, indexBuilder);
            if (!factories.containsKey(DotName.of(className))) {
                var lkp = getBuilderLookup();
                factories.put(DotName.of(className), () -> instantiate(cls, lkp));
            }
        } catch (Exception _) {
            // Class not found — skip
        }
    }

    private static void indexClassResource(String className, ClassLoader loader, IndexBuilder indexBuilder)
            throws IOException {
        String resource = className.replace('.', '/') + ".class";
        try (var is = loader.getResourceAsStream(resource)) {
            if (is != null) {
                indexBuilder.add(io.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
            }
        }
    }

    /** Fails with a {@code DefinitionException} listing the class-level definition errors, if any. */
    private static void validateClassDefinitions(VaubanIndex index,
            List<Class<?>> nonBceClasses) {
        var classErrors = io.vidocq.vauban.core.bean.validation.ClassValidator.validate(index);
        if (!classErrors.isEmpty()) {
            throw new jakarta.enterprise.inject.spi.DefinitionException(failureMessage(DEFINITION_FAILED, classErrors));
        }
        var reflectionErrors = ReflectionValidator.validateWithReflection(nonBceClasses);
        if (!reflectionErrors.isEmpty()) {
            throw new jakarta.enterprise.inject.spi.DefinitionException(
                    failureMessage(DEFINITION_FAILED, reflectionErrors));
        }
    }

    /** {@code header}, then one {@code "  - error"} line per error. */
    private static String failureMessage(String header, List<String> errors) {
        var msg = new StringBuilder(header);
        for (var error : errors) {
            msg.append("  - ").append(error).append("\n");
        }
        return msg.toString();
    }

    /**
     * The Enhancement modifications of the full extension scan of the unprocessed archives, of the legacy reflective
     * replay (only for targets not covered by the frozen patch) and of the members of the frozen patch.
     */
    private static Map<DotName, List<VaubanClassConfig>> collectEnhancements(
            List<Class<?>> unprocessedArchiveClasses, Set<Class<?>> fullBceClasses,
            List<Map.Entry<Class<?>, Class<?>>> effectiveReplayPairs,
            Map<DotName, List<AnnotationInfo>> enhancementPatch,
            VaubanIndex index, DeploymentClassLoader bceClassLoader) {
        var combinedEnhMods = new java.util.HashMap<DotName, List<VaubanClassConfig>>();
        if (!unprocessedArchiveClasses.isEmpty() && !fullBceClasses.isEmpty()) {
            var enhMods = BceProcessor.processEnhancementOnly(
                    List.copyOf(fullBceClasses), unprocessedArchiveClasses, index, bceClassLoader);
            enhMods.forEach((k, v) -> combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
        }

        // Legacy reflective replay — only for targets NOT covered by the frozen patch.
        if (!effectiveReplayPairs.isEmpty()) {
            var replayMods = BceProcessor.replayEnhancementForTargets(
                    effectiveReplayPairs, index);
            replayMods.forEach((k, v) -> combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
        }

        io.vidocq.vauban.core.extensions.EnhancementPatchSerializer
                .memberConfigurations(enhancementPatch, index).forEach((name, configs) ->
                        combinedEnhMods.computeIfAbsent(name, _ -> new ArrayList<>()).addAll(configs));
        return combinedEnhMods;
    }

    /** {@code index} with the annotations the Enhancement modifications and the frozen patch add to its classes. */
    private static VaubanIndex enrichIndex(VaubanIndex index,
            Map<DotName, List<VaubanClassConfig>> combinedEnhMods,
            Map<DotName, List<AnnotationInfo>> enhancementPatch) {
        if (combinedEnhMods.isEmpty() && enhancementPatch.isEmpty()) {
            return index;
        }
        var enrichedBuilder = new IndexBuilder();
        for (var classInfo : index.getKnownClasses()) {
            enrichedBuilder.add(enrichedClass(classInfo, combinedEnhMods.get(classInfo.name()),
                    enhancementPatch.get(classInfo.name())));
        }
        return enrichedBuilder.build();
    }

    private static io.vidocq.vauban.indexer.model.ClassInfo enrichedClass(
            io.vidocq.vauban.indexer.model.ClassInfo classInfo,
            List<VaubanClassConfig> mods,
            List<AnnotationInfo> patched) {
        if (mods == null && patched == null) {
            return classInfo;
        }
        var newAnnotations = new java.util.ArrayList<>(classInfo.annotations());
        // With their members: an added @Named("x") or qualifier member is part of the
        // class (BUG-20261008-05).
        if (mods != null) {
            for (var config : mods) {
                newAnnotations.addAll(config.getAddedAnnotationsIndexed());
            }
        }
        if (patched != null) {
            newAnnotations.addAll(patched);
        }
        // withAnnotations keeps the simple name: a nested class's default bean name
        // is not its binary simple name (Outer$Bean)
        return classInfo.withAnnotations(newAnnotations);
    }

    /**
     * vauban#24 load-time weaving tier (backstop — the Vidocq bootstrap already ran it before its extensions could
     * load application classes): must run BEFORE discovery/validation, both may load bean classes, and a class loaded
     * before the agent is attached can no longer gain its (ProxyLink) constructor.
     */
    private io.vidocq.vauban.core.weaving.LoadTimeWeaving.Result prepareLoadTimeWeaving() {
        var weavingLoader = classLoader != null
                ? classLoader : Thread.currentThread().getContextClassLoader();
        var loadTimeWeaving =
                io.vidocq.vauban.core.weaving.LoadTimeWeaving.prepare(weavingLoader);
        if (loadTimeWeaving.failure() != null) {
            LOG.log(System.Logger.Level.WARNING, loadTimeWeaving.failure());
        }

        // issue #42, Stage 3b: open the packages of produced types that fall to runtime proxy
        // generation to the container (via the agent), so the runtime fallback needs no
        // hand-written --add-opens. A strict no-op when no META-INF/vauban/required-opens.list
        // is present (the common case, including the TCK).
        io.vidocq.vauban.core.opens.OpensApplier.apply(weavingLoader);
        return loadTimeWeaving;
    }

    private void configureDiscovery(BeanDiscovery discovery,
            BceProcessor.DiscoveryResult discoveryResult) {
        discovery.setStrictScannedDiscovery(strictScannedDiscovery);

        // Apply @Discovery results to BeanDiscovery
        if (discoveryResult != null) {
            applyExtensionDiscovery(discovery, discoveryResult);
        }

        // Force bean classes from bean-discovery-mode=all archives (e.g. from scanBeanArchivesFromClasspath)
        if (!forcedDiscoveryClasses.isEmpty()) {
            discovery.addForcedBeanClasses(dotNames(forcedDiscoveryClasses));
        }
    }

    private void applyExtensionDiscovery(BeanDiscovery discovery,
            BceProcessor.DiscoveryResult discoveryResult) {
        var meta = discoveryResult.metaAnnotations();
        discovery.setCustomQualifiers(dotNames(meta.getCustomQualifiers()));
        discovery.setCustomInterceptorBindings(dotNames(meta.getCustomInterceptorBindings()));
        discovery.setCustomStereotypes(dotNames(meta.getCustomStereotypes()));
        var stereotypeAnns = new java.util.HashMap<DotName, java.util.Set<Class<? extends Annotation>>>();
        for (var entry : meta.getStereotypeAnnotations().entrySet()) {
            stereotypeAnns.put(DotName.of(entry.getKey().getName()), entry.getValue());
        }
        discovery.setCustomStereotypeAnnotations(stereotypeAnns);
        // The container's own AnnotationTypes reads them back below, so no static state.
        discovery.setCustomNonbindingMembers(meta.getNonbindingMembers());
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

    private static <T> Set<DotName> dotNames(java.util.Collection<Class<? extends T>> classes) {
        return classes.stream()
                .map(c -> DotName.of(c.getName()))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * CDI 4.1 §6.5.9: the built-in {@code @ActivateRequestContext} interceptor, only when a class of the deployment
     * uses the binding, so that no other deployment pays for interception checks it does not need
     * (Vidocq/vauban#147).
     */
    private static void addActivateRequestContextInterceptor(VaubanIndex index,
            List<InterceptorDescriptor> interceptors) {
        if (usesBinding(index, ACTIVATE_REQUEST_CONTEXT)) {
            interceptors.add(new InterceptorDescriptor(
                    DotName.of(io.vidocq.vauban.core.context.ActivateRequestContextInterceptor.class.getName()),
                    java.util.Set.of(ACTIVATE_REQUEST_CONTEXT),
                    io.vidocq.vauban.core.context.ActivateRequestContextInterceptor.AROUND_INVOKE, null,
                    io.vidocq.vauban.core.context.ActivateRequestContextInterceptor.PRIORITY, true, List.of()));
        }
    }

    /**
     * BCE definition errors → DefinitionException; BCE deployment errors → DeploymentException. The first typed
     * exception a BCE threw becomes the cause (VAU-BCE-003): specs define the exception a failed deployment must
     * surface, and TCKs assert it through the cause chain (@ShouldThrowException).
     */
    private static void failOnExtensionErrors(BceProcessor.Result bceResult) {
        if (!bceResult.definitionErrors().isEmpty()) {
            throw new jakarta.enterprise.inject.spi.DefinitionException(
                    failureMessage(DEFINITION_FAILED, bceResult.definitionErrors()));
        }
        if (!bceResult.deploymentErrors().isEmpty()) {
            var failure = new jakarta.enterprise.inject.spi.DeploymentException(
                    failureMessage(DEPLOYMENT_FAILED, bceResult.deploymentErrors()));
            var causes = bceResult.deploymentErrorCauses();
            if (!causes.isEmpty()) {
                failure.initCause(causes.getFirst());
                for (var extra : causes.subList(1, causes.size())) {
                    failure.addSuppressed(extra);
                }
            }
            throw failure;
        }
    }

    /** Registers the synthetic beans and observers of the extensions, and merges their enhancement modifications. */
    private void registerExtensionResults(BceProcessor.Result bceResult,
            List<BeanDescriptor> descriptors, List<ObserverDescriptor> observers,
            Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
            Set<String> seenSyntheticSignatures,
            Map<DotName, List<VaubanClassConfig>> combinedEnhMods) {
        // Register synthetic beans
        for (var synBean : bceResult.syntheticBeans()) {
            SyntheticComponentRegistrar.registerSyntheticBean(synBean, descriptors, factories,
                    syntheticDisposers, seenSyntheticSignatures);
        }

        // Register synthetic observers
        for (var synObs : bceResult.syntheticObservers()) {
            observers.add(SyntheticComponentRegistrar.buildSyntheticObserver(synObs));
        }

        // Merge BCE enhancement modifications into the combined map (replay + full)
        bceResult.enhancementModifications().forEach((k, v) ->
                combinedEnhMods.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
    }

    /**
     * Applies the enhancement modifications to the discovered beans and observers, in place, and returns the
     * interceptors they give. Modified beans include the non-beans that gained a scope via Enhancement (e.g.
     * {@code @Path} classes that receive {@code @RequestScoped} from a BCE).
     */
    private static List<InterceptorDescriptor> applyEnhancements(
            Discovered discovered,
            Map<DotName, List<VaubanClassConfig>> combinedEnhMods,
            java.util.function.Function<DotName, BeanDescriptor> managedBeans) {
        var descriptors = discovered.descriptors();
        var modified = BceProcessor.beansAfterEnhancement(
                descriptors, combinedEnhMods, managedBeans);
        descriptors.clear();
        descriptors.addAll(modified);

        var interceptors = new ArrayList<>(BceProcessor.applyInterceptorEnhancements(
                discovered.interceptors(), combinedEnhMods));

        var observers = discovered.observers();
        var modifiedObservers = BceProcessor.applyObserverEnhancements(
                observers, combinedEnhMods);
        observers.clear();
        observers.addAll(modifiedObservers);
        return interceptors;
    }

    /** Deployment validation: a definition error first, then a deployment error, then any other error. */
    private static void validateDeployment(Discovered discovered, VaubanIndex index,
            io.vidocq.vauban.core.bean.resolution.QualifierMatcher qualifierMatcher,
            io.vidocq.vauban.core.weaving.LoadTimeWeaving.Result loadTimeWeaving) {
        var descriptors = discovered.descriptors();
        var assignability = new AssignabilityRules(index);
        var tempResolver = new BeanResolver(descriptors, discovered.interceptors(), assignability, qualifierMatcher);
        var validator = new DeploymentValidator(
                descriptors, tempResolver, index)
                .loadTimeWoven(loadTimeWeaving.planned());
        var errors = validator.validate();
        if (errors.isEmpty()) {
            return;
        }
        var definitionErrors = messagesOf(errors, Set.of(
                DeploymentValidator.ValidationError.Kind.DEFINITION_ERROR));
        if (!definitionErrors.isEmpty()) {
            throw new jakarta.enterprise.inject.spi.DefinitionException(
                    failureMessage(DEFINITION_FAILED, definitionErrors));
        }

        // Ambiguous or unsatisfied dependencies are DeploymentExceptions in CDI
        var deploymentErrors = messagesOf(errors, DEPLOYMENT_ERROR_KINDS);
        if (!deploymentErrors.isEmpty()) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                    failureMessage(DEPLOYMENT_FAILED, deploymentErrors));
        }

        // Default fallback
        throw new jakarta.enterprise.inject.spi.DeploymentException(failureMessage("CDI validation failed:\n",
                errors.stream().map(DeploymentValidator.ValidationError::message)
                        .toList()));
    }

    private static List<String> messagesOf(
            List<DeploymentValidator.ValidationError> errors,
            Set<DeploymentValidator.ValidationError.Kind> kinds) {
        return errors.stream()
                .filter(e -> kinds.contains(e.kind()))
                .map(DeploymentValidator.ValidationError::message)
                .toList();
    }

    /**
     * The custom contexts to register, by {@code scope;contextClass}: those the extensions that ran at boot
     * registered (MetaAnnotations.addContext), then those the processor froze into META-INF/vauban-contexts.list. An
     * application compiled with the processor skips its extensions at boot, and would otherwise lose every context
     * they register.
     */
    private static Map<String, Map.Entry<Class<? extends Annotation>, Class<?>>> contextRegistrations(
            BceProcessor.DiscoveryResult discoveryResult,
            ClassLoader discoveryClassLoader) {
        var contextRegistrations = new LinkedHashMap<String, Map.Entry<Class<? extends Annotation>, Class<?>>>();
        if (discoveryResult != null) {
            for (var reg : discoveryResult.metaAnnotations().getCustomContexts()) {
                contextRegistrations.putIfAbsent(reg.scopeAnnotation().getName() + ";" + reg.contextClass().getName(),
                        java.util.Map.entry(reg.scopeAnnotation(), reg.contextClass()));
            }
        }
        for (var frozen : io.vidocq.vauban.core.extensions.FrozenContexts.read(discoveryClassLoader)) {
            var key = frozen.scope() + ";" + frozen.contextClass();
            contextRegistrations.computeIfAbsent(key, _ -> loadFrozenContext(frozen, discoveryClassLoader));
        }
        return contextRegistrations;
    }

    private static Map.Entry<Class<? extends Annotation>, Class<?>> loadFrozenContext(
            io.vidocq.vauban.core.extensions.FrozenContexts.Entry frozen, ClassLoader discoveryClassLoader) {
        try {
            @SuppressWarnings("unchecked")
            var scope = (Class<? extends Annotation>)
                    Class.forName(frozen.scope(), false, discoveryClassLoader);
            return java.util.Map.entry(scope, Class.forName(frozen.contextClass(), false, discoveryClassLoader));
        } catch (ClassNotFoundException | LinkageError e) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                    "Cannot load the context " + frozen.contextClass() + " registered for scope "
                            + frozen.scope() + " (" + io.vidocq.vauban.core.extensions.FrozenContexts.PATH + ")", e);
        }
    }

    /**
     * Registers the custom contexts from Build Compatible Extensions (MetaAnnotations.addContext). Instantiation
     * order: module's VaubanComponentProvider (zero reflection), then the PUBLIC constructor path — a BCE context
     * class is public with a public no-arg constructor, so in an exported package this needs no {@code opens} on the
     * module path — and only then the private-lookup path (classpath, or an opened package). A declared scope whose
     * context cannot be installed is a deployment error: silently skipping it makes every bean of that scope silently
     * fall back to per-lookup instances (VAU-CTX-001, surfaced as mansart's "empty @TransactionScoped" bug).
     */
    private void installContexts(VaubanContainer container,
            Map<String, Map.Entry<Class<? extends Annotation>, Class<?>>> contextRegistrations,
            VaubanLookup vaubanLookup) {
        for (var reg : contextRegistrations.values()) {
            var contextClass = reg.getValue();
            try {
                Object instance = componentProviders.create(contextClass.getName());
                if (instance == null) {
                    instance = newInstanceThroughPublicConstructor(contextClass, vaubanLookup);
                }
                if (instance == null) {
                    instance = vaubanLookup.newInstance(contextClass);
                }
                var ctx = (jakarta.enterprise.context.spi.Context) instance;
                container.contexts.computeIfAbsent(reg.getKey(), k -> new java.util.ArrayList<>()).add(ctx);
            } catch (Exception e) {
                throw new jakarta.enterprise.inject.spi.DeploymentException(
                        "Cannot install the context " + contextClass.getName()
                                + " registered for scope " + reg.getKey().getName()
                                + " (MetaAnnotations.addContext). The context class must be public"
                                + " with a public no-arg constructor in an exported package.", e);
            }
        }
    }

    /** A new {@code contextClass} through its public no-arg constructor, or {@code null} when it has none. */
    private static Object newInstanceThroughPublicConstructor(Class<?> contextClass, VaubanLookup vaubanLookup) {
        try {
            return vaubanLookup.newInstancePublic(contextClass.getConstructor());
        } catch (NoSuchMethodException _) {
            // fall through to the private-lookup path
            return null;
        }
    }

}
