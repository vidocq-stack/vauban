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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.discovery.PackageInfos;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.extensions.internal.DeploymentFailures;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.build.compatible.spi.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Processes Build Compatible Extensions (BCE) — CDI 4.1 spec chapter 28.
 * Handles @Enhancement, @Registration, and @Synthesis phases.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class BceProcessor {


    /**
     * Runs only the {@code @Enhancement} phase of the BCE lifecycle for the given classes.
     * Used at runtime for beans loaded from JARs that were not pre-processed at compile time
     * (no {@code META-INF/vauban-bce-processed} marker).
     *
     * @param bceClasses     BCE extension classes (must already be instantiated or instantiable)
     * @param archiveClasses non-bean classes to enhance (e.g. {@code @Path} without scope)
     * @param index          the current VaubanIndex
     * @param classLoader    ClassLoader for type resolution
     * @return enhancement modifications keyed by class DotName
     * @throws jakarta.enterprise.inject.spi.DefinitionException when an {@code @Enhancement} method has a parameter
     *         of an unsupported type, reported before any call (BUG-20261118-22)
     * @throws jakarta.enterprise.inject.spi.DefinitionException when an extension threw, at start as under
     *         {@code vauban:generate} (BUG-20261118-05, BUG-20261118-57)
     * @throws jakarta.enterprise.inject.spi.DeploymentException when an extension reported an error through
     *         {@code Messages}
     */
    public static Map<DotName, List<VaubanClassConfig>> processEnhancementOnly(
            List<Class<?>> bceClasses,
            List<Class<?>> archiveClasses,
            VaubanIndex index,
            ClassLoader classLoader) {
        return processEnhancementOnly(bceClasses, archiveClasses, index, classLoader, Map.of());
    }

    /**
     * {@link #processEnhancementOnly(List, List, VaubanIndex, ClassLoader)}, an extension called on the instance
     * {@code instances} holds for its class, the one its {@code @Discovery} ran on, so that it keeps one state across
     * the phases; on an instance of its own when there is none.
     */
    public static Map<DotName, List<VaubanClassConfig>> processEnhancementOnly(
            List<Class<?>> bceClasses,
            List<Class<?>> archiveClasses,
            VaubanIndex index,
            ClassLoader classLoader,
            Map<Class<?>, Object> instances) {
        var lookup = new IndexLookup(index);
        var modifications = new HashMap<DotName, List<VaubanClassConfig>>();
        var failures = new PhaseFailures();
        var archiveTypes = typesOf(archiveClasses);
        failOnInvalidEnhancementMethods(bceClasses);

        for (var bceClass : bceClasses) {
            try {
                ExtensionAsks.<Exception>of(bceClass, () -> {
                    var known = instances.get(bceClass);
                    var bce = known != null ? known : instantiateBce(bceClass);
                    processEnhancement(bce, bceClass, List.of(), archiveTypes, lookup, classLoader,
                            new EnhancementSink(failures, modifications));
                });
            } catch (Exception e) {
                failedToBuild(bceClass, e, failures);
            }
        }

        failures.failOnErrors();
        return modifications;
    }

    /**
     * Replay {@code @Enhancement} for a precise list of (BCE, target) pairs.
     * Used at runtime to rejoin BCE-applied modifications declared in
     * {@code META-INF/vauban-bce-runtime.list} for pre-processed JARs whose
     * bytecode has NOT been rewritten (the APT only inscribes the marker).
     *
     * <p>Bypasses all class/annotation matching: the APT has already determined
     * which classes match. Each (bce, target) pair is invoked directly.
     *
     * @param replayPairs ordered list of (bceClass, targetClass) pairs
     * @param index       current VaubanIndex (must contain {@code targetClass})
     * @return enhancement modifications keyed by target DotName
     * @throws jakarta.enterprise.inject.spi.DefinitionException when an {@code @Enhancement} method has a parameter
     *         of an unsupported type, reported before any call (BUG-20261118-22)
     * @throws jakarta.enterprise.inject.spi.DefinitionException when an extension threw (BUG-20261118-05,
     *         BUG-20261118-57)
     * @throws jakarta.enterprise.inject.spi.DeploymentException when an extension reported an error through
     *         {@code Messages}
     */
    public static Map<DotName, List<VaubanClassConfig>> replayEnhancementForTargets(
            List<Map.Entry<Class<?>, Class<?>>> replayPairs,
            VaubanIndex index) {
        var lookup = new IndexLookup(index);
        var modifications = new HashMap<DotName, List<VaubanClassConfig>>();
        var sink = new EnhancementSink(new PhaseFailures(), modifications);
        failOnInvalidEnhancementMethods(replayPairs.stream().<Class<?>>map(Map.Entry::getKey).distinct().toList());

        for (var pair : replayPairs) {
            var bceClass = pair.getKey();
            var targetName = DotName.of(pair.getValue().getName());
            try {
                ExtensionAsks.<Exception>of(bceClass, () -> {
                    var bce = instantiateBce(bceClass);
                    for (var method : getDeclaredMethodsSafe(bceClass)) {
                        if (method.getAnnotation(Enhancement.class) == null) continue;
                        makeAccessibleSafe(method);
                        var paramKind = detectEnhancementParamKind(method);
                        invokeEnhancement(method, bce, paramKind, targetName, lookup, sink);
                    }
                });
            } catch (Exception e) {
                failedToBuild(bceClass, e, sink.failures());
            }
        }

        sink.failures().failOnErrors();
        return modifications;
    }

    /**
     * Throws the {@code DefinitionException} of the {@code @Enhancement} methods of {@code bceClasses} whose parameters
     * the phase does not support, before any of them is called, as the full cycle does (BUG-20261118-22).
     */
    private static void failOnInvalidEnhancementMethods(List<Class<?>> bceClasses) {
        var definitionErrors = new ArrayList<String>();
        for (var bceClass : bceClasses) {
            ExtensionMethodValidator.validateEnhancementMethods(bceClass, definitionErrors);
        }
        DeploymentFailures.failOnDefinitionErrors(definitionErrors);
    }

    /**
     * Records what an extension threw outside its methods, where {@code processEnhancement} records what they threw:
     * a constructor that fails is a failure of the phase too (BUG-20261118-05), a definition error
     * (BUG-20261118-57).
     */
    private static void failedToBuild(Class<?> bceClass, Exception e, PhaseFailures failures) {
        var cause = unwrapped(e);
        failures.thrown("@Enhancement of " + bceClass.getName() + " failed: " + cause, cause);
    }

    /**
     * Where the failures of the phases go, one mapping for all of them (BUG-20261118-57): an exception an extension's
     * constructor, or its {@code @Discovery}, {@code @Enhancement}, {@code @Registration} or {@code @Synthesis} method,
     * throws is a definition error, as CDI 4.1 treats one of a {@code BeforeBeanDiscovery} (BUG-20261118-04), a
     * {@code ProcessAnnotatedType}, a {@code ProcessBean} or an {@code AfterBeanDiscovery} observer, the portable
     * events of the same phases. A {@code DeploymentException} it throws stays a deployment problem, one rule
     * everywhere: CDI 4.1 makes an invoker built for an invalid target method a deployment problem, which the invoker
     * factory throws, and the CDI TCK expects. An exception of
     * {@code @Validation}, as one of an {@code AfterDeploymentValidation} observer, and an error reported through
     * {@code Messages}, as its Javadoc says, are deployment problems. Each list of causes holds the exceptions behind
     * the errors of its kind, which the exception of the failed deployment carries (VAU-BCE-003).
     */
    record PhaseFailures(List<String> definitionErrors, List<Throwable> definitionErrorCauses,
                         List<String> deploymentErrors, List<Throwable> deploymentErrorCauses) {

        PhaseFailures() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        /**
         * What an extension's constructor, or its {@code @Discovery}, {@code @Enhancement}, {@code @Registration} or
         * {@code @Synthesis} method threw.
         */
        void thrown(String error, Throwable cause) {
            if (cause instanceof jakarta.enterprise.inject.spi.DeploymentException) {
                deploymentProblem(error, cause);
            } else {
                definitionErrors.add(error);
                definitionErrorCauses.add(cause);
            }
        }

        /** What a {@code @Validation} method threw. */
        void deploymentProblem(String error, Throwable cause) {
            deploymentErrors.add(error);
            deploymentErrorCauses.add(cause);
        }

        /** The errors a method reported through {@code Messages}. */
        void reported(List<String> errors) {
            deploymentErrors.addAll(errors);
        }

        /** Throws the {@code DefinitionException} of the definition errors, then the deployment problems'. */
        void failOnErrors() {
            DeploymentFailures.failOnDefinitionErrors(definitionErrors, definitionErrorCauses);
            DeploymentFailures.failOnDeploymentErrors(deploymentErrors, deploymentErrorCauses);
        }
    }

    /**
     * Result of BCE processing: synthetic bean definitions and collected errors.
     */
    public record Result(
            List<VaubanSyntheticBeanBuilder<?>> syntheticBeans,
            List<VaubanSyntheticObserverBuilder<?>> syntheticObservers,
            List<String> definitionErrors,
            /*
             * The exceptions behind definitionErrors: what an @Enhancement, a @Registration or a @Synthesis method
             * threw (BUG-20261118-57).
             */
            List<Throwable> definitionErrorCauses,
            List<String> deploymentErrors,
            Map<io.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>> enhancementModifications,
            /*
             * Typed exceptions behind deploymentErrors (VAU-BCE-003): specs define the
             * exception a failed deployment must surface (e.g. MP Fault Tolerance's
             * FaultToleranceDefinitionException) and TCKs assert it through the
             * DeploymentException cause chain — the flattened messages alone lose it.
             */
            List<Throwable> deploymentErrorCauses,
            /*
             * The invokers whose argument lookups the caller checks, once it holds every bean of the deployment
             * (BUG-20261117-45); none when process checked them itself.
             */
            InvokerLookups invokerLookups
    ) {}

    /**
     * The invokers the extensions built, and what their argument lookups are checked with. A start checks them once it
     * registered the synthetic beans of the frozen extensions and of the archives the processor processed: CDI 4.1,
     * "Configuring lookups", identifies a looked-up bean among every bean of the deployment, and a lookup only one of
     * those satisfies was refused (BUG-20261117-45).
     */
    public record InvokerLookups(List<VaubanInvokerFactory> factories, VaubanIndex index, ClassLoader classLoader,
                                 RegisteredQualifiers qualifiers) {

        static final InvokerLookups NONE = new InvokerLookups(List.of(), null, null, RegisteredQualifiers.NONE);

        public InvokerLookups {
            factories = List.copyOf(factories);
        }

        /** The unsatisfied and ambiguous argument lookups, each bean identified among {@code allBeans}. */
        public List<String> errors(List<BeanDescriptor> allBeans) {
            var errors = new ArrayList<String>();
            ExtensionMethodValidator.validateInvokerLookups(factories, allBeans, index, classLoader, qualifiers,
                    errors);
            return errors;
        }
    }

    /**
     * What {@code @Discovery} did. {@code definitionErrors} and their causes are the exceptions its methods threw: a
     * definition error, as CDI 4.1 treats an exception of a {@code BeforeBeanDiscovery} or an
     * {@code AfterTypeDiscovery} observer, the portable events of the same phase (BUG-20261118-04), except a
     * {@code DeploymentException}, which joins the deployment problems (BUG-20261118-57).
     * {@code deploymentErrors} are those its methods reported through {@code Messages}: a deployment problem, as the
     * Javadoc of {@code Messages} says (BUG-20261118-22).
     */
    public record DiscoveryResult(
            VaubanMetaAnnotations metaAnnotations,
            VaubanScannedClasses scannedClasses,
            Map<Class<?>, Object> bceInstances,
            List<String> definitionErrors,
            List<Throwable> definitionErrorCauses,
            List<String> deploymentErrors,
            /* The DeploymentExceptions its methods threw (BUG-20261118-57). */
            List<Throwable> deploymentErrorCauses
    ) {}

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    public static DiscoveryResult processDiscovery(List<Class<?>> bceClasses, IndexLookup lookup) {
        var metaAnnotations = new VaubanMetaAnnotations(lookup);
        var scannedClasses = new VaubanScannedClasses();
        var bceInstances = new LinkedHashMap<Class<?>, Object>();
        var failures = new PhaseFailures();
        var definitionErrors = failures.definitionErrors();
        var deploymentErrors = failures.deploymentErrors();

        for (var bceClass : bceClasses) {
            try {
                ExtensionAsks.<Exception>of(bceClass, () -> {
                    var bce = instantiateBce(bceClass);
                    bceInstances.put(bceClass, bce);
                    for (var method : getDeclaredMethodsSafe(bceClass)) {
                        if (method.getAnnotation(Discovery.class) != null
                                && ExtensionMethodValidator.validDiscoveryMethod(bceClass, method, definitionErrors)) {
                            invokeDiscovery(method, bce, metaAnnotations, scannedClasses, deploymentErrors);
                        }
                    }
                });
            } catch (Exception e) {
                // The constructor runs here: a DeploymentException of either stays a deployment problem, as in the
                // other phases (BUG-20261118-57)
                var cause = unwrapped(e);
                failures.thrown("@Discovery of " + bceClass.getName() + " failed: " + cause, cause);
            }
        }

        return new DiscoveryResult(metaAnnotations, scannedClasses, bceInstances, definitionErrors,
                failures.definitionErrorCauses(), deploymentErrors, failures.deploymentErrorCauses());
    }

    /**
     * Calls the {@code @Discovery} {@code method}, whose parameter types {@link ExtensionMethodValidator} checked, with
     * the phase's arguments; the errors it reports through {@code Messages} join {@code errors}, the deployment
     * problems of the phase.
     */
    private static void invokeDiscovery(Method method, Object bce, MetaAnnotations metaAnnotations,
                                        ScannedClasses scannedClasses, List<String> errors)
            throws ReflectiveOperationException {
        makeAccessibleSafe(method);
        var messages = new VaubanMessages();
        var params = method.getParameterTypes();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (MetaAnnotations.class.isAssignableFrom(params[i])) {
                args[i] = metaAnnotations;
            } else if (ScannedClasses.class.isAssignableFrom(params[i])) {
                args[i] = scannedClasses;
            } else {
                args[i] = messages;
            }
        }
        method.invoke(bce, args);
        errors.addAll(messages.getErrors());
    }

    /**
     * Process all BCE classes through their lifecycle phases.
     * Reuses instances from Discovery to maintain state across phases.
     */
    public static Result process(List<Class<?>> bceClasses,
                                 List<BeanDescriptor> beans,
                                 VaubanIndex index,
                                 ClassLoader classLoader,
                                 Map<Class<?>, Object> bceInstances,
                                 List<Class<?>> allArchiveClasses) {
        return process(bceClasses, beans, List.of(), List.of(), index, classLoader, bceInstances, allArchiveClasses,
                _ -> null, RegisteredQualifiers.NONE, false);
    }

    /**
     * Runs the phases after {@code @Discovery}, each one for every extension before the next phase
     * starts, as CDI 4.1 requires: an extension's {@code @Registration} must see what every
     * {@code @Enhancement} did, whichever extension did it.
     *
     * @param managedBean the managed bean a class of the index would be, or {@code null} when it
     *                    cannot be one; it makes a bean of a class an {@code @Enhancement} gave a scope
     *                    (see {@link #beansAfterEnhancement})
     * @param qualifiers  the qualifiers the extensions registered, which the check of the invokers' lookups
     *                    applies as the container does
     * @param deferLookups whether the caller checks the invokers' lookups, through {@link Result#invokerLookups()},
     *                    once it holds the synthetic beans of other extensions too: a start, which registers those of
     *                    the frozen extensions and of the processed archives after (BUG-20261117-45)
     */
    @SuppressWarnings("java:S107") // CDI BCE processing requires multiple contextual parameters
    public static Result process(List<Class<?>> bceClasses,
                                 List<BeanDescriptor> beans,
                                 List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
                                 List<io.vidocq.vauban.core.bean.model.InterceptorDescriptor> interceptors,
                                 VaubanIndex index,
                                 ClassLoader classLoader,
                                 Map<Class<?>, Object> bceInstances,
                                 List<Class<?>> allArchiveClasses,
                                 java.util.function.Function<DotName, BeanDescriptor> managedBean,
                                 RegisteredQualifiers qualifiers,
                                 boolean deferLookups) {
        return process(bceClasses, beans, observers, interceptors, index, classLoader, bceInstances,
                allArchiveClasses, managedBean, qualifiers, deferLookups, Enhanced.NONE);
    }

    /**
     * What the container's {@code @Enhancement} pass did before it discovered the beans: the extensions it ran, and
     * the modifications it collected, those of the frozen extensions included.
     *
     * @param extensions    the extensions whose {@code @Enhancement} methods ran: {@code process} does not call them
     *                      again (BUG-20261117-08)
     * @param modifications what that pass, the replay and the frozen record added: the phases after
     *                      {@code @Enhancement} read the classes with them, members included (BUG-20261118-42)
     */
    public record Enhanced(Set<Class<?>> extensions, Map<DotName, List<VaubanClassConfig>> modifications) {

        public static final Enhanced NONE = new Enhanced(Set.of(), Map.of());

        public Enhanced {
            extensions = Set.copyOf(extensions);
            modifications = Map.copyOf(modifications);
        }
    }

    /**
     * {@link #process(List, List, List, List, VaubanIndex, ClassLoader, Map, List, java.util.function.Function,
     * RegisteredQualifiers, boolean)}, after the container's own {@code @Enhancement} pass, {@code enhanced}: it calls
     * no {@code @Enhancement} method of the extensions that pass ran, as CDI 4.1 calls each once for each type it
     * matches, where it called them a second time (BUG-20261117-08); and its later phases read the classes with what
     * that pass added.
     */
    @SuppressWarnings("java:S107") // CDI BCE processing requires multiple contextual parameters
    public static Result process(List<Class<?>> bceClasses,
                                 List<BeanDescriptor> beans,
                                 List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
                                 List<io.vidocq.vauban.core.bean.model.InterceptorDescriptor> interceptors,
                                 VaubanIndex index,
                                 ClassLoader classLoader,
                                 Map<Class<?>, Object> bceInstances,
                                 List<Class<?>> allArchiveClasses,
                                 java.util.function.Function<DotName, BeanDescriptor> managedBean,
                                 RegisteredQualifiers qualifiers,
                                 boolean deferLookups,
                                 Enhanced enhanced) {
        var lookup = new IndexLookup(index);
        var failures = new PhaseFailures();
        var definitionErrors = failures.definitionErrors();
        var deploymentErrors = failures.deploymentErrors();
        var allSyntheticBeans = new ArrayList<VaubanSyntheticBeanBuilder<?>>();
        var allSyntheticObservers = new ArrayList<VaubanSyntheticObserverBuilder<?>>();
        var allEnhancementMods = new HashMap<io.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>>();

        for (var bceClass : bceClasses) {
            // Validate method signatures before processing
            ExtensionMethodValidator.validateExtensionMethods(bceClass, definitionErrors);
        }
        if (!definitionErrors.isEmpty()) {
            return result(failures, allSyntheticBeans, allSyntheticObservers, allEnhancementMods,
                    InvokerLookups.NONE);
        }

        // Reuse the instances of the Discovery phase to keep the state an extension builds across phases.
        // An extension that fails in one phase takes no part in the next ones.
        var extensions = new LinkedHashMap<Class<?>, Object>();
        for (var bceClass : bceClasses) {
            runPhase(bceClass, failures::thrown, () -> {
                var bce = bceInstances != null ? bceInstances.get(bceClass) : null;
                extensions.put(bceClass, bce != null ? bce : instantiateBce(bceClass));
            });
        }
        var archiveTypes = typesOf(allArchiveClasses);

        // Phase: @Enhancement — iterates over beans, fallback to archive classes if none match
        runPhase(extensions, failures::thrown, (bceClass, bce) -> {
            if (!enhanced.extensions().contains(bceClass)) {
                processEnhancement(bce, bceClass, beans, archiveTypes, lookup, classLoader,
                        new EnhancementSink(failures, allEnhancementMods));
            }
        });

        // The later phases read the classes as @Enhancement left them: the processor's language model did not show
        // the annotations it added, which the container's did through the index it enriched (BUG-20261117-10)
        var phaseModifications = merged(enhanced.modifications(), allEnhancementMods);
        var enhancedLookup = phaseModifications.isEmpty() ? lookup
                : new IndexLookup(withEnhancements(index, phaseModifications));
        var types = new VaubanTypes(enhancedLookup);

        // Phase: @Registration — on the beans as Enhancement left them, the classes it made beans included
        // (BUG-20261008-03)
        var enhancedBeans = beansAfterEnhancement(beans, allEnhancementMods, managedBean);
        var registered = withInheritedInjectionPoints(enhancedBeans, enhancedLookup.index(), phaseModifications,
                qualifiers);
        var invokers = new ArrayList<VaubanInvokerFactory>();
        var registrationContext = new RegistrationContext(enhancedLookup, classLoader, types,
                EnhancedClasses.inheritedParameters(enhancedLookup.index(), phaseModifications));
        runPhase(extensions, failures::thrown, (bceClass, bce) ->
                processRegistration(bce, bceClass, registered, observers, interceptorsAsBeans(interceptors,
                        managedBean), registrationContext, failures, invokers));

        // Phase: @Synthesis
        runPhase(extensions, failures::thrown, (bceClass, bce) -> {
            var synthesisResult = processSynthesis(bce, bceClass, types, failures);
            synthesisResult.beans().forEach(bean -> bean.setSourceExtension(bceClass));
            synthesisResult.observers().forEach(observer -> observer.setSourceExtension(bceClass));
            allSyntheticBeans.addAll(synthesisResult.beans());
            allSyntheticObservers.addAll(synthesisResult.observers());
        });

        // The looked-up beans of the invokers built, identified among every bean, the synthetic ones included: they
        // were checked in @Registration, before @Synthesis added any (BUG-20261117-39)
        var invokerLookups = new InvokerLookups(invokers, enhancedLookup.index(), classLoader, qualifiers);
        if (!deferLookups) {
            deploymentErrors.addAll(invokerLookups.errors(withSyntheticBeans(enhancedBeans, allSyntheticBeans)));
        }

        // Phase: @Validation
        runPhase(extensions, failures::deploymentProblem, (bceClass, bce) ->
                processValidation(bce, bceClass, types, failures));

        return result(failures, allSyntheticBeans, allSyntheticObservers, allEnhancementMods,
                deferLookups ? invokerLookups : InvokerLookups.NONE);
    }

    private static Result result(PhaseFailures failures, List<VaubanSyntheticBeanBuilder<?>> syntheticBeans,
                                 List<VaubanSyntheticObserverBuilder<?>> syntheticObservers,
                                 Map<DotName, List<VaubanClassConfig>> enhancementModifications,
                                 InvokerLookups invokerLookups) {
        return new Result(syntheticBeans, syntheticObservers, failures.definitionErrors(),
                failures.definitionErrorCauses(), failures.deploymentErrors(), enhancementModifications,
                failures.deploymentErrorCauses(), invokerLookups);
    }

    /**
     * {@code index} with what {@code modifications} did to its classes: the annotations they removed and those they
     * added (BUG-20261118-45).
     */
    static VaubanIndex withEnhancements(VaubanIndex index, Map<DotName, List<VaubanClassConfig>> modifications) {
        return EnhancedClasses.of(index, modifications);
    }

    /** The modifications of {@code first}, then those of {@code second}, by class. */
    private static Map<DotName, List<VaubanClassConfig>> merged(Map<DotName, List<VaubanClassConfig>> first,
                                                               Map<DotName, List<VaubanClassConfig>> second) {
        var merged = new HashMap<DotName, List<VaubanClassConfig>>();
        first.forEach((name, configs) -> merged.computeIfAbsent(name, _ -> new ArrayList<>()).addAll(configs));
        second.forEach((name, configs) -> merged.computeIfAbsent(name, _ -> new ArrayList<>()).addAll(configs));
        return merged;
    }

    /**
     * One phase of every extension still taking part, in their order; {@code failed} records what escapes a step, as
     * {@link PhaseFailures} maps the phase's exceptions.
     */
    private static void runPhase(Map<Class<?>, Object> extensions, BiConsumer<String, Throwable> failed,
                                 PhaseStep step) {
        for (var it = extensions.entrySet().iterator(); it.hasNext(); ) {
            var extension = it.next();
            if (!runPhase(extension.getKey(), failed, () -> step.run(extension.getKey(), extension.getValue()))) {
                it.remove();
            }
        }
    }

    private static boolean runPhase(Class<?> bceClass, BiConsumer<String, Throwable> failed, PhaseAction action) {
        try {
            ExtensionAsks.of(bceClass, action::run);
            return true;
        } catch (Exception e) {
            // What the constructor threw, not its reflective wrapper (BUG-20261118-57)
            var cause = unwrapped(e);
            failed.accept("BCE processing failed for " + bceClass.getName() + ": "
                    + java.util.Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getName()), cause);
            return false;
        }
    }

    @FunctionalInterface
    private interface PhaseStep {
        void run(Class<?> bceClass, Object bce) throws ReflectiveOperationException;
    }

    @FunctionalInterface
    private interface PhaseAction {
        void run() throws ReflectiveOperationException;
    }

    /**
     * The beans as {@code @Registration} sees them: a managed bean with the injection points its class inherits too,
     * which its descriptor leaves out, each as the {@code @Enhancement} configs of that class left it. A member a
     * superclass declares, configured through a subclass, changes that subclass's bean only (BUG-20261118-48): its
     * shared declaration in the index does not carry the change, the descriptor of the subclass's bean does, as the
     * container injects it. {@code BeanInfo.injectionPoints()} gave the declared injection points only
     * (BUG-20261118-50). The configs apply to the inherited points too: one of them may make an initializer of an
     * inherited method that declares no {@code @Inject}, which the container calls (review round 3). A point is told
     * from another by its description and the class that declares its member ({@code declaringClass}), which the
     * description names by its simple name only (review round 5); a point read back without that class is one of the
     * bean class, or of the nearest superclass that declares a member it describes.
     */
    static List<BeanDescriptor> withInheritedInjectionPoints(List<BeanDescriptor> beans, VaubanIndex index,
            Map<DotName, List<VaubanClassConfig>> modifications, RegisteredQualifiers qualifiers) {
        var discovery = new io.vidocq.vauban.core.bean.discovery.BeanDiscovery(index);
        discovery.setCustomQualifiers(qualifiers.types());
        var lookup = new IndexLookup(index);
        var result = new ArrayList<BeanDescriptor>(beans.size());
        for (var bean : beans) {
            var beanClass = bean.kind() == BeanDescriptor.BeanKind.MANAGED
                    ? lookup.describe(bean.beanClass()).orElse(null) : null;
            if (beanClass == null) {
                result.add(bean);
                continue;
            }
            var keyed = new java.util.LinkedHashMap<String, io.vidocq.vauban.core.bean.model.InjectionPointInfo>();
            bean.injectionPoints().forEach(point -> keyed.putIfAbsent(key(point, beanClass), point));
            discovery.inheritedInjectionPoints(beanClass).stream()
                    .filter(point -> !keyed.containsKey(" " + point.description()))
                    .forEach(point -> keyed.putIfAbsent(key(point, beanClass), point));
            var points = List.copyOf(keyed.values());
            var configs = modifications.get(bean.beanClass());
            if (configs != null) {
                points = applyEnhancements(List.of(withInjectionPoints(bean, points)),
                        Map.of(bean.beanClass(), configs)).stream().findFirst()
                        .map(BeanDescriptor::injectionPoints).orElse(points);
            }
            result.add(points.equals(bean.injectionPoints()) ? bean : withInjectionPoints(bean, points));
        }
        return result;
    }

    /**
     * What tells {@code point} of a bean of class {@code beanClass} from another: its description, and the class
     * that declares its member. Read back without that class, a point the description names the bean class of is
     * the bean class's, and another one is keyed by its description alone, which an inherited point of that
     * description then matches.
     */
    private static String key(io.vidocq.vauban.core.bean.model.InjectionPointInfo point,
            io.vidocq.vauban.indexer.model.ClassInfo beanClass) {
        var declaring = point.declaringClass();
        var simpleName = beanClass.name().simpleName();
        var description = point.description();
        if (declaring == null && (description.startsWith("field " + simpleName + ".")
                || description.contains(" of " + simpleName + ".")
                || description.contains(" of " + simpleName + "("))) {
            declaring = beanClass.name();
        }
        return (declaring == null ? "" : declaring.value()) + " " + description;
    }

    private static BeanDescriptor withInjectionPoints(BeanDescriptor bean,
            List<io.vidocq.vauban.core.bean.model.InjectionPointInfo> points) {
        return new BeanDescriptor(bean.id(), bean.beanClass(), bean.kind(), bean.types(), bean.qualifiers(),
                bean.scope(), bean.isAlternative(), bean.priority(), bean.priorityDeclared(), points, bean.name(),
                bean.interceptorBindings(), bean.constructorBindings(), bean.interceptorBindingAnnotations(),
                bean.enhancedInjectionMethods());
    }

    /**
     * The beans once the {@code @Enhancement} modifications apply: the existing beans modified, plus a
     * bean for each class that was none and gained a scope.
     *
     * @param managedBean the managed bean a class of the index would be, or {@code null}
     */
    public static List<BeanDescriptor> beansAfterEnhancement(
            List<BeanDescriptor> beans,
            Map<DotName, List<VaubanClassConfig>> modifications,
            java.util.function.Function<DotName, BeanDescriptor> managedBean) {
        if (modifications.isEmpty()) {
            return beans;
        }
        var result = new ArrayList<>(applyEnhancements(beans, modifications));
        var existingBeanClasses = result.stream().map(BeanDescriptor::beanClass).collect(Collectors.toSet());
        for (var entry : modifications.entrySet()) {
            if (!existingBeanClasses.contains(entry.getKey())) {
                promotedBean(entry.getKey(), entry.getValue(), managedBean).ifPresent(promoted ->
                        result.addAll(applyEnhancements(List.of(promoted), Map.of(entry.getKey(), entry.getValue()))));
            }
        }
        return result;
    }

    /**
     * The classes the {@code @Enhancement} modifications give an interceptor binding, on the class, a method or a
     * constructor. The build generates their {@code $$Intercepted} subclass as it does for a binding the source
     * declares: deciding from the source alone left them to the container, which defined the subclass at start
     * (BUG-20261113-13).
     */
    public static Set<DotName> boundByEnhancement(Map<DotName, List<VaubanClassConfig>> modifications) {
        return modifications.entrySet().stream()
                .filter(entry -> EnhancementApplier.addsInterceptorBinding(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The managed bean a class that was no bean becomes once an {@code @Enhancement} gave it a scope,
     * or empty when it gained none or cannot be a managed bean.
     */
    private static Optional<BeanDescriptor> promotedBean(DotName beanClass, List<VaubanClassConfig> configs,
            java.util.function.Function<DotName, BeanDescriptor> managedBean) {
        var enhancedScope = enhancedScope(configs);
        if (enhancedScope == null) {
            return Optional.empty();
        }
        var bean = managedBean.apply(beanClass);
        if (bean == null) {
            return Optional.empty();
        }
        return Optional.of(new BeanDescriptor(
                bean.id(), bean.beanClass(), bean.kind(), bean.types(),
                bean.qualifiers(), enhancedScope, bean.isAlternative(),
                bean.priority(), bean.priorityDeclared(), bean.injectionPoints(), bean.name(),
                bean.interceptorBindings(), bean.constructorBindings(),
                bean.interceptorBindingAnnotations(), bean.enhancedInjectionMethods()));
    }

    /** The scope an {@code @Enhancement} added to a class, or {@code null} when it added none. */
    public static ScopeInfo enhancedScope(List<VaubanClassConfig> configs) {
        for (var config : configs) {
            for (var ann : config.getAddedAnnotations()) {
                var scope = scopeOf(ann);
                if (scope != null) {
                    return scope;
                }
            }
        }
        return null;
    }

    /**
     * The scope an extension hands over through the Build Compatible Extensions API, as an annotation class: a scope
     * of an extension (such as {@code jakarta.transaction.TransactionScoped}) keeps its name and its normality, read
     * from its {@code @NormalScope} or {@code @Scope} meta-annotation (BUG-20261113-01); {@code null} when the
     * annotation is no scope.
     */
    public static ScopeInfo scopeOf(Class<? extends Annotation> ann) {
        if (ann.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
            return new ScopeInfo(DotName.of(ann.getName()), true);
        }
        if (ann.isAnnotationPresent(jakarta.inject.Scope.class)) {
            return new ScopeInfo(DotName.of(ann.getName()), false);
        }
        // Explicit well-known scope check (for annotations without meta-annotations)
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
        return null;
    }

    private static void makeAccessibleSafe(java.lang.reflect.AccessibleObject member) {
        member.trySetAccessible();
    }

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private static Object instantiateBce(Class<?> bceClass) throws ReflectiveOperationException {
        // Prefer ServiceLoader: a BCE declared via `provides ... with` is instantiated by the
        // module system WITHOUT requiring the module to open its package to vauban-core. This
        // removes the deep reflection that forced `opens <pkg> to io.vidocq.vauban.core`.
        var loaded = serviceLoaderInstance(bceClass);
        if (loaded != null) return loaded;

        // Fallback: class path, unnamed module, or a BCE not declared as a service.
        var ctor = bceClass.getDeclaredConstructor();
        makeAccessibleSafe(ctor);
        return ctor.newInstance();
    }

    /**
     * Returns the {@link java.util.ServiceLoader}-provided instance for {@code bceClass} if it
     * is registered as a {@code BuildCompatibleExtension} service on its own class loader,
     * otherwise {@code null}. The module system performs the instantiation, so no qualified
     * {@code opens} to vauban-core is needed on the module path.
     */
    private static Object serviceLoaderInstance(Class<?> bceClass) {
        try {
            var loader = java.util.ServiceLoader.load(
                    jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension.class,
                    bceClass.getClassLoader());
            for (var provider : loader.stream().toList()) {
                if (provider.type().equals(bceClass)) {
                    return provider.get();
                }
            }
        } catch (RuntimeException | ServiceConfigurationError | LinkageError _) {
            // ServiceLoader unavailable / misconfigured — fall back to reflection.
        }
        return null;
    }

    private enum EnhancementParamKind {
        CLASS_CONFIG, FIELD_CONFIG, METHOD_CONFIG, CLASS_INFO, FIELD_INFO, METHOD_INFO
    }

    private static EnhancementParamKind detectEnhancementParamKind(Method method) {
        for (var param : method.getParameterTypes()) {
            if (ClassConfig.class.isAssignableFrom(param)) return EnhancementParamKind.CLASS_CONFIG;
            if (FieldConfig.class.isAssignableFrom(param)) return EnhancementParamKind.FIELD_CONFIG;
            if (MethodConfig.class.isAssignableFrom(param)) return EnhancementParamKind.METHOD_CONFIG;
            if (jakarta.enterprise.lang.model.declarations.ClassInfo.class.isAssignableFrom(param))
                return EnhancementParamKind.CLASS_INFO;
            if (jakarta.enterprise.lang.model.declarations.FieldInfo.class.isAssignableFrom(param))
                return EnhancementParamKind.FIELD_INFO;
            if (jakarta.enterprise.lang.model.declarations.MethodInfo.class.isAssignableFrom(param))
                return EnhancementParamKind.METHOD_INFO;
        }
        return EnhancementParamKind.CLASS_CONFIG; // fallback
    }

    @SuppressWarnings({"java:S3011", "java:S135", "java:S107"}) // CDI spec requires reflective access; BCE processing requires multiple contextual parameters
    private static void processEnhancement(Object bce, Class<?> bceClass,
                                           List<BeanDescriptor> beans,
                                           List<Class<?>> archiveClasses,
                                           IndexLookup lookup, ClassLoader classLoader,
                                           EnhancementSink sink) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var enhancement = method.getAnnotation(Enhancement.class);
            if (enhancement == null) continue;

            makeAccessibleSafe(method);
            var paramKind = detectEnhancementParamKind(method);

            var withAnnotations = enhancement.withAnnotations();
            var processedClasses = new java.util.HashSet<DotName>();

            // First try beans (normal CDI path)
            for (var bean : beans) {
                if (!BceTypeMatcher.matchesTypes(enhancement.types(), bean, classLoader)) continue;
                if (!BceTypeMatcher.matchesAnnotations(withAnnotations, bean.beanClass(), classLoader)) continue;
                processedClasses.add(bean.beanClass());
                invokeEnhancement(method, bce, paramKind, bean.beanClass(), lookup, sink);
            }

            // Also check archive classes for non-bean classes (e.g. added via ScannedClasses)
            if (archiveClasses != null) {
                for (var archiveClass : archiveClasses) {
                    var className = DotName.of(archiveClass.getName());
                    if (processedClasses.contains(className)) continue;
                    if (!BceTypeMatcher.matchesClass(enhancement.types(), enhancement.withSubtypes(), archiveClass)) continue;
                    if (!BceTypeMatcher.matchesAnnotations(withAnnotations, archiveClass)) continue;
                    processedClasses.add(className);
                    invokeEnhancement(method, bce, paramKind, className, lookup, sink);
                }
            }

            // Third path: index-based matching for classes not loadable via ClassLoader
            // (e.g. classes being compiled in APT that are in the index but not on the classpath)
            if (lookup.index() != null) {
                var withAnnotationNames = new java.util.HashSet<DotName>();
                for (var ann : withAnnotations) {
                    withAnnotationNames.add(DotName.of(ann.getName()));
                }
                boolean isObjectWildcard = enhancement.types().length == 1
                        && enhancement.types()[0] == Object.class;

                for (var classInfo : knownTypes(lookup.index())) {
                    if (processedClasses.contains(classInfo.name())) continue;
                    // Type matching: Object.class wildcard matches everything
                    if (!isObjectWildcard && !BceTypeMatcher.matchesClassByIndex(enhancement.types(),
                            enhancement.withSubtypes(), classInfo, lookup, classLoader)) continue;
                    // Annotation matching via index
                    if (!withAnnotationNames.isEmpty()
                            && !BceTypeMatcher.matchesAnnotationsByIndex(withAnnotationNames, classInfo)) continue;
                    processedClasses.add(classInfo.name());
                    invokeEnhancement(method, bce, paramKind, classInfo.name(), lookup, sink);
                }
            }
        }
    }

    /**
     * The classes of an archive an extension sees as types: all but the {@code package-info} classes, which only carry
     * the annotations of their package (BUG-20261117-01).
     */
    private static List<Class<?>> typesOf(List<Class<?>> archiveClasses) {
        return archiveClasses == null ? List.of() : archiveClasses.stream()
                .filter(archiveClass -> !PackageInfos.isPackageInfo(archiveClass.getName()))
                .toList();
    }

    /** The classes of {@code index} an extension sees as types: all but the {@code package-info} classes. */
    private static List<io.vidocq.vauban.indexer.model.ClassInfo> knownTypes(VaubanIndex index) {
        return index.getKnownClasses().stream()
                .filter(classInfo -> !PackageInfos.isPackageInfo(classInfo.name().value()))
                .toList();
    }

    /**
     * Where the {@code @Enhancement} invocations of one phase report: their failures, and the modifications keyed by
     * the class they modify.
     */
    private record EnhancementSink(PhaseFailures failures, Map<DotName, List<VaubanClassConfig>> modifications) {
    }

    private static void invokeEnhancement(Method method, Object bce, EnhancementParamKind paramKind,
                                           DotName className, IndexLookup lookup, EnhancementSink sink) {
        var modifications = sink.modifications();
        var indexClass = lookup.getClass(className).orElse(null);
        if (indexClass == null) return;
        var vaubanClassInfo = new VaubanClassInfo(indexClass, lookup);
        // The Messages and Types the Javadoc of @Enhancement lists: they were null (BUG-20261118-23).
        var services = new PhaseServices(new VaubanMessages(), new VaubanTypes(lookup));
        try {
            switch (paramKind) {
                case CLASS_CONFIG -> {
                    var classConfig = new VaubanClassConfig(vaubanClassInfo);
                    classConfig.setSourceBce(bce.getClass());
                    invokeWithArg(method, bce, services, ClassConfig.class, classConfig);
                    if (classConfig.isModified()) {
                        modifications.computeIfAbsent(className, k -> new ArrayList<>()).add(classConfig);
                    }
                }
                case CLASS_INFO -> invokeWithArg(method, bce, services,
                        jakarta.enterprise.lang.model.declarations.ClassInfo.class, vaubanClassInfo);
                case METHOD_CONFIG -> {
                    var classConfig = new VaubanClassConfig(vaubanClassInfo);
                    classConfig.setSourceBce(bce.getClass());
                    // The methods it declares: @Enhancement is called "for each constructor or method that is declared
                    // on each discovered type", where ClassConfig.methods() includes inherited ones (BUG-20261118-48)
                    for (var mc : classConfig.configurableMethods()) {
                        invokeWithArg(method, bce, services, MethodConfig.class, mc);
                    }
                    if (classConfig.isModified()) {
                        modifications.computeIfAbsent(className, k -> new ArrayList<>()).add(classConfig);
                    }
                }
                case METHOD_INFO -> {
                    for (var mi : vaubanClassInfo.configurableMethods()) {
                        invokeWithArg(method, bce, services,
                                jakarta.enterprise.lang.model.declarations.MethodInfo.class, mi);
                    }
                }
                case FIELD_CONFIG -> {
                    var classConfig = new VaubanClassConfig(vaubanClassInfo);
                    classConfig.setSourceBce(bce.getClass());
                    for (var fc : classConfig.configurableFields()) {
                        invokeWithArg(method, bce, services, FieldConfig.class, fc);
                    }
                    if (classConfig.isModified()) {
                        modifications.computeIfAbsent(className, k -> new ArrayList<>()).add(classConfig);
                    }
                }
                case FIELD_INFO -> {
                    for (var fi : vaubanClassInfo.configurableFields()) {
                        invokeWithArg(method, bce, services,
                                jakarta.enterprise.lang.model.declarations.FieldInfo.class, fi);
                    }
                }
            }
        } catch (Exception e) {
            var unwrapped = unwrapped(e);
            sink.failures().thrown(phaseError("@Enhancement", method, unwrapped), unwrapped);
        }
        sink.failures().reported(services.messages().getErrors());
    }

    /** The {@code Messages} and {@code Types} an {@code @Enhancement} method may declare besides its target. */
    private record PhaseServices(VaubanMessages messages, VaubanTypes types) {
    }

    /** What an extension method threw: the cause of the {@code InvocationTargetException} of a reflective call. */
    private static Throwable unwrapped(Exception e) {
        return e instanceof java.lang.reflect.InvocationTargetException ite
                ? java.util.Objects.requireNonNullElse(ite.getCause(), ite) : e;
    }

    /**
     * The line an extension method's failure adds to the errors of its phase: {@code <phase> error: <message>}, or,
     * for a failure without a message, such as the {@code AssertionError} of a failed {@code assert}, the extension
     * class, the method and the failure's type, where the line read {@code null} (BUG-20261117-46).
     */
    static String phaseError(String phase, Method method, Throwable thrown) {
        var message = thrown.getMessage();
        return message != null ? phase + " error: " + message
                : phase + " error in " + method.getDeclaringClass().getName() + "." + method.getName() + ": "
                        + thrown.getClass().getName();
    }

    /**
     * The line a {@code @Registration} method's failure adds: a {@code DeploymentException} it throws, the invoker
     * factory's own (BUG-20261117-06), or an {@code IllegalStateException}, reads as its message alone; any other
     * failure as {@link #phaseError} writes it.
     */
    private static String registrationError(Method method, Exception e) {
        var thrown = unwrapped(e);
        return e instanceof java.lang.reflect.InvocationTargetException && thrown.getMessage() != null
                && (thrown instanceof IllegalStateException
                        || thrown instanceof jakarta.enterprise.inject.spi.DeploymentException)
                ? thrown.getMessage() : phaseError("@Registration", method, thrown);
    }

    @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
    private static void invokeWithArg(Method method, Object bce, PhaseServices services, Class<?> targetType,
                                      Object arg) throws Exception {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (Messages.class.isAssignableFrom(paramType)) {
                args[i] = services.messages();
            } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(paramType)) {
                args[i] = services.types();
            } else if (targetType.isAssignableFrom(paramType) || paramType.isAssignableFrom(targetType)) {
                args[i] = arg;
            }
        }
        method.invoke(bce, args);
    }

    /**
     * What the {@code @Registration} phase reads besides its targets: the classes as {@code @Enhancement} left them,
     * the class loader, the types, and the annotations the configs of a class left on the parameters of the methods it
     * inherits, which its invokers' lookups read (BUG-20261118-55).
     */
    private record RegistrationContext(IndexLookup lookup, ClassLoader classLoader, VaubanTypes types,
                                       EnhancedClasses.InheritedParameters inherited) {
    }

    /**
     * Process @Registration methods — main phase for Invokers.
     */
    @SuppressWarnings({"java:S3011", "java:S135", "java:S107"}) // CDI spec requires reflective access; BCE processing requires multiple contextual parameters
    private static void processRegistration(Object bce, Class<?> bceClass,
                                            List<BeanDescriptor> beans,
                                            List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
                                            List<InterceptorBean> interceptors,
                                            RegistrationContext context,
                                            PhaseFailures failures,
                                            List<VaubanInvokerFactory> invokers) {
        var lookup = context.lookup();
        var classLoader = context.classLoader();
        var types = context.types();
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var registration = method.getAnnotation(Registration.class);
            if (registration == null) continue;

            makeAccessibleSafe(method);

            if (hasObserverInfoParam(method)) {
                for (var observer : observers) {
                    if (!BceTypeMatcher.matchesObserverTypes(registration.types(), observer, lookup, classLoader)) {
                        continue;
                    }
                    var observerInfo = new VaubanBceObserverInfo(observer, declaringBean(observer, beans), lookup);
                    invokeRegistrationMethodWithObserver(method, bce, observerInfo, types, failures);
                }
                continue;
            }

            for (var target : registrationTargets(method, registration.types(), beans, interceptors, lookup,
                    classLoader)) {
                invokers.add(invokeRegistrationMethod(method, bce, target, context, failures));
            }
        }
    }

    /**
     * What a {@code @Registration} method with a {@code BeanInfo} or an {@code InterceptorInfo} parameter is called
     * for, in order: the beans whose bean types hold an expected type, the built-in beans that do
     * ({@link BuiltInBeans}), then the interceptors whose bean types do, and nothing else, as CDI 4.1 says. When no
     * bean had an expected type, the container gave the classes of the archive that had one, beans or not, and the
     * processor nothing (BUG-20261117-06). An {@code InterceptorInfo} parameter takes the interceptors only, as
     * CDI 4.1 says: handed a bean, it failed the deployment with "argument type mismatch" (BUG-20261117-02).
     */
    private static List<BeanInfo> registrationTargets(Method method, Class<?>[] expected, List<BeanDescriptor> beans,
            List<InterceptorBean> interceptors, IndexLookup lookup, ClassLoader classLoader) {
        var interceptorsOnly = hasInterceptorInfoParam(method);
        var interceptorClasses = interceptors.stream()
                .map(interceptor -> interceptor.interceptor().interceptorClass())
                .collect(Collectors.toSet());
        var targets = new ArrayList<BeanInfo>();
        if (!interceptorsOnly) {
            for (var bean : beans) {
                // An interceptor is matched below, as an interceptor
                if (!interceptorClasses.contains(bean.beanClass())
                        && BceTypeMatcher.matchesRegistrationTypes(expected, bean)) {
                    targets.add(new VaubanBceBeanInfo(bean, lookup));
                }
            }
            BuiltInBeans.ALL.stream().filter(builtIn -> BceTypeMatcher.matchesRegistrationTypes(expected, builtIn))
                    .forEach(builtIn -> targets.add(VaubanBceBeanInfo.builtIn(builtIn, lookup)));
        }
        for (var interceptor : interceptors) {
            if (BceTypeMatcher.matchesInterceptorTypes(expected, interceptor.interceptor(), lookup, classLoader)) {
                targets.add(new VaubanBceInterceptorInfo(interceptor.interceptor(), interceptor.bean(), lookup));
            }
        }
        return targets;
    }

    /**
     * The managed bean whose class declares {@code observer}, among {@code beans}; {@code null} for none. CDI 4.1 gives
     * an {@code ObserverInfo} that bean, which it had not (BUG-20261117-09).
     */
    private static BeanDescriptor declaringBean(ObserverDescriptor observer, List<BeanDescriptor> beans) {
        if (observer.isSynthetic()) {
            return null;
        }
        return beans.stream()
                .filter(bean -> bean.kind() == BeanDescriptor.BeanKind.MANAGED
                        && bean.beanClass().equals(observer.declaringClass()))
                .findFirst().orElse(null);
    }

    /** An interceptor, and its class as the managed bean it would be, or {@code null} when it cannot be one. */
    private record InterceptorBean(InterceptorDescriptor interceptor, BeanDescriptor bean) {
    }

    /**
     * Each interceptor with its class described as a managed bean: CDI 4.1 gives an interceptor's {@code BeanInfo} the
     * bean types, qualifiers and injection points of its class, which it had none of (BUG-20261117-04).
     */
    private static List<InterceptorBean> interceptorsAsBeans(List<InterceptorDescriptor> interceptors,
            java.util.function.Function<DotName, BeanDescriptor> managedBean) {
        return interceptors.stream()
                .map(interceptor -> new InterceptorBean(interceptor, managedBean.apply(interceptor.interceptorClass())))
                .toList();
    }

    private static boolean hasObserverInfoParam(Method method) {
        for (var param : method.getParameters()) {
            if (ObserverInfo.class.isAssignableFrom(param.getType())) return true;
        }
        return false;
    }

    /** Whether the method takes an {@code InterceptorInfo}: CDI 4.1 calls it for the interceptors, and no other bean. */
    private static boolean hasInterceptorInfoParam(Method method) {
        for (var param : method.getParameterTypes()) {
            if (InterceptorInfo.class.isAssignableFrom(param)) return true;
        }
        return false;
    }

    private static void invokeRegistrationMethodWithObserver(Method method, Object bce,
                                                              VaubanBceObserverInfo observerInfo,
                                                              VaubanTypes types, PhaseFailures failures) {
        var messages = new VaubanMessages();
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (ObserverInfo.class.isAssignableFrom(paramType)) {
                args[i] = observerInfo;
            } else if (Messages.class.isAssignableFrom(paramType)) {
                args[i] = messages;
            } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(paramType)) {
                args[i] = types;
            }
        }

        try {
            method.invoke(bce, args);
        } catch (Exception e) {
            failures.thrown(registrationError(method, e), unwrapped(e));
        }
        failures.reported(messages.getErrors());
    }

    /** Calls the {@code @Registration} {@code method} for {@code beanInfo}; returns the invokers it built. */
    private static VaubanInvokerFactory invokeRegistrationMethod(Method method, Object bce, BeanInfo beanInfo,
                                                                 RegistrationContext context,
                                                                 PhaseFailures failures) {
        var classLoader = context.classLoader();
        var types = context.types();
        var invokerFactory = new VaubanInvokerFactory(classLoader, context.inherited());
        var messages = new VaubanMessages();
        var args = resolveRegistrationArgs(method, beanInfo, invokerFactory, messages, types);

        try {
            method.invoke(bce, args);
        } catch (Exception e) {
            failures.thrown(registrationError(method, e), unwrapped(e));
        }
        failures.reported(messages.getErrors());
        return invokerFactory;
    }

    private static Object[] resolveRegistrationArgs(Method method, BeanInfo beanInfo,
                                                    VaubanInvokerFactory invokerFactory,
                                                    VaubanMessages messages, VaubanTypes types) {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (BeanInfo.class.isAssignableFrom(paramType)) {
                args[i] = beanInfo;
            } else if (InvokerFactory.class.isAssignableFrom(paramType)) {
                args[i] = invokerFactory;
            } else if (Messages.class.isAssignableFrom(paramType)) {
                args[i] = messages;
            } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(paramType)) {
                args[i] = types;
            }
        }
        return args;
    }

    /**
     * The qualifiers extensions registered with {@code MetaAnnotations.addQualifier}, and the members of qualifiers
     * they made non-binding (annotation type name to member names), as the container applies them.
     */
    public record RegisteredQualifiers(Set<DotName> types, Map<String, Set<String>> nonbindingMembers) {
        /** No qualifier registered, no member made non-binding. */
        public static final RegisteredQualifiers NONE = new RegisteredQualifiers(Set.of(), Map.of());

        public RegisteredQualifiers {
            types = Set.copyOf(types);
            nonbindingMembers = Map.copyOf(nonbindingMembers);
        }
    }

    record SynthesisResult(
            List<VaubanSyntheticBeanBuilder<?>> beans,
            List<VaubanSyntheticObserverBuilder<?>> observers
    ) {}

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private static SynthesisResult processSynthesis(Object bce, Class<?> bceClass,
                                                     VaubanTypes types,
                                                     PhaseFailures failures) {
        var components = new VaubanSyntheticComponents();

        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var synthesis = method.getAnnotation(Synthesis.class);
            if (synthesis == null) continue;

            makeAccessibleSafe(method);

            // The Messages the Javadoc of @Synthesis lists: it was null (BUG-20261118-23).
            var messages = new VaubanMessages();
            var args = resolveSynthesisArgs(method, components, messages, types);

            try {
                method.invoke(bce, args);
            } catch (Exception e) {
                var thrown = unwrapped(e);
                failures.thrown(phaseError("@Synthesis", method, thrown), thrown);
            }
            failures.reported(messages.getErrors());
        }

        return new SynthesisResult(components.getBeanDefinitions(), components.getObserverDefinitions());
    }

    /**
     * Process @Validation methods — collect errors that cause DeploymentException.
     */
    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private static void processValidation(Object bce, Class<?> bceClass, VaubanTypes types,
                                          PhaseFailures failures) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            if (method.getAnnotation(Validation.class) == null) continue;

            makeAccessibleSafe(method);
            var messages = new VaubanMessages();
            var params = method.getParameters();
            var args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (Messages.class.isAssignableFrom(params[i].getType())) {
                    args[i] = messages;
                } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(params[i].getType())) {
                    args[i] = types;
                }
            }

            try {
                method.invoke(bce, args);
            } catch (Exception e) {
                var thrown = unwrapped(e);
                failures.deploymentProblem(phaseError("@Validation", method, thrown), thrown);
            }
            failures.reported(messages.getErrors());
        }
    }

    private static Object[] resolveSynthesisArgs(Method method, VaubanSyntheticComponents components,
                                                  VaubanMessages messages, VaubanTypes types) {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (SyntheticComponents.class.isAssignableFrom(paramType)) {
                args[i] = components;
            } else if (Messages.class.isAssignableFrom(paramType)) {
                args[i] = messages;
            } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(paramType)) {
                args[i] = types;
            }
        }
        return args;
    }

    // Application of @Enhancement modifications — delegated to EnhancementApplier.

    public static List<InterceptorDescriptor> applyInterceptorEnhancements(
            List<InterceptorDescriptor> interceptors,
            Map<DotName, List<VaubanClassConfig>> modifications) {
        return EnhancementApplier.applyInterceptorEnhancements(interceptors, modifications);
    }

    public static List<BeanDescriptor> applyEnhancements(
            List<BeanDescriptor> descriptors,
            Map<DotName, List<VaubanClassConfig>> modifications) {
        return EnhancementApplier.applyEnhancements(descriptors, modifications);
    }

    public static List<ObserverDescriptor> applyObserverEnhancements(
            List<ObserverDescriptor> observers,
            Map<DotName, List<VaubanClassConfig>> modifications) {
        return EnhancementApplier.applyObserverEnhancements(observers, modifications);
    }

    /**
     * Get declared methods including from superclasses (for InvokerHolderExtensionBase pattern),
     * sorted by @Priority (lower value = earlier execution, no @Priority = APPLICATION + 500 = 2500).
     *
     * <p>Defensive: {@code Class.getDeclaredMethods()} triggers resolution of the
     * signature types (parameters, return, throws). If a signature references a
     * type absent from the classpath (typically an optional dependency of the
     * scanned module — e.g. {@code jakarta.xml.bind.JAXBException} in a
     * {@code throws} clause of {@code cassini-core/MessageBodyRegistry}), the JVM
     * throws a {@link LinkageError} (typically {@code NoClassDefFoundError}).
     * We catch it and skip this layer of the hierarchy — the class simply cannot
     * be inspected method-by-method in this context.</p>
     */
    static Method[] getDeclaredMethodsSafe(Class<?> cls) {
        var methods = new ArrayList<Method>();
        for (var c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Collections.addAll(methods, c.getDeclaredMethods());
            } catch (LinkageError _) {
                // Optional type missing in a signature — skip this layer.
            }
        }
        methods.sort(Comparator.comparingInt(BceProcessor::getMethodPriority));
        return methods.toArray(new Method[0]);
    }

    private static final int DEFAULT_PRIORITY = 2500; // APPLICATION(2000) + 500

    private static int getMethodPriority(Method m) {
        var p = m.getAnnotation(jakarta.annotation.Priority.class);
        return p != null ? p.value() : DEFAULT_PRIORITY;
    }

    /** {@code beans} followed by the descriptors of the synthetic beans {@code @Synthesis} added. */
    private static List<BeanDescriptor> withSyntheticBeans(List<BeanDescriptor> beans,
                                                           List<VaubanSyntheticBeanBuilder<?>> syntheticBeans) {
        var all = new ArrayList<>(beans);
        for (var syntheticBean : syntheticBeans) {
            all.add(SyntheticBeanConverter.toBeanDescriptor(syntheticBean, all.size()));
        }
        return all;
    }

    /** Synthesis builder -> descriptor conversion — delegated to {@link SyntheticBeanConverter}. */
    public static BeanDescriptor toBeanDescriptor(VaubanSyntheticBeanBuilder<?> synBean, int slot) {
        return SyntheticBeanConverter.toBeanDescriptor(synBean, slot);
    }
}
