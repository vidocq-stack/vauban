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
package io.vidocq.vauban.tests.architecture;

import java.util.Map;
import java.util.TreeMap;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Vauban's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Vauban's packages; each names the document
 * it comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the
 * shipped classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The package of each shipped module: {@code io.vidocq.vauban.api} (vauban-api),
 * {@code io.vidocq.vauban.classloader.spi} (vauban-classloader-spi), {@code io.vidocq.vauban.weaver}
 * (vauban-weaver), {@code io.vidocq.vauban.classloader} (vauban-classloader, the package itself),
 * {@code io.vidocq.vauban.indexer} (vauban-indexer), {@code io.vidocq.vauban.core} (vauban-core),
 * {@code io.vidocq.vauban.processor} (vauban-processor), {@code io.vidocq.vauban.sjar} (vauban-sjar),
 * {@code io.vidocq.vauban.webcontexts} (vauban-webcontexts) and {@code io.vidocq.vauban.naming} (vauban-naming).
 */
final class ArchitectureRules {

    private static final String ROOT = "io.vidocq.vauban.";
    private static final String ZERO_DEPENDENCY = "workspace AGENTS.md, zero external dependency for what ships";
    private static final String STATIC_GENERATION = "workspace AGENTS.md, maximum static code generation";
    private static final String INTERNALS = "internals.adoc";

    private static final String API = ROOT + "api..";
    private static final String SPI = ROOT + "classloader.spi..";
    private static final String WEAVER = ROOT + "weaver..";
    /** The package itself, not {@code classloader.spi}, which is another module. */
    private static final String CLASSLOADER = ROOT + "classloader";

    private static final String INDEXER = ROOT + "indexer..";
    private static final String CORE = ROOT + "core..";
    private static final String PROCESSOR = ROOT + "processor..";
    private static final String SJAR = ROOT + "sjar..";
    private static final String WEBCONTEXTS = ROOT + "webcontexts..";
    private static final String NAMING = ROOT + "naming..";

