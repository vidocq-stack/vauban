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
package io.vidocq.vauban.processor;

import io.vidocq.vauban.api.ExtensionPhase;
import io.vidocq.vauban.processor.apt.ElementScanner;
import io.vidocq.vauban.processor.codegen.proxy.ClientProxyShapeFromElements;
import io.vidocq.vauban.processor.codegen.provider.ComponentProviderGenerator;
import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.validation.DeploymentValidator;
import io.vidocq.vauban.core.extensions.BceProcessor;
import io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import io.vidocq.vauban.core.extensions.VaubanClassConfig;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.codegen.ProvidedClass;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
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
 *   <li>{@code -Avauban.providesHint=false} — the note on each generated
 *       {@code _VaubanComponents} no longer tells the module to declare it with
 *       {@code provides}. For a runtime that turns the module's {@code META-INF/services}
 *       entries into {@code provides} when it loads the module, as Vidocq does, where the
 *       declaration is not needed (VAU-APT-004).</li>
 * </ul>
 */
@javax.annotation.processing.SupportedOptions({"vauban.validation", "vauban.validation.scope",
        "vauban.producerProxy", "vauban.providesHint", "vauban.generationRound"})
public class VaubanProcessor extends AbstractProcessor {

    private static final Set<String> CDI_ANNOTATIONS = Set.of(
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.Dependent",
            "jakarta.inject.Singleton",
            "jakarta.enterprise.inject.Produces",
            // @Interceptor classes are bean-defining too (BeanDiscovery treats them as @Dependent
            // managed beans). Including them here brings them into the APT round/index so they get a
            // _VaubanComponents arm — the container then instantiates and field-injects
            // them in-module on the module path, with no `opens … to io.vidocq.vauban.core` (their
            // public @AroundInvoke method is reachable without opens via the F3 public-member guard).
            "jakarta.interceptor.Interceptor"
    );

    /** The meta-annotations that make an annotation bean-defining: stereotypes and scopes. */
    private static final Set<String> BEAN_DEFINING_META_ANNOTATIONS = Set.of(
            "jakarta.enterprise.inject.Stereotype",
            "jakarta.enterprise.context.NormalScope",
            "jakarta.inject.Scope");

    private static final String BEANS_LIST_PATH = "META-INF/vauban-beans.list";
    private static final String REQUIRED_OPENS_PATH = "META-INF/vauban/required-opens.list";
    private static final String BCE_RUNTIME_LIST_PATH = "META-INF/vauban-bce-runtime.list";
    private static final String LOG_PREFIX = "[Vauban] ";
    private static final String BCE_PREFIX = "[Vauban BCE] ";
    private static final String BCE_SERVICE_PATH = "META-INF/services/" + BuildCompatibleExtension.class.getName();
    private static final String FAILED_TO_WRITE = "[Vauban] Failed to write ";
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    // Beans accumulate across APT rounds: companion processors (e.g. mansart-data-processor)
    // emit their classes in round N which then surface to roundEnv.getElementsAnnotatedWith
    // in round N+1. We build the full index over all rounds and run the BCE / generation /
    // writeBeansList pipeline once, at processingOver(), so we see every type whatever the
    // round it was discovered in.
    private final IndexBuilder accumulatedIndex = new IndexBuilder();
    private boolean finalized = false;
    /** The APT rounds seen so far. */
    private int round;
    /** The types the current round added to {@link #accumulatedIndex}. */
    private final List<TypeElement> indexedThisRound = new ArrayList<>();
    /** Whether the pipeline ran before javac's last round, so that a later round may still bring a bean. */
    private boolean generatedEarly;
    /** The late beans already reported, once each whatever the number of their annotations. */
    private final Set<String> reportedLateBeans = new HashSet<>();
    private List<Class<?>> discoveredBceClasses;

    /**
     * BCE implementations declared on the compile/module path that the processor-path
     * {@code ServiceLoader} could not load — {@code null} until first computed (lazy: needs the
     * round's {@code Elements}). See {@link #unloadableBcesOnCompilePath()} (vauban#29).
     */
    private Set<String> unloadableBcesCache;
    private boolean warnedUnloadableBces;

    /** Named modules this compilation is building — never complete their directives. */
    private final Set<String> compiledModuleNames = new HashSet<>();
    private Set<String> bceAnnotationTypes = Set.of();

    /**
     * DotNames of classes added via {@code ScannedClasses.add()} during a BCE @Discovery phase.
     * These classes originate from dependency jars, not from the module being compiled.
     * Generating a {@code _ClientProxy} in the user module for such classes
     * would create a split-package violation under Java Modules (the package is already exported by the
     * source jar). They are kept in the index and in {@code vauban-beans.list} so injection
     * resolution works, but no bytecode is emitted for them.
     */
    private final Set<DotName> externalClassNames = new LinkedHashSet<>();

    /**
     * Beans of the bean archive modules this compilation depends on, found while resolving the types it injects
     * (VAU-APT-006). They only take part in resolution: never validated, generated or listed here, since
     * their own module ships and validates them.
     */
    private final List<BeanDescriptor> archiveBeans = new ArrayList<>();

    /** The service every Vauban bean archive provides, one implementation per package of beans. */
    private static final String COMPONENT_PROVIDER = "io.vidocq.vauban.api.VaubanComponentProvider";

    private List<Class<?>> overrideBceClasses;

    /** Visible for testing — injects BCE classes without ServiceLoader. */
    public void overrideBceClasses(List<Class<?>> bceClasses) {
        this.overrideBceClasses = bceClasses;
    }

    private ClassLoader bceClassLoader;

    /** Visible for testing — the class loader whose {@code ServiceLoader} discovers the BCEs, in place of the processor's. */
    public void overrideBceClassLoader(ClassLoader classLoader) {
        this.bceClassLoader = classLoader;
    }

    /** Visible for testing — returns the set of external class names after processing. */
    public Set<DotName> getExternalClassNames() {
        return Collections.unmodifiableSet(externalClassNames);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /** The processed types, found by the names the index carries. */
    private TypeLookup typeLookup;
    /** Where the generated classes go, and the record of their names. */
    private GeneratedOutput output;
    /** The generated artefacts, one class each. */
    private InterceptedArtefacts intercepted;
    private ClientProxyArtefacts clientProxies;
    private ComponentProviderArtefacts componentProviders;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        // The artefacts share one output: the classes one emits as bytecode, the providers instantiate through
        // their lookup (BUG-20261004-09).
        output = new GeneratedOutput(processingEnv);
        typeLookup = new TypeLookup(processingEnv);
        intercepted = new InterceptedArtefacts(processingEnv, output, typeLookup);
        clientProxies = new ClientProxyArtefacts(processingEnv, output);
        componentProviders = new ComponentProviderArtefacts(processingEnv, output, typeLookup, this::isTestRound);
        // Discover BCEs early so their @Enhancement(withAnnotations=...) annotations
        // are included in getSupportedAnnotationTypes(). Without this, classes annotated
        // with e.g. @Path would never be seen by the APT roundEnv.
        var cl = bceClassLoader != null ? bceClassLoader : VaubanProcessor.class.getClassLoader();
        discoveredBceClasses = overrideBceClasses != null ? overrideBceClasses : discoverBceClasses(cl);
        bceAnnotationTypes = extractBceAnnotationTypes(discoveredBceClasses);
    }

