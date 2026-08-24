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

import io.vidocq.vauban.processor.apt.ElementScanner;
import io.vidocq.vauban.processor.codegen.GeneratedClass;
import io.vidocq.vauban.processor.codegen.factory.BeanFactoryGenerator;
import io.vidocq.vauban.processor.codegen.proxy.ClientProxyGenerator;
import io.vidocq.vauban.processor.codegen.proxy.ClientProxySourceRenderer;
import io.vidocq.vauban.processor.codegen.provider.ComponentProviderGenerator;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedShapeFromElements;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedSourceRenderer;
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
import io.vidocq.vauban.indexer.model.TypeInfo;
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
            "jakarta.enterprise.inject.Produces",
            // @Interceptor classes are bean-defining too (BeanDiscovery treats them as @Dependent
            // managed beans). Including them here brings them into the APT round/index so they get a
            // _Factory and a _VaubanComponents arm — the container then instantiates and field-injects
            // them in-module on the module path, with no `opens … to io.vidocq.vauban.core` (their
            // public @AroundInvoke method is reachable without opens via the F3 public-member guard).
            "jakarta.interceptor.Interceptor"
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
     * Generating {@code _Factory} / {@code _ClientProxy} in the user module for such classes
     * would create a split-package violation under Java Modules (the package is already exported by the
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

        // Remember which module(s) this compilation is building: reading the module *directives*
        // of a module still being compiled (as unloadableBcesOnCompilePath does for dependency
        // modules) completes it prematurely and freezes `provides` resolution errors before the
        // last-round generated classes exist. getModuleOf only walks ownership — no completion.
        for (var root : roundEnv.getRootElements()) {
            var module = processingEnv.getElementUtils().getModuleOf(root);
            if (module != null && !module.isUnnamed()) {
                compiledModuleNames.add(module.getQualifiedName().toString());
            }
        }

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

        // Injection points may target beans that live in a dependency. Such a type is absent from
        // this compilation's index — but javac can see it, so resolve it lazily rather than
        // rejecting a deployment the runtime container resolves without trouble (vauban#23).
        var dependencyTypes = resolveDependencyBeans(beans, indexBuilder, scanner, discoveryResult);
        if (dependencyTypes.indexGrew()) {
            index = indexBuilder.build();
        }

        // Validate deployment — unless explicitly skipped (cf. APT options on the class javadoc).
        // The runtime container always re-validates on container start; this only silences the
        // compile-time check for projects whose beans depend on BCE-produced injections that
        // aren't visible to the static analyser (typical pattern with @ConfigProperty / @Claim /
        // @RegisterRestClient used in test code without the producing BCE on the APT classpath).
        warnUnloadableBces();

        var errors = java.util.Collections.<DeploymentValidator.ValidationError>emptyList();
        if (validationEnabled()) {
            var assignability = new AssignabilityRules(index);
            var resolver = new BeanResolver(beans, assignability);
            var validator = new DeploymentValidator(beans, resolver, index);
            errors = validator.validate();
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Static deployment validation skipped (vauban.validation=false). "
                            + "Runtime container will still validate on start.");
        }

        boolean fatal = false;
        for (var error : errors) {
            // An unsatisfied point whose required type comes from a dependency is deferred to the
            // container, not rejected here: this compilation cannot see that module's producers,
            // and the runtime — which merges the vauban-beans.list of every archive — can.
            if (dependencyTypes.isDeferred(error)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "[Vauban] " + error.message()
                                + " — the required type is not declared by this compilation unit; "
                                + "resolution deferred to the runtime container.");
                continue;
            }
            // A missing proxy-entry constructor is fixable without touching the source: the
            // vauban-maven-plugin weaves a synthetic (ProxyLink) constructor at process-classes
            // (Vidocq/vauban#24 phase 2). Warn here; a deployment that skips the plugin still
            // fails at container start. Final classes/methods stay fatal — weaving cannot fix them.
            if (error.kind() == DeploymentValidator.ValidationError.Kind.UNPROXYABLE_BEAN) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "[Vauban] " + error.message()
                                + " — the vauban-maven-plugin weaves this constructor at "
                                + "process-classes; without the plugin this fails at container start.");
                continue;
            }
            fatal = true;
            var hint = "";
            if (error.kind() == DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY) {
                var unloadable = unloadableBcesOnCompilePath();
                if (!unloadable.isEmpty()) {
                    hint = " Hint: Build Compatible Extension(s) " + unloadable
                            + " found on the compile/module path cannot run (not on the"
                            + " annotation-processor path) and may contribute the missing bean —"
                            + " add their artifact(s) to <annotationProcessorPaths>.";
                }
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] " + error.message() + hint);
        }

        if (fatal) return true;

        // Generate code for each bean; also accumulate eligible managed classes for the
        // per-package _VaubanComponents provider.
        var providedClasses = new java.util.ArrayList<ProvidedClass>();
        // <Bean>_ClientProxy names emitted as SOURCE (top-level normal-scoped beans with an accessible
        // no-arg ctor): the per-package provider instantiates them in-module via createClientProxy, so
        // the bean package needs no opens/exports for proxy creation.
        var clientProxyFqns = new java.util.LinkedHashSet<String>();
        for (var bean : beans) {
            if (bean.kind() == BeanDescriptor.BeanKind.MANAGED) {
                var classInfo = index.getClassByName(bean.beanClass()).orElse(null);
                if (classInfo == null) continue;

                // Skip factory/proxy generation for classes that originate from dependency jars
                // (added via ScannedClasses.add() during @Discovery). Generating artifacts in
                // the user module for such classes would create a Java Modules split-package violation
                // because the package is already exported by the source jar.
                // The class remains in vauban-beans.list for injection resolution.
                if (externalClassNames.contains(bean.beanClass())) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "[Vauban] Skipping factory/proxy generation for external class "
                                    + bean.beanClass().value()
                                    + " (factory expected to be provided by the source jar)");
                    continue;
                }

                // SOURCE factory when possible (APT-first rule, CG-02): javac emits the
                // erasure bridge itself and the artifact is readable/debuggable. Source is
                // only viable when (a) the generated code can resolve BeanFactory at the
                // user module's compile time (bytecode references resolve at load time
                // instead — some consumers run this APT without a vauban-core compile
                // dependency), and (b) the bean is top-level with an accessible no-arg
                // constructor. Everything else keeps the bytecode fallback.
                var factoryBeanFqn = bean.beanClass().value();
                var factoryTypeElement = isTopLevelType(factoryBeanFqn)
                        ? processingEnv.getElementUtils().getTypeElement(factoryBeanFqn) : null;
                boolean beanFactoryResolvable = processingEnv.getElementUtils()
                        .getTypeElement("io.vidocq.vauban.core.BeanFactory") != null;
                if (factoryTypeElement != null && beanFactoryResolvable
                        && hasNonPrivateNoArgCtor(factoryTypeElement)) {
                    var factoryGen = io.vidocq.vauban.processor.codegen.factory.BeanFactorySourceRenderer
                            .render(factoryTypeElement);
                    writeSourceFile(factoryGen.className(), factoryGen.source());
                } else {
                    generateClass(BeanFactoryGenerator.generate(classInfo));
                }

                if (bean.scope().isNormal()) {
                    var proxyBeanFqn = bean.beanClass().value();
                    var proxyTypeElement = isTopLevelType(proxyBeanFqn)
                            ? processingEnv.getElementUtils().getTypeElement(proxyBeanFqn) : null;
                    if (proxyTypeElement != null && hasNonPrivateCtor(proxyTypeElement)) {
                        // SOURCE proxy: the sibling _VaubanComponents provider does
                        // `new <Bean>_ClientProxy()` in-module (createClientProxy), so the bean package
                        // needs no opens/exports for proxy creation. Source (not bytecode) so the
                        // provider source can reference it by name (resolved in a later APT round).
                        // The proxy ctor calls the simplest non-private super ctor with default values,
                        // so beans with only an injected (arg-bearing) constructor are covered too.
                        var gen = ClientProxySourceRenderer.render(proxyTypeElement,
                                processingEnv.getElementUtils(), processingEnv.getTypeUtils());
                        writeSourceFile(gen.className(), gen.source());
                        clientProxyFqns.add(proxyBeanFqn + "_ClientProxy");
                    } else {
                        // No accessible no-arg ctor (or a nested type): keep the bytecode proxy; the
                        // runtime instantiates it (its package must stay opened/exported as before).
                        generateClass(ClientProxyGenerator.generate(classInfo));
                    }
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
                            // Emit the subclass as readable Java source (not bytecode). A sibling
                            // generated source — the _VaubanComponents provider — can then reference
                            // <bean>$$Intercepted by name, so the provider stays source everywhere
                            // (no "generated source cannot see generated bytecode" wall). The bytecode
                            // emitter is kept for the runtime classpath fallback and the Maven plugin.
                            var gen = InterceptedSourceRenderer.render(shape);
                            writeSourceFile(gen.className(), gen.source());
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
            // Proxies of this package's beans (their FQN package equals the provider's package).
            var pkgProxies = clientProxyFqns.stream()
                    .filter(p -> packageOfFqn(p).equals(pkg.packageName()))
                    .toList();
            var className = writeComponentProvider(
                    pkg.packageName(), pkg.components(), pkg.fields(), pkg.methods(), pkgProxies);
            if (className != null) providerClassNames.add(className);
        }
        if (!providerClassNames.isEmpty()) {
            writeComponentProviderService(providerClassNames);
        }

        // Write META-INF/vauban-beans.list
        writeBeansList(beans);

        // Publish the (ProxyLink) weave plan for the auto-started javac plugin — see
        // io.vidocq.vauban.processor.weave (Vidocq/vauban#24, javac tier of the weaving).
        publishWeavePlan(beans, index);

        // Write BCE processed marker
        if (bceProcessed) {
            writeBceProcessedMarker();
        }

        return true;
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
            if (bean.kind() != BeanDescriptor.BeanKind.MANAGED || !bean.scope().isNormal()) continue;
            var fqn = bean.beanClass().value();
            if (!isTopLevelType(fqn) || externalClassNames.contains(bean.beanClass())) continue;
            if (planBeanWeaving(fqn, index, planned, lines)) {
                lines.add(io.vidocq.vauban.processor.weave.WeavePlan.Line.proxy(fqn + "_ClientProxy"));
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
        if (ctors.stream().anyMatch(io.vidocq.vauban.processor.codegen.proxy
                .ClientProxyShapeFromElements::isProxyLinkConstructor)) {
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
        if ("java.lang.Object".equals(superFqn)) {
            return io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain.NO_ARG;
        }
        var superCtors = javax.lang.model.util.ElementFilter
                .constructorsIn(superType.getEnclosedElements());
        if (superCtors.stream().anyMatch(io.vidocq.vauban.processor.codegen.proxy
                .ClientProxyShapeFromElements::isProxyLinkConstructor)) {
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
                && isTopLevelType(superFqn);
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
        } catch (Exception e) {
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
                if (module.isUnnamed()
                        || compiledModuleNames.contains(module.getQualifiedName().toString())) {
                    continue;
                }
                for (var directive : module.getDirectives()) {
                    if (directive.getKind() != ModuleElement.DirectiveKind.PROVIDES) continue;
                    var provides = (ModuleElement.ProvidesDirective) directive;
                    if (!bceFqn.contentEquals(provides.getService().getQualifiedName())) continue;
                    provides.getImplementations()
                            .forEach(impl -> declared.add(impl.getQualifiedName().toString()));
                }
            }
        } catch (Exception e) {
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
        } catch (Exception e) {
            // No such resource on the classpath — the normal case.
        }

        discoveredBceClasses.forEach(loaded -> declared.remove(loaded.getName()));
        unloadableBcesCache = declared;
        return declared;
    }

    /** One-shot warning naming the BCEs of {@link #unloadableBcesOnCompilePath()}. */
    private void warnUnloadableBces() {
        if (warnedUnloadableBces) return;
        warnedUnloadableBces = true;
        var unloadable = unloadableBcesOnCompilePath();
        if (unloadable.isEmpty()) return;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "[Vauban] " + unloadable.size() + " Build Compatible Extension(s) present on the"
                        + " compile/module path are NOT on the annotation-processor path and will"
                        + " not run: " + unloadable + ". Maven's <annotationProcessorPaths> narrows"
                        + " javac's -processorpath to its entries — add the artifact(s) shipping"
                        + " these extensions as additional <path> entries (or pass"
                        + " -Avauban.validation=false to defer bean validation to the runtime"
                        + " container).");
    }

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

    /**
     * Outcome of the dependency-bean resolution pass: whether the index gained anything, and which
     * types could not be turned into beans here and must therefore be left to the container.
     *
     * @param indexGrew     whether a dependency type was added to the index
     * @param deferredTypes fully-qualified names that exist on the compile classpath but did not
     *                      yield a bean in this compilation — a producer in the other module, say
     */
    private record DependencyTypes(boolean indexGrew, Set<String> deferredTypes) {

        /** Whether this error is about a type this compilation cannot see the whole story of. */
        boolean isDeferred(DeploymentValidator.ValidationError error) {
            if (error.kind() != DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY) {
                return false;
            }
            return deferredTypes.stream().anyMatch(fqn -> error.message().contains(fqn));
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
     * {@link #externalClassNames} so no {@code _Factory} / {@code _ClientProxy} is emitted for it
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
            if (indexBuilder.contains(name)) {
                continue;
            }
            var typeElement = elements.getTypeElement(name.value());
            if (typeElement == null) {
                // Not on the compile classpath either: a genuine mistake, left to the validator.
                continue;
            }
            indexBuilder.add(scanner.scan(typeElement));
            added.add(name);
        }

        if (added.isEmpty()) {
            return new DependencyTypes(false, Set.of());
        }

        var enrichedIndex = indexBuilder.build();
        var discovery = new BeanDiscovery(enrichedIndex);
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

        for (var name : added) {
            if (!becameBeans.contains(name) && mayBeSatisfiedElsewhere(elements.getTypeElement(name.value()))) {
                deferred.add(name.value());
            }
        }
        return new DependencyTypes(true, deferred);
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
            case TypeInfo.ClassType ct -> out.add(ct.name());
            case TypeInfo.ParameterizedType pt -> {
                out.add(pt.rawType());
                pt.typeArguments().forEach(arg -> collectClassNames(arg, out));
            }
            case TypeInfo.ArrayType at -> collectClassNames(at.componentType(), out);
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
        // An @Interceptor class carries the interceptor binding to associate itself with its targets,
        // but it is never itself an interception target — do not generate a $$Intercepted for it.
        for (var am : beanElement.getAnnotationMirrors()) {
            if (am.getAnnotationType().toString().equals("jakarta.interceptor.Interceptor")) return false;
        }
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

    /**
     * An interceptor binding is an annotation type itself meta-annotated {@code @InterceptorBinding}.
     *
     * <p>Detected by NAME over the annotation type's own meta-mirrors, NOT via
     * {@code getAnnotation(InterceptorBinding.class)}. The class-literal form forces javac to complete
     * the {@code jakarta.interceptor.InterceptorBinding} symbol; that completion throws a
     * {@code CompletionFailure} when the binding marker is declared in a dependency module that only
     * {@code requires static jakarta.interceptor} (e.g. heisenberg's {@code @FaultToleranceBinding}) and
     * is processed from a downstream module on a strict module path — the marker module's read edge to
     * {@code jakarta.interceptor} is absent from the consumer's module graph. Reading a meta-mirror's
     * type name only touches the constant pool, so it never triggers completion — the same name-based
     * pattern already used for the {@code @Interceptor} check in {@link #isInterceptedTarget}.
     */
    private static boolean isInterceptorBinding(javax.lang.model.element.AnnotationMirror am) {
        for (var meta : am.getAnnotationType().asElement().getAnnotationMirrors()) {
            if (meta.getAnnotationType().toString().equals("jakarta.interceptor.InterceptorBinding")) {
                return true;
            }
        }
        return false;
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
     * {@code true} when {@code te} has a non-private constructor the proxy can call via {@code super}
     * (a subclass cannot reach a private super ctor): either no explicit constructor (implicit no-arg)
     * or at least one non-private declared ctor. Gates SOURCE client-proxy generation — the proxy
     * calls the simplest non-private super ctor with default values; an all-private-ctor bean is
     * unproxyable by subclassing and keeps the bytecode proxy + runtime fallback.
     */
    /**
     * True if the type has an accessible (non-private) no-arg constructor — explicit or
     * implicit. Gates SOURCE factory generation: the rendered {@code new Bean()} must
     * compile, whereas the bytecode factory defers resolution to load time.
     */
    private static boolean hasNonPrivateNoArgCtor(javax.lang.model.element.TypeElement te) {
        var ctors = javax.lang.model.util.ElementFilter.constructorsIn(te.getEnclosedElements());
        if (ctors.isEmpty()) {
            return true; // implicit no-arg constructor
        }
        for (var c : ctors) {
            if (c.getParameters().isEmpty()
                    && !c.getModifiers().contains(javax.lang.model.element.Modifier.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonPrivateCtor(javax.lang.model.element.TypeElement te) {
        var ctors = javax.lang.model.util.ElementFilter.constructorsIn(te.getEnclosedElements());
        if (ctors.isEmpty()) {
            return true; // implicit no-arg constructor
        }
        for (var c : ctors) {
            if (!c.getModifiers().contains(javax.lang.model.element.Modifier.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    /** Package of a top-level FQN — the substring before the last dot, or {@code ""} (default package). */
    private static String packageOfFqn(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /**
     * Generates the {@code <pkg>._VaubanComponents} provider for one package's beans (in-module
     * instantiation, field injection and method invocation) and returns its fully-qualified name so
     * the caller can list it (with the others) in the single class-path service file. The fields and
     * methods passed in already belong to {@code pkg} (the caller groups by package), so no filtering
     * is needed — a co-located provider can reach its own package's package-private members.
     *
     * <p>Always emitted as readable Java <strong>source</strong>. A package with an intercepted bean
     * has a {@code <bean>$$Intercepted} component; because that subclass is now itself generated as
     * source (by {@link InterceptedSourceRenderer}), the provider source can reference it by name —
     * javac resolves both in a later round. (Previously the provider had to be bytecode for such
     * packages, since a generated source could not see a Filer-emitted {@code $$Intercepted}.)
     */
    private String writeComponentProvider(String pkg,
            List<io.vidocq.vauban.indexer.codegen.Component> components,
            List<io.vidocq.vauban.indexer.codegen.FieldInject> fieldInjects,
            List<io.vidocq.vauban.indexer.codegen.MethodInvoke> methodInvokes,
            List<String> clientProxyFqns) {
        var gen = ComponentProviderGenerator.generateFrom(pkg, components, fieldInjects, methodInvokes,
                clientProxyFqns);
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

    /** Write a generated Java source file (compiled by javac in a subsequent APT round). */
    private void writeSourceFile(String className, String source) {
        try {
            var file = processingEnv.getFiler().createSourceFile(className);
            try (var w = file.openWriter()) {
                w.write(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] Failed to write generated source " + className + ": " + e.getMessage());
        }
    }
}