    /**
     * The module descriptor of vauban-api reads the CDI API, which holds {@code jakarta.inject} and the
     * annotations.
     */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {API},
            Packages.of(Jdk.RUNTIME, "jakarta.enterprise..", "jakarta.inject..", API),
            ZERO_DEPENDENCY + "; module-info.java of vauban-api");

    /** The module descriptor of vauban-classloader-spi reads nothing, and the JDK for the key cipher. */
    static final ArchRule SPI_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {SPI}, Packages.of(Jdk.RUNTIME, SPI), ZERO_DEPENDENCY + "; module-info.java of vauban-classloader-spi");

    /** The module descriptor of vauban-weaver reads java.instrument, and jdk.attach statically. */
    static final ArchRule WEAVER_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {WEAVER},
            Packages.of(Jdk.RUNTIME, "com.sun.tools.attach..", WEAVER),
            ZERO_DEPENDENCY + "; module-info.java of vauban-weaver");

    /** The module descriptor of vauban-classloader reads vauban-classloader-spi and vauban-weaver. */
    static final ArchRule CLASSLOADER_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {CLASSLOADER},
            Packages.of(Jdk.RUNTIME, CLASSLOADER, SPI, WEAVER),
            ZERO_DEPENDENCY + "; module-info.java of vauban-classloader");

    /** The module descriptor of vauban-indexer reads vauban-classloader-spi alone. */
    static final ArchRule INDEXER_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {INDEXER}, Packages.of(Jdk.RUNTIME, INDEXER, SPI), ZERO_DEPENDENCY + "; module-info.java of vauban-indexer");

    /**
     * The module descriptor of vauban-core reads vauban-api, vauban-indexer, vauban-classloader-spi,
     * vauban-weaver, vauban-classloader, the CDI API (with its lang-model) and the Expression Language API.
     * The Jakarta APIs a CDI container implements or reads: CDI, Inject, Annotation, Interceptors, EL.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {CORE},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "jakarta.interceptor..",
                    "jakarta.el..",
                    CORE,
                    API,
                    INDEXER,
                    SPI,
                    WEAVER,
                    CLASSLOADER),
            ZERO_DEPENDENCY + "; module-info.java of vauban-core");

    /**
     * The module descriptor of vauban-processor reads vauban-core, vauban-indexer and vauban-weaver, and the
     * compiler APIs ({@code java.compiler}, {@code jdk.compiler}). It is a build-time tool, never shipped in
     * an application.
     */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {PROCESSOR},
            Packages.of(
                    Jdk.COMPILER,
                    "com.sun.source..",
                    "jakarta.enterprise..",
                    PROCESSOR,
                    API,
                    CORE,
                    INDEXER,
                    WEAVER),
            ZERO_DEPENDENCY + "; module-info.java of vauban-processor");

    /** The module descriptor of vauban-sjar reads vauban-classloader-spi alone, and the JDK ciphers. */
    static final ArchRule SJAR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {SJAR}, Packages.of(Jdk.RUNTIME, SJAR, SPI), ZERO_DEPENDENCY + "; module-info.java of vauban-sjar");

    /** The module descriptor of vauban-webcontexts reads the CDI API alone. */
    static final ArchRule WEBCONTEXTS_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {WEBCONTEXTS},
            Packages.of(Jdk.RUNTIME, "jakarta.enterprise..", WEBCONTEXTS),
            ZERO_DEPENDENCY + "; module-info.java of vauban-webcontexts");

    /** The module descriptor of vauban-naming reads vauban-api, the CDI API and {@code java.naming}. */
    static final ArchRule NAMING_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {NAMING},
            Packages.of(Jdk.RUNTIME, "javax.naming..", "jakarta.enterprise..", "jakarta.inject..", NAMING, API),
            ZERO_DEPENDENCY + "; module-info.java of vauban-naming");

    /**
     * Workspace AGENTS.md, dependency graph: "the foundational bricks (chappe + vauban + champollion) have no
     * dependencies between them". Vauban never reads Chappe or Champollion.
     */
    static final ArchRule FOUNDATIONAL_BRICKS = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.vauban.."},
            new String[] {"io.vidocq.chappe..", "io.vidocq.champollion.."},
            "workspace AGENTS.md, dependency graph");

    /** Workspace AGENTS.md: {@code *-api} never depends on {@code *-core}. */
    static final ArchRule API_NEVER_DEPENDS_ON_CORE = VidocqArchRules.neverDependOn(
            new String[] {API}, new String[] {CORE}, "workspace AGENTS.md, layering; module-info.java of vauban-api");

    /**
     * The module descriptors: vauban-api, vauban-classloader-spi and vauban-weaver are the bottom layers and read
     * no other Vauban module; vauban-classloader builds on the last two, vauban-indexer on the SPI, vauban-core
     * on all of them; vauban-processor, a build-time tool, builds on vauban-core and nothing builds on it.
     * vauban-sjar, vauban-webcontexts and vauban-naming are leaves: the first reads the SPI, the second nothing,
     * the third vauban-api.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "module-info.java files of the shipped modules",
            Layer.named("API").in(API).accessesNoLayer().accessibleOnlyBy("Core", "Processor", "Naming"),
            Layer.named("Classloader SPI")
                    .in(SPI)
                    .accessesNoLayer()
                    .accessibleOnlyBy("Classloader", "Indexer", "Core", "Sjar"),
            Layer.named("Weaver").in(WEAVER).accessesNoLayer().accessibleOnlyBy("Classloader", "Core", "Processor"),
            Layer.named("Classloader").in(CLASSLOADER).accessibleOnlyBy("Core"),
            Layer.named("Indexer").in(INDEXER).accessibleOnlyBy("Core", "Processor"),
            Layer.named("Core").in(CORE).accessibleOnlyBy("Processor"),
            Layer.named("Processor").in(PROCESSOR).accessibleByNone(),
            Layer.named("Sjar").in(SJAR).accessibleByNone(),
            Layer.named("Webcontexts").in(WEBCONTEXTS).accessesNoLayer().accessibleByNone(),
            Layer.named("Naming").in(NAMING).accessibleByNone());

    /**
     * The documented reflective fallbacks, each with its reason. Vauban's container reads by reflection what no
     * generated provider or build-time index covers (internals.adoc, "Which path ran" and AOT compatibility),
     * and a few classes use {@code java.lang.reflect.Type}, the type model of the CDI API. Every entry is a
     * top-level class (its nested classes share the entry) and {@link ArchitectureRulesSelfTest} fails on an
     * entry that no longer needs it.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = fallbacks();

    /**
     * Workspace AGENTS.md, "Maximum static code generation": what would be reflection at run time is generated at
     * build time. internals.adoc: the container reads by reflection only the documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, STATIC_GENERATION + "; " + INTERNALS + ", Which path ran and AOT compatibility");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O": the async observers run on a virtual thread per
     * task (internals.adoc, Threading model), and Vauban creates no platform thread.
     */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads; " + INTERNALS + ", Threading model");

    /**
     * The classes that keep a {@code ThreadLocal}, each with its reason. The request context is carried by a
     * {@code ScopedValue} (internals.adoc, Threading model); a thread-local remains where a scoped value cannot
     * bind and unbind from separate callbacks.
     */
    static final Map<String, String> THREAD_LOCAL_HOLDERS = Map.of(
            "io.vidocq.vauban.core.context.RequestContext",
            "imperative activation (activate/deactivate), the built-in way outside a ScopedValue scope; a"
                    + " ScopedValue carries the request inside Cassini and Foy",
            "io.vidocq.vauban.webcontexts.SessionContext",
            "the session bound to the current thread: a web container binds and unbinds it from separate"
                    + " request-listener callbacks, which a scoped value cannot span",
            "io.vidocq.vauban.webcontexts.WebContexts",
            "binds and unbinds the web contexts of the current thread from separate request-listener callbacks");

    /**
     * Workspace AGENTS.md ("Virtual Threads everywhere") and internals.adoc, Threading model: the request context
     * is carried by a {@code ScopedValue}, not a {@code ThreadLocal}. Applied to the shipped classes without
     * {@link #THREAD_LOCAL_HOLDERS}.
     */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal does not follow a request across virtual threads, a ScopedValue does",
            "workspace AGENTS.md, Virtual Threads; " + INTERNALS + ", Threading model");

    private ArchitectureRules() {
        // Rules only.
    }

    private static Map<String, String> fallbacks() {
        Map<String, String> all = new TreeMap<>();

        add(
                all,
                "loads a class by name, without initialising it, from a name recorded at build time (the bean index,"
                        + " a service or extension descriptor, a placed proxy) or given by the application",
                "classloader.Launch",
                "core.container.ContainerScanner",
                "core.container.SyntheticComponentRegistrar",
                "core.container.VaubanContainerBuilder",
                "core.extensions.SyntheticParamCodec",
                "core.extensions.VaubanSyntheticBeanBuilder",
                "core.loading.DeploymentClasses",
                "core.opens.OpensApplier",
                "naming.BeanDeclarations");

        add(
                all,
                "reads annotations by reflection, the reads -Dvauban.annotations.reflection=allow|warn|forbid"
                        + " governs: a live annotation the index does not describe, an instance's members, an"
                        + " annotation type's declaration",
                "core.annotation.AnnotationInstances",
                "core.annotation.AnnotationReflection",
                "core.annotation.AnnotationTypes",
                "core.annotation.AnnotationValues",
                "core.annotation.CoreAnnotationInstanceFactory",
                "core.bean.discovery.QualifierResolver",
                "core.bean.discovery.StereotypeResolver",
                "core.bean.validation.AnnotationTypes",
                "core.container.QualifierHelper",
                "core.extensions.VaubanAnnotationBuilder",
                "core.extensions.VaubanClassConfig");

        add(
                all,
                "discovers and validates a bean class that no build-time index describes (class path without the"
                        + " processor, an archive scanned at boot), and answers the CDI metadata API"
                        + " (AnnotatedType, InjectionPoint, BeanManager) the specification hands out as"
                        + " java.lang.reflect objects",
                "core.bean.discovery.BeanDiscovery",
                "core.bean.discovery.InterceptorDiscovery",
                "core.bean.discovery.ObserverDisposerDiscovery",
                "core.bean.validation.DeploymentValidator",
                "core.codegen.BeanMembers",
                "core.codegen.ClassTypeView",
                "core.codegen.ShadowedDefaults",
                "core.container.ClassMetadataCache",
                "core.container.InstanceImpl",
                "core.container.ReflectionValidator",
                "core.container.VaubanAnnotatedType",
                "core.container.VaubanBeanManager",
                "core.container.VaubanInjectionPoint",
                "core.event.EventImpl",
                "core.interceptor.BusinessMethods",
                "core.interceptor.InterceptionTargets");

        add(
                all,
                "the reflective tier that instantiates, injects and invokes when no generated _VaubanComponents"
                        + " provider covers a type (the class path, a producer only known at run time), logged at"
                        + " boot, and what codegenCoverage() reports as REFLECTION",
                "core.container.BeanInjector",
                "core.container.BeanLifecycle",
                "core.container.CodegenCoverage",
                "core.container.DisposerInvoker",
                "core.container.IndexPlans",
                "core.container.InterceptorBeanWrapper",
                "core.container.ManagedBean",
                "core.container.VaubanContainer",
                "core.container.VaubanInterceptor",
                "core.container.VaubanLookup",
                "core.event.EventDispatcher",
                "core.event.VaubanObserverMethod",
                "core.interceptor.InterceptorManager",
                "core.interceptor.VaubanInvocationContext");

        add(
                all,
                "the Build Compatible Extensions and Invokers API (CDI Lite) hands extensions and users"
                        + " java.lang.reflect Method and Type objects, and the container reads an extension class's"
                        + " methods, which no build-time index holds",
                "core.extensions.BceProcessor",
                "core.extensions.BceTypeMatcher",
                "core.extensions.EnhancementApplier",
                "core.extensions.ExtensionMethodValidator",
                "core.extensions.ReflectionMethodInfo",
                "core.extensions.VaubanInvoker",
                "core.extensions.VaubanInvokerBuilder",
                "core.extensions.VaubanInvokerFactory");

        add(
                all,
                "generates at run time, with the Class-File API or java.lang.reflect.Proxy, the $$Intercepted"
                        + " subclass and the client proxy of a type the processor did not cover, and announces it"
                        + " in the framed RUNTIME CODE GENERATION FALLBACK warning",
                "core.interceptor.InterceptorSubclassGenerator",
                "core.proxy.ClientProxyShape",
                "core.proxy.RuntimeClientProxyGenerator");

        add(
                all,
                "java.lang.reflect.Type and its sub-interfaces, the type model of the CDI API (BeanManager,"
                        + " Instance, Event, InjectionPoint signatures), used as data to compare and print types,"
                        + " never to reach a member; and java.lang.reflect.AccessFlag, the class-file flag enum",
                "core.container.BuiltInBean",
                "core.container.VaubanCDI",
                "core.extensions.SyntheticMetadataSerializer",
                "core.extensions.VaubanParameters",
                "core.extensions.VaubanSyntheticObserverBuilder",
                "core.types.TypeHierarchyResolver",
                "core.types.TypeInfoUtils",
                "weaver.ProxyLinkWeaver");

        add(
                all,
                "build-time tool, never shipped in an application: loads the classes it processes by name and reads"
                        + " the methods of a build compatible extension",
                "processor.VaubanProcessor");

        return Map.copyOf(all);
    }

    private static void add(Map<String, String> all, String reason, String... classes) {
        for (String name : classes) {
            String qualified = ROOT + name;
            if (all.put(qualified, reason) != null) {
                throw new IllegalStateException("two reasons for " + qualified);
            }
        }
    }
}
