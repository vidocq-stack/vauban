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
import io.vidocq.vauban.core.extensions.FrozenExtensions;
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
    /** The beans the embedding runtime added itself ({@link #addHostBean}), by class name. */
    private final Set<String> hostBeanClasses = new java.util.HashSet<>();
    /**
     * The bean classes of the deployment ({@link #addBeanClass}, a scan included), by class name: a host bean among
     * them is the deployment's too, and stays in its fingerprint (BUG-20261113-16).
     */
    private final Set<String> deploymentClasses = new java.util.HashSet<>();
    final Set<String> bceProcessedSources = new java.util.HashSet<>();
    private final Map<DotName, BeanFactory<?>> factories = new LinkedHashMap<>();
    /** The classes {@link #addFactory} was given a factory for: built without reflection. */
    private final java.util.Set<DotName> suppliedFactories = new java.util.HashSet<>();
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
        deploymentClasses.add(beanClass.getName());
        return addClass(beanClass);
    }

    private VaubanContainerBuilder addClass(Class<?> beanClass) {
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
        suppliedFactories.add(DotName.of(beanClass.getName()));
        return this;
    }

    /**
     * Adds a bean of the runtime that embeds the container, created by {@code factory}: a holder the runtime fills
     * before the container starts, for one. Such a bean is not part of the application's deployment as a build saw
     * it, and whether the runtime adds it may depend on where the application starts.
     *
     * <p>For a deployment whose extensions the build froze ({@link #freezeExtensions}), the difference with
     * {@link #addBeanClass} matters: a host bean leaves the build's record valid, where one more bean class makes
     * the start run every extension again. In return the frozen extensions never saw the bean; give it nothing an
     * extension would have to act on. Without a record the extensions run on it as on any class.
     *
     * <p>A class the deployment holds as well, one a scan finds for instance, stays the deployment's: whether the
     * runtime also adds it leaves the fingerprint as the build computed it (BUG-20261113-16).
     */
    public <T> VaubanContainerBuilder addHostBean(Class<T> beanClass, BeanFactory<T> factory) {
        addClass(beanClass);
        addFactory(beanClass, factory);
        hostBeanClasses.add(beanClass.getName());
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
            mergeEnhancementPatch(io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.read(is), merged);
        } catch (IOException _) {
            // unreadable patch resource — skip
        }
    }

    /** Merges {@code patch} into {@code merged}, each added annotation once. */
    private static void mergeEnhancementPatch(Map<String, List<AnnotationInfo>> patch,
            Map<DotName, List<AnnotationInfo>> merged) {
        patch.forEach((target, anns) -> {
            var list = merged.computeIfAbsent(DotName.of(target), _ -> new ArrayList<>());
            for (var ann : anns) {
                if (!list.contains(ann)) list.add(ann);
            }
        });
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

    /**
     * Freezes the build compatible extensions of this deployment: see {@link #freezeExtensions(java.nio.file.Path,
     * java.util.Collection)}, with no extension named to run at start.
     */
    public FrozenExtensions.Outcome freezeExtensions(java.nio.file.Path output) {
        return freezeExtensions(output, List.of());
    }

    /**
     * Runs the build compatible extensions of this deployment the way {@link #build()} does, at build time
     * ({@link io.vidocq.vauban.api.ExtensionPhase#isBuildTime()} answers {@code true}), and writes what they did
     * under {@code output}, the root of the application's output (VAU-PERF-005). A container built later for the
     * same deployment, with that output on its path, loads the record instead of running the extensions.
     *
     * <p>For the build step that sees the deployment as the start will: the application's classes and every archive
     * of its path, configured on this builder as the runtime configures its own. Nothing is started and no bean is
     * created.
     *
     * <p>An extension that asked whether it runs at build time left work for the start: it is recorded as running
     * again at each start, and what it did is not frozen. So are the extensions {@code startExtensions} names.
     *
     * <p>No record is written, a stale one is removed and the outcome says why, when the deployment has no
     * extension, when an extension fails or reports an error (the start will report it, in the environment it
     * belongs to), when an {@code @Enhancement} removes annotations or when a synthetic component holds a parameter
     * the metadata cannot express. The start then runs the extensions, as it does without this step.
     *
     * @param output          where the application's classes are compiled
     * @param startExtensions class names of extensions to run at each start whatever they asked
     */
    public FrozenExtensions.Outcome freezeExtensions(java.nio.file.Path output,
            java.util.Collection<String> startExtensions) {
        var capture = new ExtensionCapture();
        try {
            io.vidocq.vauban.api.ExtensionPhase.atBuildTime(() ->
                    io.vidocq.vauban.core.extensions.ExtensionAsks.recordingInto(capture.asked,
                            () -> build(capture)));
            var frozen = capture.frozen(startExtensions);
            frozen.write(output);
            return new FrozenExtensions.Outcome(true, null, frozen.extensions(),
                    frozen.extensions().stream().filter(frozen.startExtensions()::contains).toList());
        } catch (ExtensionCapture.NotFrozen e) {
            FrozenExtensions.delete(output);
            return FrozenExtensions.Outcome.notFrozen(e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            // A definition or deployment problem an extension found, or one it threw: the start says it, where
            // the application runs, and the build goes on.
            FrozenExtensions.delete(output);
            return FrozenExtensions.Outcome.notFrozen("the extensions did not complete at build ("
                    + (e.getMessage() != null ? e.getMessage().strip() : e.toString()) + ")");
        }
    }

    /** What the extension cycle of a build did, as {@link #build(ExtensionCapture)} hands it over. */
    private static final class ExtensionCapture {

        /** Why a deployment cannot be frozen. */
        static final class NotFrozen extends RuntimeException {
            private static final long serialVersionUID = 1L;

            NotFrozen(String reason) {
                super(reason);
            }
        }

        /** The extensions that asked whether they run at build time. */
        final Set<Class<?>> asked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private String fingerprint;
        private List<Class<?>> extensions = List.of();
        private BceProcessor.DiscoveryResult discovery;
        private Map<DotName, List<VaubanClassConfig>> enhancements = Map.of();
        private BceProcessor.Result result;

        void captured(String fingerprint, List<Class<?>> extensions, BceProcessor.DiscoveryResult discovery,
                Map<DotName, List<VaubanClassConfig>> enhancements, BceProcessor.Result result) {
            this.fingerprint = fingerprint;
            this.extensions = extensions;
            this.discovery = discovery;
            this.enhancements = enhancements;
            this.result = result;
        }

        /** The record of what was captured, without what the extensions that run at start did. */
        FrozenExtensions frozen(java.util.Collection<String> namedForStart) {
            if (extensions.isEmpty() || result == null || discovery == null) {
                throw new NotFrozen(FrozenExtensions.NO_EXTENSION);
            }
            var atStart = new java.util.TreeSet<String>();
            asked.forEach(extension -> atStart.add(extension.getName()));
            extensions.stream().map(Class::getName).filter(namedForStart::contains).forEach(atStart::add);

            // What @Enhancement did, of the extensions that stay frozen. A configuration the frozen files of an
            // archive gave has no extension: the start reads those files itself.
            var frozenModifications = new LinkedHashMap<DotName, List<VaubanClassConfig>>();
            enhancements.forEach((target, configs) -> {
                for (var config : configs) {
                    var source = config.getSourceBce();
                    if (source == null || atStart.contains(source.getName())) {
                        continue;
                    }
                    if (config.removesAnnotations()) {
                        throw new NotFrozen(source.getName() + " removes annotations from " + target
                                + " in @Enhancement, which a record of additions cannot say");
                    }
                    frozenModifications.computeIfAbsent(target, _ -> new ArrayList<>()).add(config);
                }
            });

            var synthetic = new java.util.Properties();
            var beans = result.syntheticBeans().stream()
                    .filter(bean -> !runsAtStart(bean.getSourceExtension(), atStart)).toList();
            var observers = result.syntheticObservers().stream()
                    .filter(observer -> !runsAtStart(observer.getSourceExtension(), atStart)).toList();
            try {
                var written = new java.io.ByteArrayOutputStream();
                io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.write(beans, observers, written);
                synthetic.load(new java.io.InputStreamReader(
                        new java.io.ByteArrayInputStream(written.toByteArray()),
                        java.nio.charset.StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new NotFrozen("a synthetic component cannot be written (" + e.getMessage() + ")");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }

            var contexts = io.vidocq.vauban.core.extensions.FrozenContexts.of(
                    discovery.metaAnnotations().getCustomContexts());
            return new FrozenExtensions(fingerprint, extensions.stream().map(Class::getName).toList(), atStart,
                    ExtensionDiscovery.of(discovery).named(contexts),
                    io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.additions(frozenModifications),
                    synthetic);
        }

        private static boolean runsAtStart(Class<?> extension, Set<String> atStart) {
            return extension != null && atStart.contains(extension.getName());
        }
    }

    /**
     * The record of the frozen extensions {@code loader} sees, if it is this deployment's: the one whose
     * fingerprint is {@code fingerprint}. Another one is left alone, with a note saying so.
     */
    private static FrozenExtensions.Found frozenExtensionsOf(ClassLoader loader, String fingerprint) {
        var found = FrozenExtensions.find(loader);
        if (found.frozen() == null) {
            if (found.note() != null) {
                LOG.log(System.Logger.Level.DEBUG, "The extensions run at start: {0}.", found.note());
            }
            return found;
        }
        if (!found.frozen().fingerprint().equals(fingerprint)) {
            String note = "the record of the frozen extensions (" + FrozenExtensions.PATH + ") has the fingerprint "
                    + found.frozen().fingerprint() + ", this deployment " + fingerprint
                    + ": a bean class was added, removed or recompiled since the build that wrote it";
            LOG.log(System.Logger.Level.DEBUG, "The extensions run at start: {0}.", note);
            return new FrozenExtensions.Found(null, note);
        }
        return found;
    }

    /**
     * Which build compatible extensions a build of the container runs, and what stands for the others.
     *
     * @param lookup               the record of the frozen extensions, used or not
     * @param toRun                the extensions that run: all of them, none when every archive is processed, or,
     *                             with a record, those that left work for the start
     * @param toEnhance            the classes those extensions enhance
     * @param replayPairs          the (extension, class) pairs of {@code META-INF/vauban-bce-runtime.list} still to
     *                             consider
     * @param enhancementPatch     the annotations frozen for each target: by the archives, and by the record
     * @param effectiveReplayPairs the pairs to replay: those whose class the frozen patch does not cover
     */
    private record ExtensionPlan(FrozenExtensions.Found lookup, List<Class<?>> toRun, List<Class<?>> toEnhance,
            List<Map.Entry<Class<?>, Class<?>>> replayPairs, Map<DotName, List<AnnotationInfo>> enhancementPatch,
            List<Map.Entry<Class<?>, Class<?>>> effectiveReplayPairs) {

        FrozenExtensions frozen() {
            return lookup.frozen();
        }
    }

    /**
     * Decides which extensions run. With a record of this very deployment (VAU-PERF-005) the start loads what the
     * frozen ones did and runs only those that left work for the start; a build capturing the extensions looks for
     * no record.
     */
    private ExtensionPlan planExtensions(boolean capturing, ClassLoader discoveryClassLoader, String fingerprint) {
        List<Class<?>> deploymentExtensions = beanClasses.stream()
                .filter(ReflectionValidator::isBuildCompatibleExtension)
                .toList();
        var found = capturing || deploymentExtensions.isEmpty()
                ? new FrozenExtensions.Found(null, null)
                : frozenExtensionsOf(discoveryClassLoader, fingerprint);
        var frozen = found.frozen();

        // Classes from sources WITHOUT vauban-bce-processed marker need runtime @Enhancement
        List<Class<?>> unmarkedArchiveClasses = beanClasses.stream()
                .filter(c -> !ReflectionValidator.isBuildCompatibleExtension(c))
                .filter(c -> !isClassFromBceProcessedSource(c))
                .toList();

        List<Class<?>> toRun;
        if (frozen != null) {
            toRun = deploymentExtensions.stream()
                    .filter(extension -> frozen.startExtensions().contains(extension.getName()))
                    .toList();
        } else {
            // If ALL sources are pre-processed AND we have a replay list, skip full BCE
            // lifecycle (cleaner: BCEs from pre-processed JARs are already digested).
            boolean allSourcesProcessed = unmarkedArchiveClasses.isEmpty() && !bceProcessedSources.isEmpty();
            toRun = allSourcesProcessed ? List.of() : deploymentExtensions;
        }
        // Nothing to enhance when no extension runs: the record holds what the frozen ones added.
        List<Class<?>> toEnhance = frozen != null && toRun.isEmpty() ? List.of() : unmarkedArchiveClasses;

        // Load (bceFqn, targetFqn) pairs from META-INF/vauban-bce-runtime.list
        // Pre-processed JARs ship this list so the runtime can replay BCE @Enhancement
        // on a precise set of classes (no re-scan, no full BCE lifecycle). With a record, only the pairs of the
        // extensions that run at start are replayed: the build captured the others.
        var replayPairs = loadRuntimeReplayList(discoveryClassLoader).stream()
                .filter(pair -> frozen == null || toRun.contains(pair.getKey()))
                .toList();

        // Frozen @Enhancement result (target -> added annotation FQNs). When present for a
        // target, it replaces the reflective replay below: we apply the annotations directly
        // and never re-instantiate the BCE (no `opens` needed on the module path).
        var enhancementPatch = loadEnhancementPatch(discoveryClassLoader);
        if (frozen != null) {
            mergeEnhancementPatch(frozen.enhancements(), enhancementPatch);
        }
        var frozenEnhancementClasses = enhancementPatch.keySet().stream()
                .map(io.vidocq.vauban.core.extensions.EnhancementPatchSerializer::owner)
                .collect(java.util.stream.Collectors.toSet());
        var effectiveReplayPairs = replayPairs.stream()
                .filter(p -> !frozenEnhancementClasses.contains(DotName.of(p.getValue().getName())))
                .toList();
        return new ExtensionPlan(found, toRun, toEnhance, replayPairs, enhancementPatch, effectiveReplayPairs);
    }

    /** What {@code @Discovery} registered: the extensions that ran, the record of the frozen ones, or both. */
    private static ExtensionDiscovery registeredAtDiscovery(BceProcessor.DiscoveryResult ran, FrozenExtensions frozen,
            ClassLoader discoveryClassLoader) {
        ExtensionDiscovery live = ran == null ? null : ExtensionDiscovery.of(ran);
        if (frozen == null) {
            return live;
        }
        var recorded = ExtensionDiscovery.of(frozen.discovery(), discoveryClassLoader);
        return live == null ? recorded : recorded.plus(live);
    }

    /** Adds the classes the extensions added through {@code ScannedClasses} to the index, and returns them. */
    private List<Class<?>> addExtensionScannedClasses(ExtensionDiscovery registered,
            ClassLoader discoveryClassLoader, DeploymentClassLoader bceClassLoader, IndexBuilder indexBuilder) {
        var added = new ArrayList<Class<?>>();
        if (registered != null) {
            for (var className : registered.scannedClasses()) {
                addExtensionScannedClass(className, discoveryClassLoader, bceClassLoader, indexBuilder, added);
            }
        }
        return added;
    }

    /** What this start did with the extensions, for {@link VaubanContainer#extensionsAtStart()}. */
    private static ExtensionsAtStart extensionsAtStart(ExtensionPlan plan) {
        var frozen = plan.frozen();
        List<String> frozenAtBuild = frozen == null ? List.of() : frozen.extensions().stream()
                .filter(extension -> !frozen.startExtensions().contains(extension)).toList();
        return new ExtensionsAtStart(frozen != null, frozenAtBuild,
                plan.toRun().stream().map(Class::getName).toList(), plan.lookup().note());
    }

    public VaubanContainer build() {
        return build(null);
    }

    /**
     * Builds the container, or, with a {@code capture}, runs the build up to the end of the extension cycle, hands
     * the capture what the extensions did and returns {@code null}.
     */
    private VaubanContainer build(ExtensionCapture capture) {
        var indexBuilder = new IndexBuilder();
        var fingerprint = new DeploymentFingerprint("beanArchive=" + isBeanArchive + ";strictScannedDiscovery="
                + strictScannedDiscovery + ";forced=" + new java.util.TreeSet<>(forcedDiscoveryClasses.stream()
                        .map(Class::getName).toList()));
        indexBeanClasses(indexBuilder, fingerprint);

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

        // Which extensions run, and what stands for those the build froze (VAU-PERF-005).
        var plan = planExtensions(capture != null, discoveryClassLoader, fingerprint.value());
        var frozen = plan.frozen();
        var bceClasses = plan.toRun();
        var unprocessedArchiveClasses = plan.toEnhance();
        var runtimeReplayPairs = plan.replayPairs();
        var enhancementPatch = plan.enhancementPatch();
        var effectiveReplayPairs = plan.effectiveReplayPairs();

        BceProcessor.DiscoveryResult discoveryResult = bceClasses.isEmpty() ? null
                : BceProcessor.processDiscovery(bceClasses,
                        new io.vidocq.vauban.core.langmodel.IndexLookup(indexBuilder.build()));
        // What @Discovery registered: by the extensions that just ran, by those the build froze, or both.
        var extensionDiscovery = registeredAtDiscovery(discoveryResult, frozen, discoveryClassLoader);
        var extensionAddedClasses = addExtensionScannedClasses(extensionDiscovery, discoveryClassLoader,
                bceClassLoader, indexBuilder);

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

            // A build capturing the extensions starts nothing: no class of this loader needs weaving.
            var loadTimeWeaving = capture != null ? null : prepareLoadTimeWeaving();

            var discovery = new BeanDiscovery(index);
            configureDiscovery(discovery, extensionDiscovery);

            var descriptors = new ArrayList<>(discovery.discoverBeans());
            var observers = new ArrayList<>(discovery.discoverObservers());
            List<InterceptorDescriptor> interceptors =
                    new ArrayList<>(discovery.discoverInterceptors());
            addActivateRequestContextInterceptor(index, interceptors);
            var disposers = discovery.discoverDisposerMethods();
            var syntheticDisposers = new LinkedHashMap<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>>();

            // --- Build Compatible Extensions (BCE) — remaining phases ---
            BceProcessor.Result bceResult = null;
            // The extensions that run went through @Discovery above: the same condition as "some run".
            if (discoveryResult != null) {
                bceResult = BceProcessor.process(
                        bceClasses, descriptors, observers, interceptors, index,
                        bceClassLoader,
                        discoveryResult.bceInstances(),
                        nonBceClasses,
                        managedBeans(index, discovery));
                failOnExtensionErrors(bceResult);
                registerExtensionResults(bceResult, descriptors, observers, syntheticDisposers,
                        seenSyntheticSignatures, combinedEnhMods);
            }
            if (capture != null) {
                // The build stops here: what the extensions did is all it wants.
                capture.captured(fingerprint.value(), bceClasses, discoveryResult, combinedEnhMods, bceResult);
                return null;
            }
            if (frozen != null) {
                // The synthetic beans and observers of the frozen extensions, where those of the extensions that
                // ran are registered: before the archives' own metadata, with which identical ones collapse.
                SyntheticComponentRegistrar.registerSyntheticMetadata(frozen.synthetic(), discoveryClassLoader,
                        descriptors, factories, syntheticDisposers, observers, seenSyntheticSignatures);
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
            container.suppliedFactories(suppliedFactories);

            installContexts(container, contextRegistrations(discoveryResult,
                    frozen != null ? frozen.discovery().contexts() : List.of(), discoveryClassLoader), vaubanLookup);
            container.extensionsAtStart(extensionsAtStart(plan));

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
    private void indexBeanClasses(IndexBuilder indexBuilder, DeploymentFingerprint fingerprint) {
        for (var clazz : beanClasses) {
            byte[] classFile = classFileOf(clazz);
            // A class with no class file among the resources (e.g. dynamic or some test classes) is not indexed.
            if (classFile.length > 0) {
                indexBuilder.add(io.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(classFile));
            }
            // The fingerprint of the deployment as a build sees it: without the runtime's own beans.
            if (!hostBeanClasses.contains(clazz.getName()) || deploymentClasses.contains(clazz.getName())) {
                fingerprint.add(clazz.getName(), classFile);
            }

            // Auto-register factory — will be replaced with constructor-aware
            // version after discovery if @Inject constructor is found
            if (!factories.containsKey(DotName.of(clazz.getName()))) {
                var lkp = getBuilderLookup();
                factories.put(DotName.of(clazz.getName()), () -> instantiate(clazz, lkp));
            }
        }
    }

    /** The class file of {@code clazz}, empty when its loader has none among its resources. */
    private static byte[] classFileOf(Class<?> clazz) {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
            return is == null ? new byte[0] : is.readAllBytes();
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
        // The index plans the creation of these classes (VAU-PERF-003 step 2), keyed by the Class itself.
        nonBceClasses.forEach(beanClass -> IndexPlans.register(beanClass, index));
        var reflectionErrors = definitionErrors(index, nonBceClasses);
        if (!reflectionErrors.isEmpty()) {
            throw new jakarta.enterprise.inject.spi.DefinitionException(
                    failureMessage(DEFINITION_FAILED, reflectionErrors));
        }
    }

    /**
     * The definition errors of the bean classes: read from the index when it describes a class well enough
     * (VAU-PERF-008), by reflection otherwise, as before.
     */
    private static List<String> definitionErrors(VaubanIndex index, List<Class<?>> beanClasses) {
        var validator = new io.vidocq.vauban.core.bean.validation.IndexClassValidator(index,
                VaubanContainerBuilder::annotationTypeOrNull,
                name -> VaubanBeanManager.customInterceptorBindingTypes().stream()
                        .anyMatch(type -> type.getName().equals(name.value())));
        var errors = new java.util.ArrayList<String>();
        var reflected = new java.util.ArrayList<Class<?>>();
        for (var beanClass : beanClasses) {
            var fromIndex = index.getClassByName(io.vidocq.vauban.indexer.model.DotName.of(beanClass.getName()))
                    .flatMap(validator::validate);
            if (fromIndex.isPresent()) {
                errors.addAll(fromIndex.get());
            } else {
                reflected.add(beanClass);
            }
        }
        errors.addAll(ReflectionValidator.validateWithReflection(reflected));
        return errors;
    }

    private static Class<?> annotationTypeOrNull(String name) {
        try {
            return io.vidocq.vauban.core.loading.DeploymentClasses.load(name);
        } catch (ClassNotFoundException | LinkageError _) {
            return null;
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

    private void configureDiscovery(BeanDiscovery discovery, ExtensionDiscovery extensionDiscovery) {
        discovery.setStrictScannedDiscovery(strictScannedDiscovery);

        // Apply @Discovery results to BeanDiscovery
        if (extensionDiscovery != null) {
            applyExtensionDiscovery(discovery, extensionDiscovery);
        }

        // Force bean classes from bean-discovery-mode=all archives (e.g. from scanBeanArchivesFromClasspath)
        if (!forcedDiscoveryClasses.isEmpty()) {
            discovery.addForcedBeanClasses(dotNames(forcedDiscoveryClasses));
        }
    }

    private void applyExtensionDiscovery(BeanDiscovery discovery, ExtensionDiscovery registered) {
        discovery.setCustomQualifiers(dotNames(registered.qualifiers()));
        discovery.setCustomInterceptorBindings(dotNames(registered.interceptorBindings()));
        discovery.setCustomStereotypes(dotNames(registered.stereotypes()));
        var stereotypeAnns = new java.util.HashMap<DotName, java.util.Set<Class<? extends Annotation>>>();
        for (var entry : registered.stereotypeAnnotations().entrySet()) {
            stereotypeAnns.put(DotName.of(entry.getKey().getName()), entry.getValue());
        }
        discovery.setCustomStereotypeAnnotations(stereotypeAnns);
        // The container's own AnnotationTypes reads them back below, so no static state.
        discovery.setCustomNonbindingMembers(registered.nonbindingMembers());
        VaubanBeanManager.setCustomQualifierTypes(registered.qualifiers());
        VaubanBeanManager.setCustomInterceptorBindingTypes(registered.interceptorBindings());
        VaubanBeanManager.setCustomStereotypeTypes(registered.stereotypes());

        // Classes added via ScannedClasses bypass annotation check
        if (!registered.scannedClasses().isEmpty()) {
            var scannedDotNames = registered.scannedClasses().stream()
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
            List<io.vidocq.vauban.core.extensions.FrozenContexts.Entry> frozenContexts,
            ClassLoader discoveryClassLoader) {
        var contextRegistrations = new LinkedHashMap<String, Map.Entry<Class<? extends Annotation>, Class<?>>>();
        if (discoveryResult != null) {
            for (var reg : discoveryResult.metaAnnotations().getCustomContexts()) {
                contextRegistrations.putIfAbsent(reg.scopeAnnotation().getName() + ";" + reg.contextClass().getName(),
                        java.util.Map.entry(reg.scopeAnnotation(), reg.contextClass()));
            }
        }
        var frozenEntries = new ArrayList<>(frozenContexts);
        frozenEntries.addAll(io.vidocq.vauban.core.extensions.FrozenContexts.read(discoveryClassLoader));
        for (var frozen : frozenEntries) {
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