    /**
     * Every annotation: whether an annotation makes a class a bean can depend on the annotation's own
     * meta-annotations — an application stereotype or a custom scope, which no fixed list can name
     * (BUG-20261008-04). {@link #isIndexTrigger} picks the ones that matter. The processor never claims
     * an annotation, so other processors still see them all.
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    /**
     * Whether a class carrying {@code annotation} enters the index: a CDI annotation of the fixed
     * list, a trigger an extension declares in {@code @Enhancement(withAnnotations=...)}, or an
     * annotation that is a stereotype or a scope (CDI 4.1 §2.5.1: those are bean-defining).
     */
    private boolean isIndexTrigger(TypeElement annotation) {
        var name = annotation.getQualifiedName().toString();
        if (CDI_ANNOTATIONS.contains(name) || bceAnnotationTypes.contains(name)) {
            return true;
        }
        for (var meta : annotation.getAnnotationMirrors()) {
            var metaName = ((TypeElement) meta.getAnnotationType().asElement()).getQualifiedName().toString();
            if (BEAN_DEFINING_META_ANNOTATIONS.contains(metaName)) {
                return true;
            }
        }
        return false;
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
        round++;
        if (finalized) {
            if (generatedEarly) {
                reportLateBeans(annotations, roundEnv);
            }
            return false;
        }

        recordCompiledModules(roundEnv);

        // Accumulate types annotated this round into the cross-round index.
        var scanner = new ElementScanner(processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        indexedThisRound.clear();
        for (var annotation : annotations) {
            if (isIndexTrigger(annotation)) {
                indexAnnotatedTypes(roundEnv.getElementsAnnotatedWith(annotation), scanner);
            }
        }

        // Companion processors (mansart-data-processor, cassini-processor and friends) emit their classes in the
        // first round; they surface here in the second. The pipeline waits for them, then runs in the first round
        // from the second on whose new classes brought no bean: before javac's last round, where every file written
        // costs a "created in the last round will not be subject to annotation processing" warning
        // (BUG-20261030-26 of vidocq). A round whose new classes only carry annotations no companion acts on, such
        // as Mansart Data's repository implementations (@Generated, @Singleton, @Inject), counts as quiet too: no
        // further class can come from them (VAU-APT-007). Without a second round, or with
        // -Avauban.generationRound=last, it runs in the last round, as it always did.
        boolean quietRound = round >= 2 && accumulatedIndex.size() > 0
                && indexedThisRound.stream().allMatch(this::carriesOnlyInertAnnotations);
        if (!roundEnv.processingOver()
                && !(quietRound && earlyGeneration() && !foreignInheritedAnnotationIndexed())) return false;
        finalized = true;
        generatedEarly = !roundEnv.processingOver();
        try (var _ = BceLogBridge.install(processingEnv.getMessager(), BCE_PREFIX)) {
            return processDeployment(scanner);
        }
    }

    /**
     * Whether an indexed class carries, itself or through a superclass, an {@code @Inherited} annotation other than
     * a scope, a stereotype or {@code @Vetoed}. The client proxies and intercepted subclasses inherit it, and written
     * before the last round, they would reach the next round's processors as classes carrying it: Dirac's for
     * {@code @Counted}, Heisenberg's for {@code @Retry} would generate companions for them.
     */
    private boolean foreignInheritedAnnotationIndexed() {
        var elements = processingEnv.getElementUtils();
        for (var classInfo : accumulatedIndex.build().getKnownClasses()) {
            var name = classInfo.name();
            var type = elements.getTypeElement(name.toString());
            if (type == null) {
                continue;
            }
            for (var mirror : elements.getAllAnnotationMirrors(type)) {
                var annotation = (TypeElement) mirror.getAnnotationType().asElement();
                if (annotation.getAnnotation(java.lang.annotation.Inherited.class) != null
                        && !isCdiOwnedAnnotation(annotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The packages of the annotations no companion processor acts on, see {@link #carriesOnlyInertAnnotations}:
     * the markers processors put on what they generate ({@code @Generated}, {@code @StaticMetamodel}) among them.
     */
    private static final Set<String> INERT_ANNOTATION_PACKAGES = Set.of("java.lang", "javax.annotation.processing",
            "jakarta.annotation", "jakarta.inject", "jakarta.enterprise.context", "jakarta.enterprise.inject",
            "jakarta.persistence.metamodel", "jakarta.data.metamodel");

    /**
     * Packages of {@code @Inherited} annotations known to start no processor on Vauban's subclasses: JAX-RS's
     * {@code @Produces} and {@code @Consumes}, MicroProfile OpenAPI's {@code @Tag} and friends. Cassini's and Grimm's
     * processors start from {@code @Path}, which is not inherited.
     */
    private static final Set<String> INHERITED_SAFE_PACKAGES = Set.of("jakarta.ws.rs",
            "org.eclipse.microprofile.openapi.annotations", "org.eclipse.microprofile.openapi.annotations.tags",
            "org.eclipse.microprofile.openapi.annotations.media", "org.eclipse.microprofile.openapi.annotations.responses",
            "org.eclipse.microprofile.openapi.annotations.parameters", "org.eclipse.microprofile.openapi.annotations.security");

    /**
     * A scope, a stereotype or {@code @Vetoed}, which only the CDI container reads, or an annotation of
     * {@link #INHERITED_SAFE_PACKAGES}.
     */
    private boolean isCdiOwnedAnnotation(TypeElement annotation) {
        if ("jakarta.enterprise.inject.Vetoed".contentEquals(annotation.getQualifiedName())
                || INHERITED_SAFE_PACKAGES.contains(
                        processingEnv.getElementUtils().getPackageOf(annotation).getQualifiedName().toString())) {
            return true;
        }
        for (var meta : annotation.getAnnotationMirrors()) {
            var metaName = ((TypeElement) meta.getAnnotationType().asElement()).getQualifiedName().toString();
            if (BEAN_DEFINING_META_ANNOTATIONS.contains(metaName)) {
                return true;
            }
        }
        return false;
    }

    /** {@code -Avauban.generationRound}: {@code early} (the default) or {@code last}. */
    private boolean earlyGeneration() {
        return !"last".equalsIgnoreCase(processingEnv.getOptions().getOrDefault("vauban.generationRound", "early")
                .strip());
    }

    /**
     * A bean that another processor generated after the pipeline ran: the provider written before it cannot list
     * it, so the build fails by name rather than the application at boot. Vauban's own classes, which inherit
     * their bean's scope, are not beans of their own.
     */
    private void reportLateBeans(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (var annotation : annotations) {
            if (isIndexTrigger(annotation)) {
                roundEnv.getElementsAnnotatedWith(annotation).forEach(this::reportIfLateBean);
            }
        }
    }

    /** Reports the type of {@code element}, or the type enclosing it, when it is a bean Vauban did not list. */
    private void reportIfLateBean(Element element) {
        TypeElement type = typeOf(element);
        if (type == null) {
            return;
        }
        var name = type.getQualifiedName().toString();
        boolean known = accumulatedIndex.contains(DotName.of(name)) || output.wrote(name)
                || type.getAnnotation(jakarta.enterprise.inject.Vetoed.class) != null;
        if (!known && reportedLateBeans.add(name)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, LOG_PREFIX + name
                    + " was generated by another annotation processor after Vauban wrote its classes, which"
                    + " therefore do not list it. Compile with -Avauban.generationRound=last to have Vauban"
                    + " wait for javac's last round.", type);
        }
    }

    /** {@code element} itself when it is a type, else the type that encloses it directly, else {@code null}. */
    private static TypeElement typeOf(Element element) {
        if (element instanceof TypeElement type) {
            return type;
        }
        return element.getEnclosingElement() instanceof TypeElement enclosing ? enclosing : null;
    }

    /**
     * Remembers which module(s) this compilation is building: reading the module <em>directives</em>
     * of a module still being compiled (as unloadableBcesOnCompilePath does for dependency
     * modules) completes it prematurely and freezes {@code provides} resolution errors before the
     * last-round generated classes exist. getModuleOf only walks ownership — no completion.
     */
    private void recordCompiledModules(RoundEnvironment roundEnv) {
        for (var root : roundEnv.getRootElements()) {
            var module = processingEnv.getElementUtils().getModuleOf(root);
            if (module != null && !module.isUnnamed()) {
                compiledModuleNames.add(module.getQualifiedName().toString());
            }
        }
    }

    /** Adds to the cross-round index each annotated type, or the type enclosing an annotated member. */
    private void indexAnnotatedTypes(Set<? extends Element> annotated, ElementScanner scanner) {
        for (var element : annotated) {
            if (element instanceof TypeElement typeElement) {
                indexOnce(typeElement, scanner);
            } else if (element.getEnclosingElement() instanceof TypeElement enclosing) {
                indexOnce(enclosing, scanner);
            }
        }
    }

    private void indexOnce(TypeElement typeElement, ElementScanner scanner) {
        var name = DotName.of(typeElement.getQualifiedName().toString());
        if (!accumulatedIndex.contains(name)) {
            accumulatedIndex.add(scanner.scan(typeElement));
            indexedThisRound.add(typeElement);
        }
    }

    /**
     * Whether {@code type}, its members and their parameters only carry annotations no companion processor acts
     * on: CDI's and {@code jakarta.inject}'s, {@code @Generated}, {@code @Priority}, the static metamodel markers
     * and {@code java.lang}'s. Such a class, generated by another processor, cannot make one generate more.
     */
    private boolean carriesOnlyInertAnnotations(TypeElement type) {
        var elements = new ArrayList<Element>();
        elements.add(type);
        for (var member : type.getEnclosedElements()) {
            elements.add(member);
            if (member instanceof javax.lang.model.element.ExecutableElement executable) {
                elements.addAll(executable.getParameters());
            }
        }
        var utils = processingEnv.getElementUtils();
        for (var element : elements) {
            for (var mirror : element.getAnnotationMirrors()) {
                var annotation = (TypeElement) mirror.getAnnotationType().asElement();
                if (!INERT_ANNOTATION_PACKAGES.contains(utils.getPackageOf(annotation).getQualifiedName().toString())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The pipeline run once, in the final round: the BCE phases, the deployment validation, the
     * generation of every artefact and the indexes written beside them.
     *
     * @return what {@link #process} returns
     */
    private boolean processDeployment(ElementScanner scanner) {
        var indexBuilder = accumulatedIndex;
        // The annotation types the indexed classes name — a qualifier, a stereotype or an interceptor
        // binding this compilation declares is on no class path yet, so discovery cannot fall back on
        // Class.forName for it, and would ignore it on both sides (BUG-20260914-13).
        indexAnnotationTypes(indexBuilder, scanner);
        var index = indexBuilder.build();
        if (index.size() == 0) return false;

        // --- BCE: Use extensions discovered in init() ---
        var aptClassLoader = VaubanProcessor.class.getClassLoader();
        var bceClasses = discoveredBceClasses;
        BceProcessor.DiscoveryResult discoveryResult = null;

        if (!bceClasses.isEmpty()) {
            discoveryResult = runDiscoveryPhase(bceClasses, index, indexBuilder, aptClassLoader);
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
        boolean bceProcessed = !bceClasses.isEmpty();
        if (bceProcessed && !runRemainingBcePhases(bceClasses, beans, discovery,
                Objects.requireNonNull(discoveryResult), index, aptClassLoader)) {
            return true;
        }

        // Injection points may target beans that live in a dependency. Such a type is absent from
        // this compilation's index — but javac can see it, so resolve it lazily rather than
        // rejecting a deployment the runtime container resolves without trouble (vauban#23).
        var dependencyTypes = resolveDependencyBeans(beans, indexBuilder, scanner, discoveryResult);
        if (dependencyTypes.indexGrew()) {
            index = indexBuilder.build();
        }

        warnUnloadableBces();

        if (reportValidationErrors(validateDeployment(beans, index, discoveryResult), dependencyTypes)) {
            return true;
        }

        var generated = generateArtefacts(beans, index);
        // What this compilation's own annotation types declare, rendered into their own package so a
        // package-private qualifier is covered like any other (vauban#70).
        componentProviders.writeAll(generated, declaredAnnotationTypes(index));

        // Write META-INF/vauban-beans.list
        writeBeansList(beans);

        writeRequiredOpens(generated.requiredOpens);

        // Publish the (ProxyLink) weave plan for the auto-started javac plugin — see
        // io.vidocq.vauban.processor.weave (Vidocq/vauban#24, javac tier of the weaving).
        publishWeavePlan(beans, index);

        // Write BCE processed marker
        if (bceProcessed) {
            writeBceProcessedMarker();
        }

        mergeMainRoundIndexes();

        return true;
    }

    /**
     * The {@code @Discovery} phase: runs it, writes down the contexts the extensions registered,
     * and adds the classes they scanned to {@code indexBuilder}.
     */
    private BceProcessor.DiscoveryResult runDiscoveryPhase(List<Class<?>> bceClasses,
            io.vidocq.vauban.indexer.VaubanIndex index, IndexBuilder indexBuilder, ClassLoader aptClassLoader) {
        var lookup = new IndexLookup(index);
        var discoveryResult = ExtensionPhase.atBuildTime(() -> BceProcessor.processDiscovery(bceClasses, lookup));
        // The contexts the extensions registered are runtime objects: write them down, since
        // the container skips the extensions of an application compiled here (vauban-webcontexts).
        writeFrozenContexts(discoveryResult.metaAnnotations().getCustomContexts());

        // Add scanned classes to the index.
        // These classes come from dependency jars (added via ScannedClasses.add()), not from
        // the module being compiled — track them as external so we skip factory/proxy generation.
        for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
            var bytes = loadClassBytes(className, aptClassLoader);
            if (bytes.length > 0) {
                indexScannedClass(indexBuilder, className, bytes);
            }
        }
        return discoveryResult;
    }

    private void indexScannedClass(IndexBuilder indexBuilder, String className, byte[] bytes) {
        try {
            indexBuilder.add(ClassFileScanner.scan(bytes));
            externalClassNames.add(DotName.of(className));
        } catch (Exception _) {
            // Class scan failed — skip
        }
    }

    /**
     * The BCE phases after {@code @Discovery} ({@code @Enhancement} to {@code @Validation}), their
     * results applied to {@code beans} and written down for the runtime.
     *
     * @return {@code false} when an extension reported a definition or deployment error
     */
    private boolean runRemainingBcePhases(List<Class<?>> bceClasses, List<BeanDescriptor> beans,
            BeanDiscovery discovery, BceProcessor.DiscoveryResult discoveryResult,
            io.vidocq.vauban.indexer.VaubanIndex index, ClassLoader aptClassLoader) {
        var archiveClasses = loadArchiveClasses(index, aptClassLoader);
        var observers = discovery.discoverObservers();
        var interceptors = discovery.discoverInterceptors();

        var bceInstances = discoveryResult.bceInstances();
        var bceResult = ExtensionPhase.atBuildTime(() -> BceProcessor.process(bceClasses, beans,
                observers, interceptors,
                index, aptClassLoader,
                bceInstances, archiveClasses,
                name -> index.getClassByName(name).map(discovery::buildManagedBean).orElse(null)));

        // Report BCE errors as compilation errors
        boolean hasErrors = false;
        for (var err : bceResult.definitionErrors()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    BCE_PREFIX + err);
            hasErrors = true;
        }
        for (var err : bceResult.deploymentErrors()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    BCE_PREFIX + err);
            hasErrors = true;
        }

        if (hasErrors) return false;

        // Apply enhancement modifications
        if (!bceResult.enhancementModifications().isEmpty()) {
            // Modified beans, plus the non-beans that gained a scope via Enhancement
            var modified = BceProcessor.beansAfterEnhancement(beans, bceResult.enhancementModifications(),
                    name -> index.getClassByName(name).map(discovery::buildManagedBean).orElse(null));
            beans.clear();
            beans.addAll(modified);

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
        return true;
    }

    /**
     * Validates the deployment — unless explicitly skipped (cf. APT options on the class javadoc).
     * The runtime container always re-validates on container start; this only silences the
     * compile-time check for projects whose beans depend on BCE-produced injections that
     * aren't visible to the static analyser (typical pattern with {@code @ConfigProperty},
     * {@code @Claim} or {@code @RegisterRestClient} used in test code without the producing BCE on
     * the APT classpath).
     */
    private List<DeploymentValidator.ValidationError> validateDeployment(List<BeanDescriptor> beans,
            io.vidocq.vauban.indexer.VaubanIndex index, BceProcessor.DiscoveryResult discoveryResult) {
        if (!validationEnabled()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Static deployment validation skipped (vauban.validation=false). "
                            + "Runtime container will still validate on start.");
            return List.of();
        }
        var assignability = new AssignabilityRules(index);
        // Matching reads what each qualifier type declares from the index, the only source at
        // compile time: a member default counts as a written value and a @Nonbinding member takes
        // no part, exactly as when the container validates the same deployment again at boot.
        var annotationTypes = new io.vidocq.vauban.core.annotation.AnnotationTypes(
                index, List.of(), nonbindingMembers(discoveryResult));
        var candidates = new ArrayList<BeanDescriptor>(beans);
        candidates.addAll(archiveBeans);
        var resolver = new BeanResolver(candidates, List.of(), assignability,
                new io.vidocq.vauban.core.bean.resolution.QualifierMatcher(annotationTypes));
        var validator = new DeploymentValidator(beans, resolver, index);
        return validator.validate();
    }

    /**
     * Reports each validation error, as an error or, when the container may still resolve it, as
     * a warning.
     *
     * @return {@code true} when at least one error is fatal to the build
     */
    private boolean reportValidationErrors(List<DeploymentValidator.ValidationError> errors,
            DependencyTypes dependencyTypes) {
        boolean fatal = false;
        for (var error : errors) {
            if (reportValidationError(error, dependencyTypes)) {
                fatal = true;
            }
        }
        return fatal;
    }

    /** Reports one validation error; returns {@code true} when it is fatal to the build. */
    private boolean reportValidationError(DeploymentValidator.ValidationError error, DependencyTypes dependencyTypes) {
        // An unsatisfied point whose required type comes from a dependency is deferred to the
        // container, not rejected here: this compilation cannot see that module's producers,
        // and the runtime — which merges the vauban-beans.list of every archive — can.
        if (dependencyTypes.isDeferred(error)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    LOG_PREFIX + error.message()
                            + " — the required type is not declared by this compilation unit; "
                            + "resolution deferred to the runtime container.");
            return false;
        }
        // A missing proxy-entry constructor is fixable without touching the source: the
        // vauban-maven-plugin weaves a synthetic (ProxyLink) constructor at process-classes
        // (Vidocq/vauban#24 phase 2). Warn here; a deployment that skips the plugin still
        // fails at container start. Final classes/methods stay fatal — weaving cannot fix them.
        if (error.kind() == DeploymentValidator.ValidationError.Kind.UNPROXYABLE_BEAN) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    LOG_PREFIX + error.message()
                            + " — the vauban-maven-plugin weaves this constructor at "
                            + "process-classes; without the plugin this fails at container start.");
            return false;
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
            LOG_PREFIX + error.message() + unsatisfiedDependencyHint(error));
        return true;
    }

    /** For an unsatisfied dependency, the extensions that may contribute the bean but cannot run; else empty. */
    private String unsatisfiedDependencyHint(DeploymentValidator.ValidationError error) {
        if (error.kind() != DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY) {
            return "";
        }
        var unloadable = unloadableBcesOnCompilePath();
        if (unloadable.isEmpty()) {
            return "";
        }
        return " Hint: Build Compatible Extension(s) " + unloadable
                + " found on the compile/module path cannot run (not on the"
                + " annotation-processor path) and may contribute the missing bean —"
                + " add their artifact(s) to <annotationProcessorPaths>.";
    }

    /**
     * Generates code for each bean; also accumulates the eligible managed classes for the
     * per-package {@code _VaubanComponents} provider.
     */
    private Generation generateArtefacts(List<BeanDescriptor> beans, io.vidocq.vauban.indexer.VaubanIndex index) {
        var generation = new Generation();
        for (var bean : beans) {
            if (bean.kind() == BeanDescriptor.BeanKind.MANAGED) {
                generateManagedBeanArtefacts(bean, index, generation);
            } else if ((bean.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                    || bean.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) && bean.scope().isNormal()) {
                // Build-time proxy for a normal-scoped producer whose produced type is a fully-public
                // class from another module (issue #42, cdi#1015). The proxy is placed in the
                // producer's OWN package (never the produced type's — that would be a split package)
                // and registered under the produced-type key the container looks up before its
                // reflective fallback, so the module path needs no `opens` and no runtime defineClass.
                clientProxies.generateForProducer(bean, generation);
            }
        }
        return generation;
    }

    /**
     * The client proxy and the intercepted subclass of a managed bean, as it needs them. No factory class: the
     * container instantiates the bean through the module's {@code _VaubanComponents} provider (vauban#227).
     */
    private void generateManagedBeanArtefacts(BeanDescriptor bean, io.vidocq.vauban.indexer.VaubanIndex index,
            Generation generation) {
        var classInfo = index.getClassByName(bean.beanClass()).orElse(null);
        if (classInfo == null) return;

        // Skip proxy generation for classes that originate from dependency jars
        // (added via ScannedClasses.add() during @Discovery). Generating artifacts in
        // the user module for such classes would create a Java Modules split-package violation
        // because the package is already exported by the source jar.
        // The class remains in vauban-beans.list for injection resolution.
        if (externalClassNames.contains(bean.beanClass())) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Skipping proxy generation for external class "
                            + bean.beanClass().value()
                            + " (expected to be provided by the source jar)");
            return;
        }

        if (bean.scope().isNormal()) {
            clientProxies.generateForBean(bean.beanClass().value(), generation);
        }

        var fqn = bean.beanClass().value();
        boolean interceptedGenerated = intercepted.generate(fqn);

        // Accumulate for the provider: only top-level types get an in-module entry. The
        // instantiable flag lets ComponentCollector attempt constructor-param extraction, and
        // the intercepted flag makes it also emit a component for the $$Intercepted subclass
        // generated just above, which the bytecode provider instantiates in-module with no opens.
        // The key stays the binary name — that is what the container looks a component
        // up by — while the provider instantiates it by its canonical name, the only one
        // that can be written in source for a nested type.
        var beanElement = TypeLookup.typeElementByBinaryName(processingEnv.getElementUtils(), fqn);
        var sourceFqn = beanElement != null
                ? beanElement.getQualifiedName().toString() : fqn;
        generation.providedClasses.add(new ProvidedClass(
                fqn, sourceFqn, classInfo, true, interceptedGenerated));
    }

    /**
     * The main sources' output, when this compilation is another round over the same module, its tests typically
     * (vauban#101). From {@code -Avauban.mainOutput}, else from a main {@code _VaubanComponents} found outside this
     * round's output, else from the build tools' layouts: Maven's {@code test-classes} next to {@code classes},
     * Gradle's {@code classes/java/test} next to {@code classes/java/main}. {@code null} for a main round.
     */
    private java.nio.file.Path mainOutput() {
        if (mainOutputResolved) {
            return mainOutput;
        }
        mainOutputResolved = true;
        var option = processingEnv.getOptions().get("vauban.mainOutput");
        if (option != null && !option.isBlank()) {
            mainOutput = existingDirectory(java.nio.file.Path.of(option.trim()));
            return mainOutput;
        }
        if (mainOutputFromProvider != null) {
            mainOutput = mainOutputFromProvider;
            return mainOutput;
        }
        var roundOutput = classOutput();
        if (roundOutput == null) {
            return null;
        }
        var name = roundOutput.getFileName() == null ? "" : roundOutput.getFileName().toString();
        if (name.equals("test-classes") && roundOutput.getParent() != null) {
            mainOutput = existingDirectory(roundOutput.getParent().resolve("classes"));
        } else if (name.equals("test") && roundOutput.getParent() != null
                && roundOutput.getParent().getFileName() != null
                && roundOutput.getParent().getParent() != null
                && roundOutput.getParent().getParent().getFileName() != null
                && roundOutput.getParent().getParent().getFileName().toString().equals("classes")) {
            mainOutput = existingDirectory(roundOutput.getParent().resolve("main"));
        }
        return mainOutput;
    }

    private java.nio.file.Path mainOutput;
    private boolean mainOutputResolved;
    /** The main output a main {@code _VaubanComponents} found outside this round's output points to. */
    private java.nio.file.Path mainOutputFromProvider;

    private static java.nio.file.Path existingDirectory(java.nio.file.Path dir) {
        return java.nio.file.Files.isDirectory(dir) ? dir.toAbsolutePath().normalize() : null;
    }

    /** This round's class output directory, or {@code null} when it is no directory. */
    private java.nio.file.Path classOutput() {
        try {
            var probe = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", "vauban-round-probe");
            return java.nio.file.Path.of(probe.toUri()).getParent().toAbsolutePath().normalize();
        } catch (IOException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException _) {
            return null;
        }
    }

    /** The line-based indexes a test round writes that hide the main round's when the tests patch the module. */
    private static final List<String> MERGED_INDEXES = List.of(BEANS_LIST_PATH, REQUIRED_OPENS_PATH,
            BCE_RUNTIME_LIST_PATH, io.vidocq.vauban.core.extensions.FrozenContexts.PATH);

    /** The binary indexes, which are not merged: a test round that writes one next to a main one is reported. */
    private static final List<String> UNMERGED_INDEXES = List.of(
            io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH,
            SyntheticMetadataSerializer.METADATA_PATH);

    /**
     * In a test round, adds the main round's lines to each index this round wrote (vauban#101). When the tests run,
     * the test output patches the module and its {@code META-INF/vauban-beans.list} hides the main one, so the
     * main beans vanished from discovery. A merged list makes the shadowing harmless.
     */
    private void mergeMainRoundIndexes() {
        var main = mainOutput();
        var roundOutput = classOutput();
        if (main == null || roundOutput == null || main.equals(roundOutput)) {
            return;
        }
        for (var path : MERGED_INDEXES) {
            var own = roundOutput.resolve(path);
            var theirs = main.resolve(path);
            if (java.nio.file.Files.isRegularFile(own) && java.nio.file.Files.isRegularFile(theirs)) {
                mergeIndex(path, own, theirs);
            }
        }
        for (var path : UNMERGED_INDEXES) {
            if (java.nio.file.Files.isRegularFile(roundOutput.resolve(path))
                    && java.nio.file.Files.isRegularFile(main.resolve(path))) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "[Vauban] The test compilation wrote " + path + " next to the main sources' one; when the"
                                + " tests run it hides the main one, which is not merged (vauban#101).");
            }
        }
    }

    /** Adds to the index file {@code own} the entries of {@code theirs} it lacks, comments and blanks left out. */
    private void mergeIndex(String path, java.nio.file.Path own, java.nio.file.Path theirs) {
        try {
            var lines = new ArrayList<>(java.nio.file.Files.readAllLines(own, StandardCharsets.UTF_8));
            var present = new java.util.HashSet<>(lines);
            for (var line : java.nio.file.Files.readAllLines(theirs, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#") && present.add(line)) {
                    lines.add(line);
                }
            }
            java.nio.file.Files.write(own, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Cannot add the main sources' " + path + " to the test compilation's: "
                            + e.getMessage() + ". When the tests run, the main beans may be missing.");
        }
    }

    // --- ProxyLink weave plan (javac tier) ---

    private boolean weavePlanPublished;

    /**
     * Publishes the weave plan executed by {@code VaubanWeavingPlugin} at the end of this
     * javac task: the processor cannot patch class files that are not written yet, and the
     * plugin — public API only — cannot know the output directory; the plan is the bridge.
     * Covers every top-level normal-scoped managed bean of this compilation that does not
     * declare the {@code (ProxyLink)} entry constructor; their generated proxies are
     * retargeted onto it.
     */
    private void publishWeavePlan(java.util.List<BeanDescriptor> beans,
            io.vidocq.vauban.indexer.VaubanIndex index) {
        if (weavePlanPublished) return;
        var lines = new java.util.ArrayList<io.vidocq.vauban.processor.weave.WeavePlan.Line>();
        var planned = new java.util.LinkedHashSet<String>();
        for (var bean : beans) {
            if (isWeavingCandidate(bean) && planBeanWeaving(bean.beanClass().value(), index, planned, lines)) {
                lines.add(io.vidocq.vauban.processor.weave.WeavePlan.Line.proxy(
                        bean.beanClass().value() + ClientProxyArtefacts.CLIENT_PROXY_SUFFIX));
            }
        }
        if (lines.isEmpty()) return;
        try {
            io.vidocq.vauban.processor.weave.WeavePlan.publish(processingEnv.getFiler(), lines);
            weavePlanPublished = true;
        } catch (java.io.IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] could not publish the ProxyLink weave plan: " + e.getMessage());
        }
    }

    /** A top-level normal-scoped managed bean of this compilation, whose generated proxy may need the marker. */
    private boolean isWeavingCandidate(BeanDescriptor bean) {
        return bean.kind() == BeanDescriptor.BeanKind.MANAGED && bean.scope().isNormal()
                && typeLookup.isTopLevelType(bean.beanClass().value()) && !externalClassNames.contains(bean.beanClass());
    }

    /**
     * Plans the weaving of {@code fqn} (recursively covering superclasses compiled in this
     * unit). Returns {@code true} when the bean will carry the marker — already declared
     * manually, or planned here.
     */
    private boolean planBeanWeaving(String fqn, io.vidocq.vauban.indexer.VaubanIndex index,
            java.util.Set<String> planned,
            java.util.List<io.vidocq.vauban.processor.weave.WeavePlan.Line> lines) {
        if (planned.contains(fqn)) return true;
        var type = processingEnv.getElementUtils().getTypeElement(fqn);
        if (type == null) return false;

        var ctors = javax.lang.model.util.ElementFilter.constructorsIn(type.getEnclosedElements());
        if (ctors.stream().anyMatch(ClientProxyShapeFromElements::isProxyLinkConstructor)) {
            planned.add(fqn); // manual phase-1 constructor — nothing to weave
            return true;
        }

        var chain = superChainFor(type, index, planned, lines);
        if (chain == null) return false; // unweavable — the UNPROXYABLE warning already fired

        lines.add(io.vidocq.vauban.processor.weave.WeavePlan.Line.bean(fqn, chain));
        planned.add(fqn);
        return true;
    }

    /** How {@code type}'s woven marker chains to its superclass — {@code null} when it cannot. */
    private io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain superChainFor(
            javax.lang.model.element.TypeElement type,
            io.vidocq.vauban.indexer.VaubanIndex index,
            java.util.Set<String> planned,
            java.util.List<io.vidocq.vauban.processor.weave.WeavePlan.Line> lines) {
        if (!(type.getSuperclass() instanceof javax.lang.model.type.DeclaredType declared)
                || !(declared.asElement() instanceof javax.lang.model.element.TypeElement superType)) {
            return null;
        }
        var superFqn = superType.getQualifiedName().toString();
        if (JAVA_LANG_OBJECT.equals(superFqn)) {
            return io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain.NO_ARG;
        }
        var superCtors = javax.lang.model.util.ElementFilter
                .constructorsIn(superType.getEnclosedElements());
        if (superCtors.stream().anyMatch(ClientProxyShapeFromElements::isProxyLinkConstructor)) {
            return io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain.MARKER;
        }
        if (superCtors.isEmpty() || superCtors.stream().anyMatch(c ->
                c.getParameters().isEmpty()
                        && !c.getModifiers().contains(javax.lang.model.element.Modifier.PRIVATE))) {
            return io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain.NO_ARG;
        }
        // The superclass itself needs the marker — only weavable when compiled here too.
        var superName = io.vidocq.vauban.indexer.model.DotName.of(superFqn);
        boolean compiledHere = index.getClassByName(superName).isPresent()
                && !externalClassNames.contains(superName)
                && typeLookup.isTopLevelType(superFqn);
        if (compiledHere && planBeanWeaving(superFqn, index, planned, lines)) {
            return io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain.MARKER;
        }
        return null;
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
        } catch (Exception _) {
            // Filer probe failed — default to running validation (safest).
            return true;
        }
    }

