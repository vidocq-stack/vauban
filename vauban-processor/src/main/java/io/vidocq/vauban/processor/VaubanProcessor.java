package io.vidocq.vauban.processor;

import io.vidocq.vauban.processor.apt.ElementScanner;
import io.vidocq.vauban.processor.codegen.GeneratedClass;
import io.vidocq.vauban.processor.codegen.factory.BeanFactoryGenerator;
import io.vidocq.vauban.processor.codegen.proxy.ClientProxyGenerator;
import io.vidocq.vauban.processor.codegen.provider.ComponentProviderGenerator;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedShapeFromElements;
import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.validation.DeploymentValidator;
import io.vidocq.vauban.core.extensions.BceProcessor;
import io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import io.vidocq.vauban.core.extensions.VaubanClassConfig;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.codegen.ComponentCollector;
import io.vidocq.vauban.indexer.codegen.ProvidedClass;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Annotation processor that discovers CDI beans at compile time,
 * runs Build Compatible Extensions (BCE), generates bean factories,
 * client proxies, and a {@code META-INF/vauban-beans.list}.
 *
 * <p>Supports bean enrichment via {@code vauban-apt.properties} and
 * BCE execution ({@code @Discovery}, {@code @Enhancement}, {@code @Registration},
 * {@code @Synthesis}, {@code @Validation}) at compile time.</p>
 *
 * <p>When BCEs are processed, a marker file {@code META-INF/vauban-bce-processed}
 * is written so the runtime container skips re-executing them.</p>
 *
 * <h2>APT options</h2>
 *
 * <ul>
 *   <li>{@code -Avauban.validation=false} — skip the static deployment validation
 *       (UNSATISFIED / AMBIGUOUS / unproxyable / circular dep checks). Useful when
 *       beans rely on injections that only a runtime BCE can satisfy (e.g.
 *       MicroProfile {@code @ConfigProperty}, {@code @Claim}, {@code @RegisterRestClient})
 *       and the BCE isn't on the APT classpath. The runtime container still runs the
 *       full validation at container start; this option only silences the compile-time
 *       check, it doesn't disable wiring.</li>
 *   <li>{@code -Avauban.validation.scope=main|all} — when set to {@code main} (default),
 *       validation only runs on the principal source set and is skipped automatically
 *       when the processor detects it's invoked from a {@code testCompile} (i.e. the
 *       file manager points at {@code target/test-classes} as default output). Set to
 *       {@code all} to enforce validation on test sources too.</li>
 * </ul>
 */
@javax.annotation.processing.SupportedOptions({"vauban.validation", "vauban.validation.scope"})
public class VaubanProcessor extends AbstractProcessor {

    private static final Set<String> CDI_ANNOTATIONS = Set.of(
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.Dependent",
            "jakarta.inject.Singleton",
            "jakarta.enterprise.inject.Produces"
    );

    private static final String BEANS_LIST_PATH = "META-INF/vauban-beans.list";
    private static final String BCE_RUNTIME_LIST_PATH = "META-INF/vauban-bce-runtime.list";

    // Beans accumulate across APT rounds: companion processors (e.g. mansart-data-processor)
    // emit their classes in round N which then surface to roundEnv.getElementsAnnotatedWith
    // in round N+1. We build the full index over all rounds and run the BCE / generation /
    // writeBeansList pipeline once, at processingOver(), so we see every type whatever the
    // round it was discovered in.
    private final IndexBuilder accumulatedIndex = new IndexBuilder();
    private boolean finalized = false;
    private List<Class<?>> discoveredBceClasses;
    private Set<String> bceAnnotationTypes = Set.of();

    /**
     * DotNames of classes added via {@code ScannedClasses.add()} during a BCE @Discovery phase.
     * These classes originate from dependency jars, not from the module being compiled.
     * Generating {@code _Factory} / {@code _ClientProxy} in the user module for such classes
     * would create a split-package violation under JPMS (the package is already exported by the
     * source jar). They are kept in the index and in {@code vauban-beans.list} so injection
     * resolution works, but no bytecode is emitted for them.
     */
    private final Set<DotName> externalClassNames = new LinkedHashSet<>();

