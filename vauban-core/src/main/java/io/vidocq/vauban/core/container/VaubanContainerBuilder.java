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
        scanner.scanPackage(packageName);
        return this;
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
        scanner.scanClasspath();
        return this;
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
        scanner.scanBeanArchivesFromClasspath();
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
                var all = new java.util.LinkedHashSet<java.net.URL>();
                for (var loader : loaderList) {
                    all.addAll(java.util.Collections.list(loader.getResources(name)));
                }
                // getResources() = parent.getResources() + findResources(): the
                // parent is loaderList.getFirst(), so drop its results here to
                // avoid duplicates in the concatenated enumeration.
                var parent = getParent();
                if (parent != null) {
                    java.util.Collections.list(parent.getResources(name)).forEach(all::remove);
                }
                return java.util.Collections.enumeration(all);
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
            // VAU-BCE-002: signatures of every synthetic bean registered during this boot,
            // shared between the runtime BCE path and the APT metadata path so identical
            // registrations (duplicated BCE discovery, wrapper subclasses) collapse to one.
            var seenSyntheticSignatures = new java.util.HashSet<String>();

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

                // BCE deployment errors → DeploymentException. The first typed
                // exception a BCE threw becomes the cause (VAU-BCE-003): specs
                // define the exception a failed deployment must surface, and TCKs
                // assert it through the cause chain (@ShouldThrowException).
                if (!bceResult.deploymentErrors().isEmpty()) {
                    var msg = new StringBuilder("CDI deployment validation failed:\n");
                    for (var error : bceResult.deploymentErrors()) {
                        msg.append("  - ").append(error).append("\n");
                    }
                    var failure = new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                    var causes = bceResult.deploymentErrorCauses();
                    if (!causes.isEmpty()) {
                        failure.initCause(causes.getFirst());
                        for (var extra : causes.subList(1, causes.size())) {
                            failure.addSuppressed(extra);
                        }
                    }
                    throw failure;
                }

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
                    var enhancedScope = SyntheticComponentRegistrar.extractEnhancedScope(entry.getValue());
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
                SyntheticComponentRegistrar.loadSyntheticMetadataFromApt(discoveryClassLoader, descriptors, factories,
                        syntheticDisposers, observers, seenSyntheticSignatures);
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

}