    // --- BCE Discovery ---

    /**
     * BCE implementations declared on the <em>compile/module path</em> that the processor-path
     * {@code ServiceLoader} of {@link #discoverBceClasses(ClassLoader)} could not load, and which
     * therefore silently never run (vauban#29 — Maven's {@code <annotationProcessorPaths>}
     * narrows javac's {@code -processorpath} to its entries only).
     *
     * <p>The processor cannot load those classes — javac gives it no classloader over the compile
     * path — but their <em>declarations</em> are visible through supported APIs: the
     * {@code provides} directives of every observable module ({@code Elements}), and the
     * {@code META-INF/services} resource of the classpath ({@code Filer}). Anything declared
     * there but absent from the loaded set is reported.
     */
    private Set<String> unloadableBcesOnCompilePath() {
        if (unloadableBcesCache != null) return unloadableBcesCache;
        var declared = new LinkedHashSet<String>();
        var bceFqn = BuildCompatibleExtension.class.getName();

        // (a) module path: `provides BuildCompatibleExtension with ...` in observable modules.
        // Modules under compilation are skipped: completing their directives here would freeze
        // `provides` resolution errors before the last-round generated classes exist.
        try {
            for (var module : processingEnv.getElementUtils().getAllModuleElements()) {
                if (!module.isUnnamed()
                        && !compiledModuleNames.contains(module.getQualifiedName().toString())) {
                    collectProvidedBces(module, bceFqn, declared);
                }
            }
        } catch (Exception _) {
            // Module elements unavailable in this javac configuration — classpath probe still runs.
        }

        // (b) classpath: the ServiceLoader descriptor as a compile-classpath resource.
        try {
            var resource = processingEnv.getFiler()
                    .getResource(StandardLocation.CLASS_PATH, "", "META-INF/services/" + bceFqn);
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.openInputStream(), StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(line -> line.replaceFirst("#.*", "").trim())
                        .filter(line -> !line.isEmpty())
                        .forEach(declared::add);
            }
        } catch (Exception _) {
            // No such resource on the classpath — the normal case.
        }