    /** Visible for testing — allows injecting BCE classes without ServiceLoader. */
    public List<Class<?>> overrideBceClasses;

    /** Visible for testing — returns the set of external class names after processing. */
    public Set<DotName> getExternalClassNames() {
        return Collections.unmodifiableSet(externalClassNames);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        // Discover BCEs early so their @Enhancement(withAnnotations=...) annotations
        // are included in getSupportedAnnotationTypes(). Without this, classes annotated
        // with e.g. @Path would never be seen by the APT roundEnv.
        var cl = VaubanProcessor.class.getClassLoader();
        discoveredBceClasses = overrideBceClasses != null ? overrideBceClasses : discoverBceClasses(cl);
        bceAnnotationTypes = extractBceAnnotationTypes(discoveredBceClasses);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        var types = new LinkedHashSet<>(CDI_ANNOTATIONS);
        // Trigger annotations derived from BCE @Enhancement(withAnnotations=...)
        // Replaces vauban-apt.properties — the BCE declares its own triggers
        types.addAll(bceAnnotationTypes);
        return Set.copyOf(types);
    }

    /**
     * Extracts annotation class names from BCE @Enhancement(withAnnotations=...) declarations.
     * These must be in getSupportedAnnotationTypes() so the APT roundEnv includes the annotated classes.
     */
    private static Set<String> extractBceAnnotationTypes(List<Class<?>> bceClasses) {
        var types = new LinkedHashSet<String>();
        for (var bceClass : bceClasses) {
            for (var method : bceClass.getDeclaredMethods()) {
                var enhancement = method.getAnnotation(
                        jakarta.enterprise.inject.build.compatible.spi.Enhancement.class);
                if (enhancement == null) continue;
                for (var ann : enhancement.withAnnotations()) {
                    types.add(ann.getName());
                }
            }
        }
        return types;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (finalized) return false;

        // Accumulate types annotated this round into the cross-round index.
        var scanner = new ElementScanner(processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        for (var annotation : annotations) {
            for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof TypeElement typeElement) {
                    var name = DotName.of(typeElement.getQualifiedName().toString());
                    if (!accumulatedIndex.contains(name)) {
                        accumulatedIndex.add(scanner.scan(typeElement));
                    }
                } else if (element.getEnclosingElement() instanceof TypeElement enclosing) {
                    var name = DotName.of(enclosing.getQualifiedName().toString());
                    if (!accumulatedIndex.contains(name)) {
                        accumulatedIndex.add(scanner.scan(enclosing));
                    }
                }
            }
        }

        // Wait for the final round so companion processors (mansart-data-processor and friends)
        // have had a chance to emit their generated CDI classes — without this, beans appearing
        // only in later rounds would be invisible to the deployment validator.
        if (!roundEnv.processingOver()) return false;
        finalized = true;

        var indexBuilder = accumulatedIndex;
        var index = indexBuilder.build();
        if (index.size() == 0) return false;

        // --- BCE: Use extensions discovered in init() ---
        var aptClassLoader = VaubanProcessor.class.getClassLoader();
        var bceClasses = discoveredBceClasses;
        BceProcessor.DiscoveryResult discoveryResult = null;

