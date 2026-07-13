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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.BuiltAnnotationInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processes Build Compatible Extensions (BCE) — CDI 4.1 spec chapter 28.
 * Handles @Enhancement, @Registration, and @Synthesis phases.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class BceProcessor {

    private static final String MSG_REGISTRATION_ERROR = "@Registration error: ";

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
     */
    public static Map<DotName, List<VaubanClassConfig>> processEnhancementOnly(
            List<Class<?>> bceClasses,
            List<Class<?>> archiveClasses,
            VaubanIndex index,
            ClassLoader classLoader) {
        var lookup = new IndexLookup(index);
        var modifications = new HashMap<DotName, List<VaubanClassConfig>>();
        var errors = new ArrayList<String>();
        var errorCauses = new ArrayList<Throwable>();

        for (var bceClass : bceClasses) {
            try {
                var bce = instantiateBce(bceClass);
                processEnhancement(bce, bceClass, List.of(), archiveClasses, lookup, classLoader, errors,
                        errorCauses, modifications);
            } catch (Exception e) {
                // Enhancement-only errors are non-fatal
            }
        }

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
     */
    public static Map<DotName, List<VaubanClassConfig>> replayEnhancementForTargets(
            List<Map.Entry<Class<?>, Class<?>>> replayPairs,
            VaubanIndex index) {
        var lookup = new IndexLookup(index);
        var modifications = new HashMap<DotName, List<VaubanClassConfig>>();
        var errors = new ArrayList<String>();

        for (var pair : replayPairs) {
            var bceClass = pair.getKey();
            var targetName = DotName.of(pair.getValue().getName());
            try {
                var bce = instantiateBce(bceClass);
                for (var method : getDeclaredMethodsSafe(bceClass)) {
                    if (method.getAnnotation(Enhancement.class) == null) continue;
                    makeAccessibleSafe(method);
                    var paramKind = detectEnhancementParamKind(method);
                    invokeEnhancement(method, bce, paramKind, targetName, lookup, errors, new ArrayList<>(), modifications);
                }
            } catch (Exception e) {
                // Replay errors are non-fatal — skip pair
            }
        }

        return modifications;
    }

    /**
     * Result of BCE processing: synthetic bean definitions and collected errors.
     */
    public record Result(
            List<VaubanSyntheticBeanBuilder<?>> syntheticBeans,
            List<VaubanSyntheticObserverBuilder<?>> syntheticObservers,
            List<String> definitionErrors,
            List<String> deploymentErrors,
            Map<io.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>> enhancementModifications,
            /*
             * Typed exceptions behind deploymentErrors (VAU-BCE-003): specs define the
             * exception a failed deployment must surface (e.g. MP Fault Tolerance's
             * FaultToleranceDefinitionException) and TCKs assert it through the
             * DeploymentException cause chain — the flattened messages alone lose it.
             */
            List<Throwable> deploymentErrorCauses
    ) {}

    public record DiscoveryResult(
            VaubanMetaAnnotations metaAnnotations,
            VaubanScannedClasses scannedClasses,
            Map<Class<?>, Object> bceInstances
    ) {}

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    public static DiscoveryResult processDiscovery(List<Class<?>> bceClasses, IndexLookup lookup) {
        var metaAnnotations = new VaubanMetaAnnotations(lookup);
        var scannedClasses = new VaubanScannedClasses();
        var bceInstances = new LinkedHashMap<Class<?>, Object>();

        for (var bceClass : bceClasses) {
            try {
                var bce = instantiateBce(bceClass);
                bceInstances.put(bceClass, bce);
                for (var method : getDeclaredMethodsSafe(bceClass)) {
                    if (method.getAnnotation(Discovery.class) == null) continue;
                    makeAccessibleSafe(method);

                    var params = method.getParameters();
                    var args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        var paramType = params[i].getType();
                        if (MetaAnnotations.class.isAssignableFrom(paramType)) {
                            args[i] = metaAnnotations;
                        } else if (ScannedClasses.class.isAssignableFrom(paramType)) {
                            args[i] = scannedClasses;
                        }
                    }
                    method.invoke(bce, args);
                }
            } catch (Exception e) {
                // Discovery phase errors are silently ignored per spec
            }
        }

        return new DiscoveryResult(metaAnnotations, scannedClasses, bceInstances);
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
        return process(bceClasses, beans, List.of(), List.of(), index, classLoader, bceInstances, allArchiveClasses);
    }

    @SuppressWarnings("java:S107") // CDI BCE processing requires multiple contextual parameters
    public static Result process(List<Class<?>> bceClasses,
                                 List<BeanDescriptor> beans,
                                 List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
                                 List<io.vidocq.vauban.core.bean.model.InterceptorDescriptor> interceptors,
                                 VaubanIndex index,
                                 ClassLoader classLoader,
                                 Map<Class<?>, Object> bceInstances,
                                 List<Class<?>> allArchiveClasses) {
        var lookup = new IndexLookup(index);
        var definitionErrors = new ArrayList<String>();
        var deploymentErrors = new ArrayList<String>();
        var deploymentErrorCauses = new ArrayList<Throwable>();
        var allSyntheticBeans = new ArrayList<VaubanSyntheticBeanBuilder<?>>();
        var allSyntheticObservers = new ArrayList<VaubanSyntheticObserverBuilder<?>>();
        var allEnhancementMods = new HashMap<io.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>>();

        for (var bceClass : bceClasses) {
            try {
                // Validate method signatures before processing
                ExtensionMethodValidator.validateExtensionMethods(bceClass, definitionErrors);
                if (!definitionErrors.isEmpty()) continue;

                // Reuse instance from Discovery phase to maintain state
                var bce = bceInstances != null ? bceInstances.get(bceClass) : null;
                if (bce == null) bce = instantiateBce(bceClass);

                var types = new VaubanTypes(lookup);

                // Phase: @Enhancement — iterates over beans, fallback to archive classes if none match
                processEnhancement(bce, bceClass, beans, allArchiveClasses, lookup, classLoader, deploymentErrors,
                        deploymentErrorCauses, allEnhancementMods);

                // Phase: @Registration
                processRegistration(bce, bceClass, beans, observers, interceptors, lookup, classLoader, types, deploymentErrors, allArchiveClasses);

                // Phase: @Synthesis
                var synthesisResult = processSynthesis(bce, bceClass, types, deploymentErrors);
                allSyntheticBeans.addAll(synthesisResult.beans());
                allSyntheticObservers.addAll(synthesisResult.observers());

                // Phase: @Validation
                processValidation(bce, bceClass, types, deploymentErrors);

            } catch (Exception e) {
                var className = bceClass != null ? bceClass.getName() : "unknown";
                deploymentErrors.add("BCE processing failed for " + className + ": " + e.getMessage());
                deploymentErrorCauses.add(e);
            }
        }

        return new Result(allSyntheticBeans, allSyntheticObservers, definitionErrors, deploymentErrors,
                allEnhancementMods, deploymentErrorCauses);
    }

    private static void makeAccessibleSafe(java.lang.reflect.AccessibleObject member) {
        member.trySetAccessible();
    }

    @SuppressWarnings({"java:S3011", "java:S112"}) // CDI spec requires reflective access; container exceptions propagate as RuntimeException
    private static Object instantiateBce(Class<?> bceClass) throws Exception {
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
        } catch (Throwable _) {
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
                                           List<String> errors,
                                           List<Throwable> errorCauses,
                                           Map<io.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>> modifications) {
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
                invokeEnhancement(method, bce, paramKind, bean.beanClass(), lookup, errors, errorCauses, modifications);
            }

            // Also check archive classes for non-bean classes (e.g. added via ScannedClasses)
            if (archiveClasses != null) {
                for (var archiveClass : archiveClasses) {
                    var className = DotName.of(archiveClass.getName());
                    if (processedClasses.contains(className)) continue;
                    if (!BceTypeMatcher.matchesClass(enhancement.types(), enhancement.withSubtypes(), archiveClass)) continue;
                    if (!BceTypeMatcher.matchesAnnotations(withAnnotations, archiveClass)) continue;
                    processedClasses.add(className);
                    invokeEnhancement(method, bce, paramKind, className, lookup, errors, errorCauses, modifications);
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

                for (var classInfo : lookup.index().getKnownClasses()) {
                    if (processedClasses.contains(classInfo.name())) continue;
                    // Type matching: Object.class wildcard matches everything
                    if (!isObjectWildcard && !BceTypeMatcher.matchesClassByIndex(enhancement.types(),
                            enhancement.withSubtypes(), classInfo, lookup)) continue;
                    // Annotation matching via index
                    if (!withAnnotationNames.isEmpty()
                            && !BceTypeMatcher.matchesAnnotationsByIndex(withAnnotationNames, classInfo)) continue;
                    processedClasses.add(classInfo.name());
                    invokeEnhancement(method, bce, paramKind, classInfo.name(), lookup, errors, errorCauses, modifications);
                }
            }
        }
    }

    private static void invokeEnhancement(Method method, Object bce, EnhancementParamKind paramKind,
                                           DotName className, IndexLookup lookup,
                                           List<String> errors,
                                           List<Throwable> errorCauses,
                                           Map<DotName, List<VaubanClassConfig>> modifications) {
        var indexClass = lookup.getClass(className).orElse(null);
        if (indexClass == null) return;
        var vaubanClassInfo = new VaubanClassInfo(indexClass, lookup);
        try {
            switch (paramKind) {
                case CLASS_CONFIG -> {
                    var classConfig = new VaubanClassConfig(vaubanClassInfo);
                    classConfig.setSourceBce(bce.getClass());
                    invokeWithArg(method, bce, ClassConfig.class, classConfig);
                    if (classConfig.isModified()) {
                        modifications.computeIfAbsent(className, k -> new ArrayList<>()).add(classConfig);
                    }
                }
                case CLASS_INFO -> invokeWithArg(method, bce,
                        jakarta.enterprise.lang.model.declarations.ClassInfo.class, vaubanClassInfo);
                case METHOD_CONFIG -> {
                    var classConfig = new VaubanClassConfig(vaubanClassInfo);
                    classConfig.setSourceBce(bce.getClass());
                    for (var mc : classConfig.methods()) invokeWithArg(method, bce, MethodConfig.class, mc);
                    if (classConfig.isModified()) {
                        modifications.computeIfAbsent(className, k -> new ArrayList<>()).add(classConfig);
                    }
                }
                case METHOD_INFO -> {
                    for (var mi : vaubanClassInfo.methods())
                        invokeWithArg(method, bce, jakarta.enterprise.lang.model.declarations.MethodInfo.class, mi);
                }
                case FIELD_CONFIG -> {
                    var classConfig = new VaubanClassConfig(vaubanClassInfo);
                    classConfig.setSourceBce(bce.getClass());
                    for (var fc : classConfig.fields()) invokeWithArg(method, bce, FieldConfig.class, fc);
                    if (classConfig.isModified()) {
                        modifications.computeIfAbsent(className, k -> new ArrayList<>()).add(classConfig);
                    }
                }
                case FIELD_INFO -> {
                    for (var fi : vaubanClassInfo.fields())
                        invokeWithArg(method, bce, jakarta.enterprise.lang.model.declarations.FieldInfo.class, fi);
                }
            }
        } catch (Exception e) {
            var unwrapped = e instanceof java.lang.reflect.InvocationTargetException ite
                    ? java.util.Objects.requireNonNullElse(ite.getCause(), ite) : e;
            errors.add("@Enhancement error: " + unwrapped.getMessage());
            errorCauses.add(unwrapped);
        }
    }

    /** Check if a Registration method uses InvokerFactory parameter. */
    private static boolean usesInvokerFactory(Method method) {
        for (var param : method.getParameterTypes()) {
            if (InvokerFactory.class.isAssignableFrom(param)) return true;
        }
        return false;
    }

    @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
    private static void invokeWithArg(Method method, Object bce, Class<?> targetType, Object arg) throws Exception {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (targetType.isAssignableFrom(paramType) || paramType.isAssignableFrom(targetType)) {
                args[i] = arg;
            }
            // Messages and Types can be added here if needed
        }
        method.invoke(bce, args);
    }

    /**
     * Process @Registration methods — main phase for Invokers.
     */
    @SuppressWarnings({"java:S3011", "java:S135", "java:S107"}) // CDI spec requires reflective access; BCE processing requires multiple contextual parameters
    private static void processRegistration(Object bce, Class<?> bceClass,
                                            List<BeanDescriptor> beans,
                                            List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
                                            List<io.vidocq.vauban.core.bean.model.InterceptorDescriptor> interceptors,
                                            IndexLookup lookup, ClassLoader classLoader,
                                            VaubanTypes types,
                                            List<String> errors,
                                            List<Class<?>> allArchiveClasses) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var registration = method.getAnnotation(Registration.class);
            if (registration == null) continue;

            makeAccessibleSafe(method);

            if (hasObserverInfoParam(method)) {
                for (var observer : observers) {
                    if (!BceTypeMatcher.matchesObserverTypes(registration.types(), observer, classLoader)) continue;
                    var observerInfo = new VaubanBceObserverInfo(observer, lookup);
                    invokeRegistrationMethodWithObserver(method, bce, observerInfo, types, errors);
                }
                continue;
            }

            // Collect interceptor class names to avoid double-processing
            var interceptorClassNames = new java.util.HashSet<String>();
            for (var ic : interceptors) {
                interceptorClassNames.add(ic.interceptorClass().value());
            }

            // Try matching against beans first (skip interceptors, handled below)
            boolean matched = false;
            for (var bean : beans) {
                if (interceptorClassNames.contains(bean.beanClass().value())) continue;
                if (!BceTypeMatcher.matchesTypes(registration.types(), bean, classLoader)) continue;
                matched = true;

                var beanInfo = new VaubanBceBeanInfo(bean, lookup);
                invokeRegistrationMethod(method, bce, beanInfo, classLoader, types, errors, beans);
            }

            // Also try matching interceptors
            for (var interceptor : interceptors) {
                if (!BceTypeMatcher.matchesInterceptorTypes(registration.types(), interceptor, classLoader)) continue;
                matched = true;

                var interceptorInfo = new VaubanBceInterceptorInfo(interceptor, lookup);
                invokeRegistrationMethod(method, bce, interceptorInfo, classLoader, types, errors, beans);
            }

            // If no beans matched and method doesn't use InvokerFactory, try archive classes
            if (!matched && allArchiveClasses != null && !usesInvokerFactory(method)) {
                for (var archiveClass : allArchiveClasses) {
                    if (!BceTypeMatcher.matchesClass(registration.types(), true, archiveClass)) continue;

                    var className = DotName.of(archiveClass.getName());
                    var indexClass = lookup.getClass(className).orElse(null);
                    if (indexClass == null) continue;

                    var minimalBean = io.vidocq.vauban.core.bean.model.BeanDescriptor.minimal(className);
                    var beanInfo = new VaubanBceBeanInfo(minimalBean, lookup);
                    invokeRegistrationMethod(method, bce, beanInfo, classLoader, types, errors, beans);
                }
            }
        }
    }

    private static boolean hasObserverInfoParam(Method method) {
        for (var param : method.getParameters()) {
            if (ObserverInfo.class.isAssignableFrom(param.getType())) return true;
        }
        return false;
    }

    private static void invokeRegistrationMethodWithObserver(Method method, Object bce,
                                                              VaubanBceObserverInfo observerInfo,
                                                              VaubanTypes types, List<String> errors) {
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
        } catch (java.lang.reflect.InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof IllegalStateException ise) {
                errors.add(ise.getMessage());
            } else {
                errors.add(MSG_REGISTRATION_ERROR
                        + (cause != null ? cause.getMessage() : e.getMessage()));
            }
        } catch (Exception e) {
            errors.add(MSG_REGISTRATION_ERROR + e.getMessage());
        }

        if (messages.hasErrors()) {
            errors.addAll(messages.getErrors());
        }
    }

    private static void invokeRegistrationMethod(Method method, Object bce,
                                                   BeanInfo beanInfo, ClassLoader classLoader,
                                                   VaubanTypes types, List<String> errors,
                                                   List<BeanDescriptor> allBeans) {
        var invokerFactory = new VaubanInvokerFactory(classLoader);
        var messages = new VaubanMessages();
        var args = resolveRegistrationArgs(method, beanInfo, invokerFactory, messages, types);

        try {
            method.invoke(bce, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof IllegalStateException ise) {
                errors.add(ise.getMessage());
            } else {
                errors.add(MSG_REGISTRATION_ERROR
                        + (cause != null ? cause.getMessage() : e.getMessage()));
            }
        } catch (Exception e) {
            errors.add(MSG_REGISTRATION_ERROR + e.getMessage());
        }

        if (messages.hasErrors()) {
            errors.addAll(messages.getErrors());
        }

        ExtensionMethodValidator.validateInvokerLookups(invokerFactory, allBeans, classLoader, errors);
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

    record SynthesisResult(
            List<VaubanSyntheticBeanBuilder<?>> beans,
            List<VaubanSyntheticObserverBuilder<?>> observers
    ) {}

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private static SynthesisResult processSynthesis(Object bce, Class<?> bceClass,
                                                     VaubanTypes types,
                                                     List<String> errors) {
        var components = new VaubanSyntheticComponents();

        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var synthesis = method.getAnnotation(Synthesis.class);
            if (synthesis == null) continue;

            makeAccessibleSafe(method);

            var args = resolveSynthesisArgs(method, components, types);

            try {
                method.invoke(bce, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                errors.add("@Synthesis error: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (Exception e) {
                errors.add("@Synthesis error: " + e.getMessage());
            }
        }

        return new SynthesisResult(components.getBeanDefinitions(), components.getObserverDefinitions());
    }

    /**
     * Process @Validation methods — collect errors that cause DeploymentException.
     */
    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private static void processValidation(Object bce, Class<?> bceClass, VaubanTypes types, List<String> errors) {
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
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                errors.add("@Validation error: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (Exception e) {
                errors.add("@Validation error: " + e.getMessage());
            }

            if (messages.hasErrors()) {
                for (var msg : messages.getErrors()) {
                    errors.add(msg);
                }
            }
        }
    }

    private static Object[] resolveSynthesisArgs(Method method, VaubanSyntheticComponents components,
                                                  VaubanTypes types) {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (SyntheticComponents.class.isAssignableFrom(paramType)) {
                args[i] = components;
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
            } catch (LinkageError ignored) {
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

    /** Synthesis builder -> descriptor conversion — delegated to {@link SyntheticBeanConverter}. */
    public static BeanDescriptor toBeanDescriptor(VaubanSyntheticBeanBuilder<?> synBean, int slot) {
        return SyntheticBeanConverter.toBeanDescriptor(synBean, slot);
    }
}