        discoveredBceClasses.forEach(loaded -> declared.remove(loaded.getName()));
        unloadableBcesCache = declared;
        return declared;
    }

    /** Adds to {@code declared} the implementations {@code module} provides for the service {@code bceFqn}. */
    private static void collectProvidedBces(ModuleElement module, String bceFqn, Set<String> declared) {
        for (var directive : module.getDirectives()) {
            if (directive.getKind() == ModuleElement.DirectiveKind.PROVIDES
                    && directive instanceof ModuleElement.ProvidesDirective provides
                    && bceFqn.contentEquals(provides.getService().getQualifiedName())) {
                provides.getImplementations()
                        .forEach(impl -> declared.add(impl.getQualifiedName().toString()));
            }
        }
    }

    /** One-shot warning naming the BCEs of {@link #unloadableBcesOnCompilePath()}. */
    private void warnUnloadableBces() {
        if (warnedUnloadableBces) return;
        warnedUnloadableBces = true;
        var unloadable = unloadableBcesOnCompilePath();
        if (unloadable.isEmpty()) return;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                LOG_PREFIX + unloadable.size() + " Build Compatible Extension(s) present on the"
                        + " compile/module path are NOT on the annotation-processor path and will"
                        + " not run: " + unloadable + ". Maven's <annotationProcessorPaths> narrows"
                        + " javac's -processorpath to its entries — add the artifact(s) shipping"
                        + " these extensions as additional <path> entries (or pass"
                        + " -Avauban.validation=false to defer bean validation to the runtime"
                        + " container).");
    }

    /**
     * The build compatible extensions {@code cl} provides. A provider that cannot be loaded or instantiated is skipped
     * with a warning naming the service file, and the others still run (vauban#199).
     */
    private List<Class<?>> discoverBceClasses(ClassLoader cl) {
        var bceClasses = new ArrayList<Class<?>>();
        var providers = ServiceLoader.load(BuildCompatibleExtension.class, cl).iterator();
        String lastError = null;
        var more = true;
        while (more) {
            try {
                more = providers.hasNext();
                if (more) {
                    bceClasses.add(providers.next().getClass());
                }
            } catch (ServiceConfigurationError | LinkageError e) {
                // ServiceLoader goes on with the next provider after an error; the same error twice in a row
                // means it cannot move past it (the service files themselves cannot be listed)
                more = !Objects.equals(lastError, e.getMessage());
                if (more) {
                    lastError = e.getMessage();
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, LOG_PREFIX
                            + "Skipping a build compatible extension listed in " + BCE_SERVICE_PATH + ": " + lastError);
                }
            }
        }
        if (!bceClasses.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Discovered " + bceClasses.size() + " Build Compatible Extension(s)");
        }
        return bceClasses;
    }

    /**
     * Outcome of the dependency-bean resolution pass: whether the index gained anything, and which
     * types could not be turned into beans here and must therefore be left to the container.
     *
     * @param indexGrew     whether a dependency type was added to the index
     * @param deferredTypes fully-qualified names that exist on the compile classpath but did not
     *                      yield a bean in this compilation — a producer in the other module, say
     */
    private record DependencyTypes(boolean indexGrew, Set<String> deferredTypes, Set<String> archiveClasses) {

        /** Whether this error is about a type this compilation cannot see the whole story of. */
        boolean isDeferred(DeploymentValidator.ValidationError error) {
            if (error.kind() == DeploymentValidator.ValidationError.Kind.AMBIGUOUS_DEPENDENCY) {
                // A choice between beans of other modules is the container's to make, with every archive in view.
                return archiveClasses.stream().anyMatch(fqn -> error.message().contains(fqn));
            }
            if (error.kind() != DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY) {
                return false;
            }
            return deferredTypes.stream().anyMatch(fqn -> error.message().contains(fqn));
        }
    }

    /**
     * Adds to the index every annotation type the indexed classes name, and then the types those name
     * in turn — the meta-annotations that say what a type is ({@code @Qualifier}, {@code @Stereotype},
     * {@code @InterceptorBinding}) and what its members mean ({@code @Nonbinding}, their defaults).
     *
     * <p>Discovery and validation read all of that from the index. Their fallback, loading the type by
     * name, cannot work here: the qualifier of the module being compiled exists nowhere yet, and a
     * qualifier from a dependency sits on the compile classpath, which the processor's own class loader
     * does not see. {@code Elements} sees both.
     */
    private void indexAnnotationTypes(IndexBuilder indexBuilder, ElementScanner scanner) {
        var elements = processingEnv.getElementUtils();
        var pending = new ArrayDeque<DotName>();
        var seen = new HashSet<DotName>();
        for (var classInfo : indexBuilder.build().getKnownClasses()) {
            collectAnnotationNames(classInfo, pending);
        }
        while (!pending.isEmpty()) {
            var name = pending.poll();
            var element = seen.add(name) && !indexBuilder.contains(name)
                    ? annotationTypeElement(elements, name) : null;
            if (element != null) {
                var scanned = scanner.scan(element);
                indexBuilder.add(scanned);
                collectAnnotationNames(scanned, pending);
            }
        }
    }

    /** The members extensions made non-binding during {@code @Discovery}, by qualifier type name. */
    private static Map<String, Set<String>> nonbindingMembers(BceProcessor.DiscoveryResult discoveryResult) {
        return discoveryResult == null ? Map.of()
                : discoveryResult.metaAnnotations().getNonbindingMembers();
    }

    /** The annotation type {@code name}, from this compilation or the compile classpath, or {@code null}. */
    private static TypeElement annotationTypeElement(Elements elements, DotName name) {
        var element = elements.getTypeElement(name.value());
        if (element == null) {
            // The index names a nested type the way the class file does, Outer$Inner; javac wants Outer.Inner.
            element = elements.getTypeElement(name.value().replace('$', '.'));
        }
        return element != null && element.getKind() == ElementKind.ANNOTATION_TYPE ? element : null;
    }

    /**
     * The annotation types this compilation declares, by package — the only ones whose artefacts it
     * may render: the metadata, the reader and the literal all name the type's members directly, so
     * they have to sit in its own package, and a type from a dependency already ships its own.
     *
     * <p>{@code Elements} tells the two apart by where the type comes from: a source file of this
     * compilation (including one a companion processor generated), or a class file on the path.
     */
    private Map<String, List<ClassInfo>> declaredAnnotationTypes(io.vidocq.vauban.indexer.VaubanIndex index) {
        var elements = processingEnv.getElementUtils();
        // Per package, the annotation types its provider carries — keyed by name so a type used many
        // times in one package is rendered once.
        var byPackage = new java.util.LinkedHashMap<String, java.util.LinkedHashMap<DotName, ClassInfo>>();
        var fromDependencies = new java.util.LinkedHashMap<DotName, ClassInfo>();

        for (var classInfo : index.getKnownClasses()) {
            var element = classInfo.isAnnotation() ? annotationTypeElement(elements, classInfo.name()) : null;
            if (element != null && compiledHere(elements, element)) {
                byPackage.computeIfAbsent(classInfo.name().packageName(),
                        pkg -> new java.util.LinkedHashMap<>()).put(classInfo.name(), classInfo);
            } else if (element != null && coverableFromAConsumer(classInfo, element)) {
                fromDependencies.put(classInfo.name(), classInfo);
            }
        }

        if (!fromDependencies.isEmpty()) {
            addUsedDependencyAnnotationTypes(index, elements, fromDependencies, byPackage);
        }

        var result = new java.util.LinkedHashMap<String, List<ClassInfo>>();
        byPackage.forEach((pkg, types) -> result.put(pkg, List.copyOf(types.values())));
        return result;
    }

    /** Whether the class {@code classInfo} names comes from a source file of this compilation. */
    private static boolean compiledHere(Elements elements, ClassInfo classInfo) {
        var element = elements.getTypeElement(classInfo.name().value().replace('$', '.'));
        return element != null && compiledHere(elements, element);
    }

    /** Adds to the package of each class compiled here the dependency annotation types it uses. */
    private static void addUsedDependencyAnnotationTypes(io.vidocq.vauban.indexer.VaubanIndex index,
            Elements elements, Map<DotName, ClassInfo> fromDependencies,
            Map<String, java.util.LinkedHashMap<DotName, ClassInfo>> byPackage) {
        for (var classInfo : index.getKnownClasses()) {
            if (!classInfo.isAnnotation() && compiledHere(elements, classInfo)) {
                addUsedDependencyAnnotationTypesOf(classInfo, fromDependencies, byPackage);
            }
        }
    }

    /** Adds to the package of {@code classInfo} the dependency annotation types it uses. */
    private static void addUsedDependencyAnnotationTypesOf(ClassInfo classInfo,
            Map<DotName, ClassInfo> fromDependencies,
            Map<String, java.util.LinkedHashMap<DotName, ClassInfo>> byPackage) {
        var used = new java.util.ArrayList<DotName>();
        collectAnnotationNames(classInfo, used);
        for (var name : used) {
            var type = fromDependencies.get(name);
            if (type != null) {
                byPackage.computeIfAbsent(classInfo.name().packageName(),
                        pkg -> new java.util.LinkedHashMap<>()).put(name, type);
            }
        }
    }

    /**
     * Whether a qualifier or interceptor binding that comes from a dependency can be covered by the
     * module that <em>uses</em> it (vauban#88). Its own module ships nothing for it — it was not
     * built with this processor — so without this the container builds a {@code reflect.Proxy} for
     * it, which {@code -Dvauban.annotations.reflection=forbid} refuses.
     *
     * <p>Only a <strong>public</strong> type qualifies: a literal has to implement the annotation
     * interface, which no other package can do for a package-private one. And only a type the
     * container needs instances of — the built-ins it has literals for are left alone.
     */
    private static boolean coverableFromAConsumer(ClassInfo classInfo, TypeElement element) {
        if (!element.getModifiers().contains(Modifier.PUBLIC)) return false;
        return switch (classInfo.name().value()) {
            case "jakarta.enterprise.inject.Default", "jakarta.enterprise.inject.Any",
                 "jakarta.inject.Named" -> false;
            default -> classInfo.hasAnnotation(DotName.of("jakarta.inject.Qualifier"))
                    || classInfo.hasAnnotation(DotName.of("jakarta.interceptor.InterceptorBinding"));
        };
    }

    /** Whether {@code element} comes from a source file of this compilation rather than the path. */
    private static boolean compiledHere(Elements elements, TypeElement element) {
        var file = fileObjectOf(elements, element);
        return file != null && file.getKind() == javax.tools.JavaFileObject.Kind.SOURCE;
    }

    /**
     * The file {@code element} was read from, or {@code null} when the compiler cannot say. Eclipse's compiler (JDT APT,
     * also behind VS Code's Java extension) throws {@link java.nio.file.FileSystemNotFoundException} for a type read
     * from a jar: such a type is on the path, not a source of this compilation.
     */
    private static javax.tools.JavaFileObject fileObjectOf(Elements elements, javax.lang.model.element.Element element) {
        try {
            return elements.getFileObjectOf(element);
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** Every annotation named on a class, on its fields, on its methods and on their parameters. */
    private static void collectAnnotationNames(ClassInfo classInfo, Collection<DotName> into) {
        classInfo.annotations().forEach(annotation -> collectAnnotationNames(annotation, into));
        classInfo.fields().forEach(field ->
                field.annotations().forEach(annotation -> collectAnnotationNames(annotation, into)));
        for (var method : classInfo.methods()) {
            method.annotations().forEach(annotation -> collectAnnotationNames(annotation, into));
            method.parameters().forEach(parameter ->
                    parameter.annotations().forEach(annotation -> collectAnnotationNames(annotation, into)));
            // An annotation member may default to a nested annotation, whose type is named nowhere else.
            if (method.defaultValue() != null) {
                collectAnnotationNames(method.defaultValue(), into);
            }
        }
    }

    /** The annotation's own type, and the types of the annotations its members hold. */
    private static void collectAnnotationNames(AnnotationInfo annotation, Collection<DotName> into) {
        into.add(annotation.name());
        annotation.members().values().forEach(value -> collectAnnotationNames(value, into));
    }

    private static void collectAnnotationNames(AnnotationValue value, Collection<DotName> into) {
        switch (value) {
            case AnnotationValue.AnnotationVal(AnnotationInfo nested) -> collectAnnotationNames(nested, into);
            case AnnotationValue.ArrayVal(List<AnnotationValue> values) ->
                    values.forEach(item -> collectAnnotationNames(item, into));
            default -> {
                // No annotation type to reach through any other member kind.
            }
        }
    }

    /**
     * Brings the beans an injection point needs from a <strong>dependency</strong> into the index.
     *
     * <p>The processor only indexes the types of the current compilation, so
     * {@code @Inject Greeter} where {@code Greeter} is an {@code @ApplicationScoped} bean of another
     * Maven module used to fail the build — while the very same injection point resolved at
     * runtime, where the container merges the {@code vauban-beans.list} of every archive. That
     * false negative was viral at each module boundary: it forced {@code Instance<T>} everywhere,
     * losing the compile-time checking that is the point of build-time CDI, and it had no answer at
     * all for a third-party module's beans (Vidocq/vauban#23).
     *
     * <p>No jar scanning is needed to fix it: {@code Elements} already sees the whole compile
     * classpath, so a missing required type is looked up there, scanned into the index, and bean
     * discovery is replayed to obtain its descriptor. Such a type is recorded in
     * {@link #externalClassNames} so no {@code _ClientProxy} is emitted for it
     * here — its own module already ships those, and emitting them again would split the package.
     *
     * @param beans           the discovered beans; grown in place with the dependency beans found
     * @param indexBuilder    the accumulated index, enriched in place
     * @param scanner         element scanner used to turn a {@code TypeElement} into a ClassInfo
     * @param discoveryResult BCE discovery result to replay on the second discovery pass, or null
     * @return what was resolved, and what has to be deferred to the runtime container
     */
    private DependencyTypes resolveDependencyBeans(List<BeanDescriptor> beans,
                                                   IndexBuilder indexBuilder,
                                                   ElementScanner scanner,
                                                   BceProcessor.DiscoveryResult discoveryResult) {
        Set<DotName> required = new LinkedHashSet<>();
        for (var bean : beans) {
            for (var ip : bean.injectionPoints()) {
                collectClassNames(ip.requiredType(), required);
            }
        }

        var elements = processingEnv.getElementUtils();
        Set<DotName> added = new LinkedHashSet<>();
        Set<String> deferred = new LinkedHashSet<>();
        for (var name : required) {
            // A type absent from the compile classpath too is a genuine mistake, left to the validator.
            var typeElement = indexBuilder.contains(name) ? null : elements.getTypeElement(name.value());
            if (typeElement != null) {
                indexBuilder.add(scanner.scan(typeElement));
                added.add(name);
            }
        }

        if (added.isEmpty()) {
            return new DependencyTypes(false, Set.of(), Set.of());
        }

        Set<DotName> becameBeans = addDependencyBeans(beans, added, indexBuilder, discoveryResult);

        for (var name : added) {
            if (!becameBeans.contains(name) && mayBeSatisfiedElsewhere(elements.getTypeElement(name.value()))) {
                deferred.add(name.value());
            }
        }
        Set<String> archiveClasses = Set.of();
        if (!deferred.isEmpty()) {
            var archives = resolveFromBeanArchiveModules(deferred, indexBuilder, scanner, discoveryResult);
            deferred.removeAll(archives.satisfied());
            archiveClasses = archives.classes();
        }
        return new DependencyTypes(true, deferred, archiveClasses);
    }

    /** What the bean archive modules contributed: the deferred types they satisfy, and their classes. */
    private record ArchiveResolution(Set<String> satisfied, Set<String> classes) {
    }

    /**
     * Resolves the types still deferred against the beans of the <strong>bean archive modules</strong> on the
     * module path (VAU-APT-006).
     *
     * <p>{@link #addDependencyBeans} only finds a dependency type that is itself a bean. An interface whose
     * producer or implementation is another class of its module stayed deferred: an application injecting
     * {@code JsonWebToken}, produced by a bean of cervantes-cdi-vauban, warned at every build. A named module
     * that {@code provides VaubanComponentProvider} is a bean archive, and each implementation of that service
     * is the {@code _VaubanComponents} of one package of its beans. Those packages are read through
     * {@code Elements} — compiled classes on the module path, never loaded, nothing of theirs executed — then
     * scanned into the index, and discovery is replayed to obtain their beans.</p>
     *
     * <p>Those beans join resolution only ({@link #archiveBeans}): their own module validates their injection
     * points, generates their artefacts and lists them in its {@code vauban-beans.list}. What stays deferred:
     * a module on the class path (no module directives to read), a bean a Build Compatible Extension adds when
     * it runs at boot only, and a type no archive declares at all.</p>
     */
    private ArchiveResolution resolveFromBeanArchiveModules(Set<String> deferred, IndexBuilder indexBuilder,
            ElementScanner scanner, BceProcessor.DiscoveryResult discoveryResult) {
        archiveBeans.clear();
        Set<DotName> scanned = scanBeanArchiveModules(indexBuilder, scanner);
        if (scanned.isEmpty()) {
            return new ArchiveResolution(Set.of(), Set.of());
        }
        indexAnnotationTypes(indexBuilder, scanner);

        var discovery = new BeanDiscovery(indexBuilder.build());
        if (discoveryResult != null) {
            applyDiscoveryResult(discovery, discoveryResult);
        }
        Set<String> satisfied = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        for (var candidate : discovery.discoverBeans()) {
            var matched = scanned.contains(candidate.beanClass())
                    ? deferred.stream().filter(fqn -> hasBeanType(candidate, fqn)).toList()
                    : List.<String>of();
            if (!matched.isEmpty()) {
                archiveBeans.add(candidate);
                satisfied.addAll(matched);
                classes.add(candidate.beanClass().value());
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "[Vauban] Resolved " + String.join(", ", matched) + " with "
                                + candidate.beanClass().value() + " from a bean archive module");
            }
        }
        return new ArchiveResolution(satisfied, classes);
    }

    /** Scans into the index the packages of beans of every bean archive module; returns the classes added. */
    private Set<DotName> scanBeanArchiveModules(IndexBuilder indexBuilder, ElementScanner scanner) {
        var elements = processingEnv.getElementUtils();
        Set<DotName> scanned = new LinkedHashSet<>();
        for (var provider : componentProvidersOfOtherModules()) {
            for (var type : ElementFilter.typesIn(elements.getPackageOf(provider).getEnclosedElements())) {
                scanArchiveType(type, indexBuilder, scanner, scanned);
            }
        }
        return scanned;
    }

    /**
     * The {@code VaubanComponentProvider} implementations the named modules on the module path provide, one per
     * package of beans. The modules being compiled are skipped: reading their directives now would resolve their
     * {@code provides} too early.
     */
    private List<TypeElement> componentProvidersOfOtherModules() {
        var providers = new ArrayList<TypeElement>();
        for (var module : processingEnv.getElementUtils().getAllModuleElements()) {
            if (module.isUnnamed() || compiledModuleNames.contains(module.getQualifiedName().toString())) {
                continue;
            }
            ElementFilter.providesIn(module.getDirectives()).stream()
                    .filter(provides -> COMPONENT_PROVIDER.equals(provides.getService().getQualifiedName().toString()))
                    .forEach(provides -> providers.addAll(provides.getImplementations()));
        }
        return providers;
    }

    /** Scans {@code type} and its member types into the index, unless already there. */
    private void scanArchiveType(TypeElement type, IndexBuilder indexBuilder, ElementScanner scanner,
            Set<DotName> scanned) {
        var name = DotName.of(processingEnv.getElementUtils().getBinaryName(type).toString());
        if (!indexBuilder.contains(name)) {
            indexBuilder.add(scanner.scan(type));
            scanned.add(name);
        }
        for (var member : ElementFilter.typesIn(type.getEnclosedElements())) {
            scanArchiveType(member, indexBuilder, scanner, scanned);
        }
    }

    /** Whether {@code bean} has a bean type whose raw class is {@code fqn}. */
    private static boolean hasBeanType(BeanDescriptor bean, String fqn) {
        var wanted = DotName.of(fqn);
        for (var type : bean.types()) {
            DotName raw = switch (type) {
                case TypeInfo.ClassType(DotName name) -> name;
                case TypeInfo.ParameterizedType(DotName rawType, _) -> rawType;
                default -> null;
            };
            if (wanted.equals(raw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replays bean discovery over the enriched index and adds to {@code beans} those whose class is
     * one of the dependency types {@code added}; they are external, since their own module ships
     * their generated artefacts.
     *
     * @return the dependency types that became beans
     */
    private Set<DotName> addDependencyBeans(List<BeanDescriptor> beans, Set<DotName> added,
            IndexBuilder indexBuilder, BceProcessor.DiscoveryResult discoveryResult) {
        var discovery = new BeanDiscovery(indexBuilder.build());
        if (discoveryResult != null) {
            applyDiscoveryResult(discovery, discoveryResult);
        }

        Set<DotName> becameBeans = new LinkedHashSet<>();
        for (var candidate : discovery.discoverBeans()) {
            if (added.contains(candidate.beanClass())) {
                beans.add(candidate);
                becameBeans.add(candidate.beanClass());
                // The dependency ships its own generated artefacts; emitting ours would split the package.
                externalClassNames.add(candidate.beanClass());
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "[Vauban] Resolved " + candidate.beanClass().value()
                                + " from a dependency (injection point of this module)");
            }
        }
        return becameBeans;
    }

    /**
     * Whether an unresolved required type could still be satisfied by a bean this compilation
     * cannot see — the only case worth deferring to the container instead of failing the build.
     *
     * <p>True for an interface or an abstract class outside the Java platform: its implementation
     * may well be a bean of another module, which this compilation has no way of knowing. False for
     * anything else — a concrete type from a dependency has been indexed above, so if it did not
     * become a bean it carries no scope and the injection point is genuinely unsatisfied, and a
     * platform type ({@code java.*}, {@code jdk.*}) is never anyone's bean.
     */
    private boolean mayBeSatisfiedElsewhere(TypeElement typeElement) {
        if (typeElement == null) {
            return false;
        }
        boolean abstractType = typeElement.getKind().isInterface()
                || typeElement.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT);
        if (!abstractType) {
            return false;
        }
        var module = processingEnv.getElementUtils().getModuleOf(typeElement);
        if (module == null || module.isUnnamed()) {
            return true;
        }
        String moduleName = module.getQualifiedName().toString();
        return !moduleName.equals("java.base")
                && !moduleName.startsWith("java.")
                && !moduleName.startsWith("jdk.");
    }

    /** Collects the class names a required type refers to (raw type and type arguments alike). */
    private static void collectClassNames(TypeInfo type, Set<DotName> out) {
        switch (type) {
            case TypeInfo.ClassType(DotName name) -> out.add(name);
            case TypeInfo.ParameterizedType(DotName rawType, List<TypeInfo> typeArguments) -> {
                out.add(rawType);
                typeArguments.forEach(arg -> collectClassNames(arg, out));
            }
            case TypeInfo.ArrayType(TypeInfo componentType, _) -> collectClassNames(componentType, out);
            default -> { /* primitives, void, type variables and wildcards name no bean */ }
        }
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
        discovery.setCustomNonbindingMembers(meta.getNonbindingMembers());

        if (!discoveryResult.scannedClasses().getAddedClasses().isEmpty()) {
            var scannedDotNames = discoveryResult.scannedClasses().getAddedClasses().stream()
                    .map(DotName::of)
                    .collect(Collectors.toSet());
            discovery.setForcedBeanClasses(scannedDotNames);
        }
    }

    // --- Utility methods ---

    static List<Class<?>> loadArchiveClasses(io.vidocq.vauban.indexer.VaubanIndex index, ClassLoader cl) {
        var classes = new ArrayList<Class<?>>();
        for (var classInfo : index.getKnownClasses()) {
            try {
                classes.add(Class.forName(classInfo.name().value(), false, cl));
            } catch (ClassNotFoundException | LinkageError _) {
                // Not on the processor path, or a supertype is not (VAU-APT-008): skip
            }
        }
        return classes;
    }

    /** The class file of {@code className} on {@code cl}; empty when it cannot be read there. */
    private byte[] loadClassBytes(String className, ClassLoader cl) {
        var resourceName = className.replace('.', '/') + ".class";
        try (var is = cl.getResourceAsStream(resourceName)) {
            return is != null ? is.readAllBytes() : new byte[0];
        } catch (IOException _) {
            return new byte[0];
        }
    }

    // --- File writing ---

    /**
     * Write {@code META-INF/vauban/required-opens.list} (issue #42, Stage 3b): the produced class
     * types whose proxy falls to runtime generation. At boot the OpensApplier loads each, and — only
     * if it is in a named module — opens its package to {@code io.vidocq.vauban.core} via the agent,
     * so the runtime fallback needs no hand-written {@code --add-opens}.
     */
    private void writeRequiredOpens(java.util.Set<String> producedTypeFqns) {
        if (producedTypeFqns.isEmpty()) return;
        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", REQUIRED_OPENS_PATH);
            try (var writer = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                writer.println("# Vauban produced types needing a boot-time opens (Stage 3b) — one FQN per line");
                for (var fqn : producedTypeFqns) {
                    writer.println(fqn);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    FAILED_TO_WRITE + REQUIRED_OPENS_PATH + ": " + e.getMessage());
        }
    }

    private void writeBeansList(List<BeanDescriptor> beans) {
        // A synthetic bean has no class of its own to discover — its class may well be
        // java.lang.String — and reaches the container through the synthetic metadata.
        var beanClassNames = beans.stream()
                .filter(bean -> bean.kind() != BeanDescriptor.BeanKind.SYNTHETIC)
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
                    FAILED_TO_WRITE + BEANS_LIST_PATH + ": " + e.getMessage());
        }
    }

    private void writeBceRuntimeList(Map<DotName, List<VaubanClassConfig>> modifications) {
        var entries = new TreeSet<String>();
        for (var entry : modifications.entrySet()) {
            var targetFqn = entry.getKey().value();
            for (var config : entry.getValue()) {
                var bce = config.isModified() ? config.getSourceBce() : null;
                if (bce != null) {
                    entries.add(bce.getName() + ";" + targetFqn);
                }
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
                    FAILED_TO_WRITE + BCE_RUNTIME_LIST_PATH + ": " + e.getMessage());
        }
    }

    /**
     * Freezes the {@code @Enhancement} result as a {@code target -> added annotations} patch
     * ({@link EnhancementPatchSerializer#PATCH_PATH}), member values included (BUG-20261008-05). The
     * runtime applies this patch directly, so it never re-instantiates the BCE — removing the
     * deep-reflection that forced {@code opens ... to io.vidocq.vauban.core} on the module path.
     */
    private void writeEnhancementsPatch(Map<DotName, List<VaubanClassConfig>> modifications) {
        var patch = io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.additions(modifications);
        if (patch.isEmpty()) return;

        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "",
                    io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH);
            try (var os = resource.openOutputStream()) {
                io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.write(patch, os);
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Froze enhancement patch for " + patch.size() + " target(s)");
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
        } catch (IllegalArgumentException e) {
            // A param the run time could not be handed: failing the build beats a creator that
            // silently receives null (BUG-20261008-02).
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[Vauban] Cannot record a synthetic component: " + e.getMessage());
        }
    }

    private void writeFrozenContexts(List<io.vidocq.vauban.core.extensions.VaubanMetaAnnotations.ContextRegistration> registrations) {
        if (registrations.isEmpty()) return;
        var path = io.vidocq.vauban.core.extensions.FrozenContexts.PATH;
        try {
            var resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", path);
            try (var writer = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                writer.println("# Contexts registered by build-compatible extensions (MetaAnnotations.addContext):");
                writer.println("# <scope>;<context class>;<normal>, installed by the container at boot");
                for (var entry : io.vidocq.vauban.core.extensions.FrozenContexts.of(registrations)) {
                    writer.println(entry.line());
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    FAILED_TO_WRITE + path + ": " + e.getMessage());
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
     * Whether this compilation is another round over the same module, its test sources typically (vauban#101): the
     * package already has a {@code _VaubanComponents} class file, and it lies outside this compilation's own
     * output. One found in the own output is a stale copy from an earlier build of the same sources, not another
     * round's.
     */
    private boolean isTestRound(String pkg) {
        var name = pkg.isEmpty() ? ComponentProviderGenerator.SIMPLE_NAME : pkg + "." + ComponentProviderGenerator.SIMPLE_NAME;
        var existing = processingEnv.getElementUtils().getTypeElement(name);
        if (existing == null) {
            return false;
        }
        var file = fileObjectOf(processingEnv.getElementUtils(), existing);
        if (file == null || file.getKind() != javax.tools.JavaFileObject.Kind.CLASS) {
            return false;
        }
        try {
            var probe = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", "vauban-round-probe");
            var roundOutput = java.nio.file.Path.of(probe.toUri()).getParent().toAbsolutePath().normalize();
            var found = java.nio.file.Path.of(file.toUri()).toAbsolutePath().normalize();
            if (found.startsWith(roundOutput)) {
                return false;
            }
            // The main output is the class file's directory minus the package path.
            var root = found.getParent();
            for (int i = 0; !pkg.isEmpty() && i < pkg.split("\\.").length && root != null; i++) {
                root = root.getParent();
            }
            if (root != null && mainOutputFromProvider == null) {
                mainOutputFromProvider = root;
            }
            return true;
        } catch (IOException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException _) {
            // A class file in a jar, or an output that is no directory: not this module's main output.
            return false;
        }
    }

}