        if (!bceClasses.isEmpty()) {
            // --- @Discovery phase ---
            var lookup = new IndexLookup(index);
            discoveryResult = BceProcessor.processDiscovery(bceClasses, lookup);

            // Add scanned classes to the index.
            // These classes come from dependency jars (added via ScannedClasses.add()), not from
            // the module being compiled — track them as external so we skip factory/proxy generation.
            for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
                var bytes = loadClassBytes(className, aptClassLoader);
                if (bytes != null) {
                    try {
                        indexBuilder.add(ClassFileScanner.scan(bytes));
                        externalClassNames.add(DotName.of(className));
                    } catch (Exception e) {
                        // Class scan failed — skip
                    }
                }
            }
            index = indexBuilder.build();
        }

        // Run bean discovery
        var discovery = new BeanDiscovery(index);

        // Apply @Discovery meta-annotations to BeanDiscovery
        if (discoveryResult != null) {
            applyDiscoveryResult(discovery, discoveryResult);
        }

        var beans = new ArrayList<>(discovery.discoverBeans());

        if (beans.isEmpty() && bceClasses.isEmpty()) return false;

        // --- BCE: remaining phases (@Enhancement → @Validation) ---
        boolean bceProcessed = false;
        if (!bceClasses.isEmpty()) {
            var archiveClasses = loadArchiveClasses(index, aptClassLoader);
            var observers = discovery.discoverObservers();
            var interceptors = discovery.discoverInterceptors();

            var bceResult = BceProcessor.process(bceClasses, beans,
                    observers, interceptors,
                    index, aptClassLoader,
                    discoveryResult.bceInstances(), archiveClasses);

            // Report BCE errors as compilation errors
            boolean hasErrors = false;
            for (var err : bceResult.definitionErrors()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[Vauban BCE] " + err);
                hasErrors = true;
            }
            for (var err : bceResult.deploymentErrors()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[Vauban BCE] " + err);
                hasErrors = true;
            }

            if (hasErrors) return true;

            // Apply enhancement modifications
            if (!bceResult.enhancementModifications().isEmpty()) {
                var modified = BceProcessor.applyEnhancements(beans, bceResult.enhancementModifications());
                beans.clear();
                beans.addAll(modified);

                // Promote non-beans that gained a scope via Enhancement
                promoteEnhancedClasses(beans, bceResult.enhancementModifications(), index, discovery);

                // Freeze the enhancement *result* (target -> added annotation FQNs) so the
                // runtime applies it WITHOUT re-instantiating the BCE on the module path.
                // This is what lets application modules drop `opens ... to io.vidocq.vauban.core`.
                writeEnhancementsPatch(bceResult.enhancementModifications());

                // Legacy fallback: the (BCE, target) replay list, used by the runtime only
                // when no frozen patch is present for a target (bug #7).
                writeBceRuntimeList(bceResult.enhancementModifications());
            }

            // Serialize synthetic beans/observers for runtime
            if (!bceResult.syntheticBeans().isEmpty() || !bceResult.syntheticObservers().isEmpty()) {
                writeSyntheticMetadata(bceResult.syntheticBeans(), bceResult.syntheticObservers());
            }

            // Make BCE-declared synthetic beans visible to the deployment validator below so user
            // code can @Inject them directly without an Instance<> workaround. Without this,
            // every BCE-supplied bean would force the consuming class to use Instance<T> just to
            // bypass the static check — surprising and opaque.
            int slot = beans.size();
            for (var synBean : bceResult.syntheticBeans()) {
                beans.add(BceProcessor.toBeanDescriptor(synBean, slot++));
            }

            bceProcessed = true;
        }

        // Validate deployment — unless explicitly skipped (cf. APT options on the class javadoc).
        // The runtime container always re-validates on container start; this only silences the
        // compile-time check for projects whose beans depend on BCE-produced injections that
        // aren't visible to the static analyser (typical pattern with @ConfigProperty / @Claim /
        // @RegisterRestClient used in test code without the producing BCE on the APT classpath).
        var errors = java.util.Collections.<DeploymentValidator.ValidationError>emptyList();
        if (validationEnabled()) {
            var assignability = new AssignabilityRules(index);
            var resolver = new BeanResolver(beans, assignability);
            var validator = new DeploymentValidator(beans, resolver);
            errors = validator.validate();
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Static deployment validation skipped (vauban.validation=false). "
                            + "Runtime container will still validate on start.");
        }

        for (var error : errors) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] " + error.message());
        }

        if (!errors.isEmpty()) return true;

        // Generate code for each bean; also accumulate eligible managed classes for the
        // per-package _VaubanComponents provider.
        var providedClasses = new java.util.ArrayList<ProvidedClass>();
        for (var bean : beans) {
            if (bean.kind() == BeanDescriptor.BeanKind.MANAGED) {
                var classInfo = index.getClassByName(bean.beanClass()).orElse(null);
                if (classInfo == null) continue;

                // Skip factory/proxy generation for classes that originate from dependency jars
                // (added via ScannedClasses.add() during @Discovery). Generating artifacts in
                // the user module for such classes would create a JPMS split-package violation
                // because the package is already exported by the source jar.
                // The class remains in vauban-beans.list for injection resolution.
                if (externalClassNames.contains(bean.beanClass())) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "[Vauban] Skipping factory/proxy generation for external class "
                                    + bean.beanClass().value()
                                    + " (factory expected to be provided by the source jar)");
                    continue;
                }

                generateClass(BeanFactoryGenerator.generate(classInfo));

                if (bean.scope().isNormal()) {
                    generateClass(ClientProxyGenerator.generate(classInfo));
                }

                var fqn = bean.beanClass().value();

                // Pre-generate the <bean>$$Intercepted subclass for intercepted targets, so the
                // runtime needs no reflective class definition (hence no `opens … to
                // io.vidocq.vauban.core`) on the strict module path. Mirrors the Maven plugin but
                // builds the shape from javac Elements instead of a loaded Class. Top-level,
                // non-final targets only; anything else falls back to runtime generation.
                boolean interceptedGenerated = false;
                if (isTopLevelType(fqn)) {
                    var typeElement = processingEnv.getElementUtils().getTypeElement(fqn);
                    if (typeElement != null && isInterceptedTarget(typeElement)) {
                        try {
                            var shape = InterceptedShapeFromElements.from(typeElement,
                                    processingEnv.getElementUtils(), processingEnv.getTypeUtils());
                            var bytecode = io.vidocq.vauban.core.interceptor.InterceptedEmitter.emit(shape);
                            generateClass(new GeneratedClass(fqn + "$$Intercepted", bytecode));
                            interceptedGenerated = true;
                        } catch (Exception e) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                    "[Vauban] Could not pre-generate " + fqn + "$$Intercepted"
                                            + " (the runtime will generate it): " + e.getMessage());
                        }
                    }
                }

                // Accumulate for the provider: only top-level types get an in-module entry. The
                // instantiable flag lets ComponentCollector attempt constructor-param extraction;
                // the intercepted flag makes it also emit a component for the $$Intercepted subclass
                // generated just above (instantiated in-module by the bytecode provider, no opens).
                providedClasses.add(new ProvidedClass(
                        fqn, classInfo, isTopLevelType(fqn), interceptedGenerated));
            }
        }

        // Delegate extraction + per-package grouping to ComponentCollector.
        // It performs instantiableCtorParams, collectFields, collectMethods and groups by package.
        var collectorWarnings = new java.util.ArrayList<String>();
        var packages = ComponentCollector.collect(providedClasses, collectorWarnings);
        for (var w : collectorWarnings) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[Vauban] " + w);
        }

        // Emit ONE _VaubanComponents per package that has beans (co-located so each can reach its
        // own package's package-private members with a plain new/putfield/invoke), and list them all
        // in the class-path service file. A single common-package provider could not reach
        // package-private members in other packages.
        var providerClassNames = new java.util.ArrayList<String>();
        for (var pkg : packages) {
            var className = writeComponentProvider(
                    pkg.packageName(), pkg.components(), pkg.fields(), pkg.methods());
            if (className != null) providerClassNames.add(className);
        }
        if (!providerClassNames.isEmpty()) {
            writeComponentProviderService(providerClassNames);
        }

        // Write META-INF/vauban-beans.list
        writeBeansList(beans);

        // Write BCE processed marker
        if (bceProcessed) {
            writeBceProcessedMarker();
        }

        return true;
    }

    // --- APT options ---

    /**
     * Returns {@code true} iff the static deployment validation must run for this APT invocation.
     * <p>Honours two APT options (cf. javadoc on this class):
     * <ul>
     *   <li>{@code vauban.validation=false} unconditionally disables validation.</li>
     *   <li>{@code vauban.validation.scope=main} (default) auto-disables validation when the
     *       APT invocation targets a test source set (typically {@code target/test-classes}).</li>
     * </ul>
     */
    private boolean validationEnabled() {
        var opts = processingEnv.getOptions();
        var explicit = opts.get("vauban.validation");
        if (explicit != null && (explicit.equalsIgnoreCase("false") || explicit.equalsIgnoreCase("no"))) {
            return false;
        }
        var scope = opts.getOrDefault("vauban.validation.scope", "main");
        if ("all".equalsIgnoreCase(scope)) return true;
        // scope=main → skip if invoked from testCompile (output dir ends with /test-classes)
        try {
            var out = processingEnv.getFiler()
                    .getResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", "vauban-probe.tmp")
                    .toUri().toString();
            return !out.contains("/test-classes/");
        } catch (Exception e) {
            // Filer probe failed — default to running validation (safest).
            return true;
        }
    }

    // --- BCE Discovery ---

    private List<Class<?>> discoverBceClasses(ClassLoader cl) {
        var bceClasses = new ArrayList<Class<?>>();
        try {
            ServiceLoader.load(BuildCompatibleExtension.class, cl)
                    .forEach(ext -> bceClasses.add(ext.getClass()));
        } catch (Exception e) {
            // ServiceLoader failed — no BCEs
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] BCE discovery via ServiceLoader: " + e.getMessage());
        }
        if (!bceClasses.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Discovered " + bceClasses.size() + " Build Compatible Extension(s)");
        }
        return bceClasses;
    }

    private void applyDiscoveryResult(BeanDiscovery discovery, BceProcessor.DiscoveryResult discoveryResult) {
        var meta = discoveryResult.metaAnnotations();
        discovery.setCustomQualifiers(
                meta.getCustomQualifiers().stream()
                        .map(c -> DotName.of(c.getName()))
                        .collect(Collectors.toSet()));
        discovery.setCustomInterceptorBindings(
                meta.getCustomInterceptorBindings().stream()
                        .map(c -> DotName.of(c.getName()))
                        .collect(Collectors.toSet()));
        discovery.setCustomStereotypes(
                meta.getCustomStereotypes().stream()
                        .map(c -> DotName.of(c.getName()))
                        .collect(Collectors.toSet()));

        var stereotypeAnns = new HashMap<DotName, Set<Class<? extends java.lang.annotation.Annotation>>>();
        for (var entry : meta.getStereotypeAnnotations().entrySet()) {
            stereotypeAnns.put(DotName.of(entry.getKey().getName()), entry.getValue());
        }
        discovery.setCustomStereotypeAnnotations(stereotypeAnns);
        discovery.setCustomNonbindingMembers(meta.getNonbindingMembersPerQualifier());

        if (!discoveryResult.scannedClasses().getAddedClasses().isEmpty()) {
            var scannedDotNames = discoveryResult.scannedClasses().getAddedClasses().stream()
                    .map(DotName::of)
                    .collect(Collectors.toSet());
            discovery.setForcedBeanClasses(scannedDotNames);
        }
    }

    private void promoteEnhancedClasses(List<BeanDescriptor> beans,
                                         Map<DotName, List<VaubanClassConfig>> modifications,
                                         io.vidocq.vauban.indexer.VaubanIndex index,
                                         BeanDiscovery discovery) {
        var existingBeanClasses = beans.stream()
                .map(BeanDescriptor::beanClass)
                .collect(Collectors.toSet());

        for (var entry : modifications.entrySet()) {
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
            beans.add(newBean);
        }
    }

    private static ScopeInfo extractEnhancedScope(List<VaubanClassConfig> configs) {
        for (var config : configs) {
            for (var ann : config.getAddedAnnotations()) {
                if (ann.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                    return new ScopeInfo(DotName.of(ann.getName()), true);
                }
                if (ann.isAnnotationPresent(jakarta.inject.Scope.class)) {
                    return new ScopeInfo(DotName.of(ann.getName()), false);
                }
                String name = ann.getName();
                if (name.equals("jakarta.enterprise.context.RequestScoped")
                        || name.equals("jakarta.enterprise.context.ApplicationScoped")
                        || name.equals("jakarta.enterprise.context.SessionScoped")
                        || name.equals("jakarta.enterprise.context.ConversationScoped")) {
                    return new ScopeInfo(DotName.of(name), true);
                }
                if (name.equals("jakarta.enterprise.context.Dependent")) {
                    return ScopeInfo.DEPENDENT;
                }
                if (name.equals("jakarta.inject.Singleton")) {
                    return ScopeInfo.SINGLETON;
                }
            }
        }
        return null;
    }

    // --- Utility methods ---

    private List<Class<?>> loadArchiveClasses(io.vidocq.vauban.indexer.VaubanIndex index, ClassLoader cl) {
        var classes = new ArrayList<Class<?>>();
        for (var classInfo : index.getKnownClasses()) {
            try {
                classes.add(Class.forName(classInfo.name().value(), false, cl));
            } catch (ClassNotFoundException ignored) {
                // Class not on processor classpath — skip
            }
        }
        return classes;
    }

    private byte[] loadClassBytes(String className, ClassLoader cl) {
        var resourceName = className.replace('.', '/') + ".class";
        try (var is = cl.getResourceAsStream(resourceName)) {
            return is != null ? is.readAllBytes() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // --- File writing ---

    private void writeBeansList(List<BeanDescriptor> beans) {
        var beanClassNames = beans.stream()
                .map(BeanDescriptor::beanClass)
                .map(DotName::value)
                .distinct()
                .sorted()
                .toList();

        if (beanClassNames.isEmpty()) return;

        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", BEANS_LIST_PATH);
            try (var writer = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                writer.println("# Vauban discovered beans — generated at compile time by APT");
                for (var className : beanClassNames) {
                    writer.println(className);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write " + BEANS_LIST_PATH + ": " + e.getMessage());
        }
    }

    private void writeBceRuntimeList(Map<DotName, List<VaubanClassConfig>> modifications) {
        var entries = new TreeSet<String>();
        for (var entry : modifications.entrySet()) {
            var targetFqn = entry.getKey().value();
            for (var config : entry.getValue()) {
                if (!config.isModified()) continue;
                var bce = config.getSourceBce();
                if (bce == null) continue;
                entries.add(bce.getName() + ";" + targetFqn);
            }
        }
        if (entries.isEmpty()) return;

        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", BCE_RUNTIME_LIST_PATH);
            try (var writer = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                writer.println("# Vauban BCE runtime replay list — generated at compile time by APT");
                for (var line : entries) {
                    writer.println(line);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write " + BCE_RUNTIME_LIST_PATH + ": " + e.getMessage());
        }
    }

    /**
     * Freezes the {@code @Enhancement} result as a {@code target FQN -> added annotation FQNs}
     * patch ({@link EnhancementPatchSerializer#PATCH_PATH}). The runtime applies this patch
     * directly, so it never re-instantiates the BCE — removing the deep-reflection that forced
     * {@code opens ... to io.vidocq.vauban.core} on the module path.
     *
     * <p>Only added annotations expressed as a type are frozen (the runtime applies them as
     * member-less annotations, matching {@code VaubanClassConfig.getAddedAnnotations()}).
     * Annotations added with members fall back to the legacy replay list.
     */
    private void writeEnhancementsPatch(Map<DotName, List<VaubanClassConfig>> modifications) {
        var patch = new java.util.TreeMap<String, List<String>>();
        for (var entry : modifications.entrySet()) {
            var added = new java.util.LinkedHashSet<String>();
            for (var config : entry.getValue()) {
                if (!config.isModified()) continue;
                for (var ann : config.getAddedAnnotations()) {
                    added.add(ann.getName());
                }
            }
            if (!added.isEmpty()) {
                patch.put(entry.getKey().value(), List.copyOf(added));
            }
        }
        if (patch.isEmpty()) return;

        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "",
                    io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH);
            try (var os = resource.openOutputStream()) {
                io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.write(patch, os);
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Froze enhancement patch for " + patch.size() + " class(es)");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write enhancement patch: " + e.getMessage());
        }
    }

    private void writeSyntheticMetadata(
            List<io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?>> syntheticBeans,
            List<io.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder<?>> syntheticObservers) {
        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", SyntheticMetadataSerializer.METADATA_PATH);
            try (var os = resource.openOutputStream()) {
                SyntheticMetadataSerializer.write(syntheticBeans, syntheticObservers, os);
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Serialized " + syntheticBeans.size() + " synthetic bean(s), "
                            + syntheticObservers.size() + " synthetic observer(s)");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write synthetic metadata: " + e.getMessage());
        }
    }

    private void writeBceProcessedMarker() {
        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", SyntheticMetadataSerializer.BCE_PROCESSED_MARKER);
            try (var os = resource.openOutputStream()) {
                os.write("# BCE phases executed at compile time by VaubanProcessor\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write BCE marker: " + e.getMessage());
        }
    }

    /**
     * A managed bean is an interception TARGET (needs a {@code $$Intercepted} subclass) when it is
     * non-final and carries an interceptor binding — at the class level (including {@code @Inherited}
     * bindings from supertypes, via {@code getAllAnnotationMirrors}) or on any instance method.
     *
     * <p>Detection uses the javac {@code Elements} API rather than the indexer model: a custom
     * binding annotation defined in (or brought into) the module isn't necessarily in the Vauban
     * index, but its meta-{@code @InterceptorBinding} is always resolvable from the compiler symbol
     * table. This also covers method-level bindings, which the Maven plugin (class-level only) misses.
     */
    private boolean isInterceptedTarget(TypeElement beanElement) {
        if (beanElement.getModifiers().contains(Modifier.FINAL)) return false;
        for (var am : processingEnv.getElementUtils().getAllAnnotationMirrors(beanElement)) {
            if (isInterceptorBinding(am)) return true;
        }
        for (var enclosed : beanElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (enclosed.getModifiers().contains(Modifier.STATIC)
                    || enclosed.getModifiers().contains(Modifier.PRIVATE)) continue;
            for (var am : enclosed.getAnnotationMirrors()) {
                if (isInterceptorBinding(am)) return true;
            }
        }
        return false;
    }

    /** An interceptor binding is an annotation type itself meta-annotated {@code @InterceptorBinding}. */
    private static boolean isInterceptorBinding(javax.lang.model.element.AnnotationMirror am) {
        return am.getAnnotationType().asElement()
                .getAnnotation(jakarta.interceptor.InterceptorBinding.class) != null;
    }

    /**
     * Only top-level types get an in-module provider entry. A nested/member type's canonical name
     * is dot-separated in APT mode (e.g. {@code a.b.Outer.Inner}), which is indistinguishable from
     * a package boundary by string analysis and would make the generator emit {@code _VaubanComponents}
     * into a bogus package. Nested beans (mostly test fixtures) fall back to reflective instantiation.
     * When the element is not resolvable (bytecode-only deps), fall back to the {@code $} marker.
     */
    private boolean isTopLevelType(String fqn) {
        var te = processingEnv.getElementUtils().getTypeElement(fqn);
        if (te != null) {
            return te.getNestingKind() == javax.lang.model.element.NestingKind.TOP_LEVEL;
        }
        return !fqn.contains("$");
    }

    /**
     * Generates the {@code <pkg>._VaubanComponents} provider for one package's beans (in-module
     * instantiation, field injection and method invocation) and returns its fully-qualified name so
     * the caller can list it (with the others) in the single class-path service file. The fields and
     * methods passed in already belong to {@code pkg} (the caller groups by package), so no filtering
     * is needed — a co-located provider can reach its own package's package-private members.
     *
     * <p>Emission is conditional. A package that contains an intercepted bean has a
     * {@code <bean>$$Intercepted} component, which only the <strong>bytecode</strong> generator can
     * reference (it uses binary names resolved by the JVM, whereas a generated <em>source</em>
     * provider cannot resolve a Filer-emitted {@code $$Intercepted} symbol — "cannot find symbol").
     * Such packages are emitted as bytecode via the Class-File API; all others keep the readable
     * source form.
     */
    private String writeComponentProvider(String pkg,
            List<io.vidocq.vauban.indexer.codegen.Component> components,
            List<io.vidocq.vauban.indexer.codegen.FieldInject> fieldInjects,
            List<io.vidocq.vauban.indexer.codegen.MethodInvoke> methodInvokes) {
        boolean hasIntercepted = components.stream()
                .anyMatch(c -> c.fqn().endsWith("$$Intercepted"));
        if (hasIntercepted) {
            return writeComponentProviderBytecode(pkg, components, fieldInjects, methodInvokes);
        }
        var gen = ComponentProviderGenerator.generateFrom(pkg, components, fieldInjects, methodInvokes);
        try {
            var file = processingEnv.getFiler().createSourceFile(gen.className());
            try (var w = file.openWriter()) {
                w.write(gen.source());
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Generated " + gen.className() + " (" + components.size() + " component(s), "
                            + fieldInjects.size() + " field(s), " + methodInvokes.size() + " method(s)). "
                            + "On the module path, add to module-info: provides "
                            + "io.vidocq.vauban.api.VaubanComponentProvider with " + gen.className() + ";");
            return gen.className();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write component provider: " + e.getMessage());
            return null;
        }
    }

    /**
     * Bytecode variant of {@link #writeComponentProvider}: emits {@code _VaubanComponents} as a
     * {@code .class} via the shared {@code ComponentProviderClassGenerator} (Class-File API) and the
     * Filer. Used for packages with an intercepted bean, whose {@code $$Intercepted} component a
     * generated source provider could not reference.
     */
    private String writeComponentProviderBytecode(String pkg,
            List<io.vidocq.vauban.indexer.codegen.Component> components,
            List<io.vidocq.vauban.indexer.codegen.FieldInject> fieldInjects,
            List<io.vidocq.vauban.indexer.codegen.MethodInvoke> methodInvokes) {
        var providerFqn = pkg.isEmpty()
                ? ComponentProviderGenerator.SIMPLE_NAME
                : pkg + "." + ComponentProviderGenerator.SIMPLE_NAME;
        var gen = io.vidocq.vauban.core.provider.ComponentProviderClassGenerator.generate(
                providerFqn, components, fieldInjects, methodInvokes);
        generateClass(new GeneratedClass(gen.className(), gen.bytecode()));
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "[Vauban] Generated " + gen.className() + " (bytecode; " + components.size()
                        + " component(s), " + fieldInjects.size() + " field(s), "
                        + methodInvokes.size() + " method(s)). On the module path, add to module-info: "
                        + "provides io.vidocq.vauban.api.VaubanComponentProvider with " + gen.className() + ";");
        return gen.className();
    }

    /** Class-path fallback registration (ignored for named modules, which use {@code provides}). */
    private void writeComponentProviderService(List<String> providerClassNames) {
        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider");
            try (var w = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                for (var name : providerClassNames) w.println(name);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write component provider service file: " + e.getMessage());
        }
    }

    private void generateClass(GeneratedClass generated) {
        try {
            var filer = processingEnv.getFiler();
            var fileObject = filer.createClassFile(generated.className());
            try (var os = fileObject.openOutputStream()) {
                os.write(generated.bytecode());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] Failed to write generated class " + generated.className() + ": " + e.getMessage());
        }
    }
}
