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

        for (var bceClass : bceClasses) {
            try {
                var bce = instantiateBce(bceClass);
                processEnhancement(bce, bceClass, List.of(), archiveClasses, lookup, classLoader, errors, modifications);
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
                    invokeEnhancement(method, bce, paramKind, targetName, lookup, errors, modifications);
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
            Map<io.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>> enhancementModifications
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
        var allSyntheticBeans = new ArrayList<VaubanSyntheticBeanBuilder<?>>();
        var allSyntheticObservers = new ArrayList<VaubanSyntheticObserverBuilder<?>>();
        var allEnhancementMods = new HashMap<io.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>>();

        for (var bceClass : bceClasses) {
            try {
                // Validate method signatures before processing
                validateExtensionMethods(bceClass, definitionErrors);
                if (!definitionErrors.isEmpty()) continue;

                // Reuse instance from Discovery phase to maintain state
                var bce = bceInstances != null ? bceInstances.get(bceClass) : null;
                if (bce == null) bce = instantiateBce(bceClass);

                var types = new VaubanTypes(lookup);

                // Phase: @Enhancement — iterates over beans, fallback to archive classes if none match
                processEnhancement(bce, bceClass, beans, allArchiveClasses, lookup, classLoader, deploymentErrors, allEnhancementMods);

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
            }
        }

        return new Result(allSyntheticBeans, allSyntheticObservers, definitionErrors, deploymentErrors, allEnhancementMods);
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
                if (!matchesTypes(enhancement.types(), bean, classLoader)) continue;
                if (!matchesAnnotations(withAnnotations, bean.beanClass(), classLoader)) continue;
                processedClasses.add(bean.beanClass());
                invokeEnhancement(method, bce, paramKind, bean.beanClass(), lookup, errors, modifications);
            }

            // Also check archive classes for non-bean classes (e.g. added via ScannedClasses)
            if (archiveClasses != null) {
                for (var archiveClass : archiveClasses) {
                    var className = DotName.of(archiveClass.getName());
                    if (processedClasses.contains(className)) continue;
                    if (!matchesClass(enhancement.types(), enhancement.withSubtypes(), archiveClass)) continue;
                    if (!matchesAnnotations(withAnnotations, archiveClass)) continue;
                    processedClasses.add(className);
                    invokeEnhancement(method, bce, paramKind, className, lookup, errors, modifications);
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
                    if (!isObjectWildcard && !matchesClassByIndex(enhancement.types(),
                            enhancement.withSubtypes(), classInfo, lookup)) continue;
                    // Annotation matching via index
                    if (!withAnnotationNames.isEmpty()
                            && !matchesAnnotationsByIndex(withAnnotationNames, classInfo)) continue;
                    processedClasses.add(classInfo.name());
                    invokeEnhancement(method, bce, paramKind, classInfo.name(), lookup, errors, modifications);
                }
            }
        }
    }

    /** Index-based annotation matching: checks class, methods, fields, and constructors. */
    private static boolean matchesAnnotationsByIndex(Set<DotName> annotationNames,
                                                      io.vidocq.vauban.indexer.model.ClassInfo classInfo) {
        for (var ann : annotationNames) {
            if (classInfo.hasAnnotation(ann)) return true;
            for (var m : classInfo.methods()) {
                if (m.annotations().stream().anyMatch(a -> a.name().equals(ann))) return true;
            }
            for (var f : classInfo.fields()) {
                if (f.annotations().stream().anyMatch(a -> a.name().equals(ann))) return true;
            }
        }
        return false;
    }

    /** Index-based type matching using class hierarchy from the index. */
    private static boolean matchesClassByIndex(Class<?>[] types, boolean withSubtypes,
                                                io.vidocq.vauban.indexer.model.ClassInfo classInfo,
                                                IndexLookup lookup) {
        for (var type : types) {
            if (type == Object.class) return true;
            var typeName = DotName.of(type.getName());
            if (typeName.equals(classInfo.name())) return true;
            if (withSubtypes) {
                // Walk superclass chain
                var current = classInfo;
                while (current != null && current.superName() != null) {
                    if (typeName.equals(current.superName())) return true;
                    current = lookup.getClass(current.superName()).orElse(null);
                }
                // Check interfaces
                for (var iface : classInfo.interfaces()) {
                    if (typeName.equals(iface)) return true;
                }
            }
        }
        return false;
    }

    private static void invokeEnhancement(Method method, Object bce, EnhancementParamKind paramKind,
                                           DotName className, IndexLookup lookup,
                                           List<String> errors,
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
        }
    }

    /** Check if a Registration method uses InvokerFactory parameter. */
    private static boolean usesInvokerFactory(Method method) {
        for (var param : method.getParameterTypes()) {
            if (InvokerFactory.class.isAssignableFrom(param)) return true;
        }
        return false;
    }

    /** Check if a class matches Enhancement types filter. */
    private static boolean matchesClass(Class<?>[] types, boolean withSubtypes, Class<?> targetClass) {
        for (var type : types) {
            // Object.class is the CDI default wildcard — matches all types
            if (type == Object.class) return true;
            if (withSubtypes) {
                if (type.isAssignableFrom(targetClass)) return true;
            } else {
                if (type.equals(targetClass)) return true;
            }
        }
        return false;
    }

    /** Check if a class has at least one of the required annotations (resolved via classLoader from DotName). */
    private static boolean matchesAnnotations(Class<? extends java.lang.annotation.Annotation>[] withAnnotations,
                                              DotName beanClass, ClassLoader classLoader) {
        if (withAnnotations == null || withAnnotations.length == 0) return true;
        try {
            var clazz = classLoader.loadClass(beanClass.value());
            return matchesAnnotations(withAnnotations, clazz);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Check if a class has at least one of the required annotations (on class, methods, or fields). */
    private static boolean matchesAnnotations(Class<? extends java.lang.annotation.Annotation>[] withAnnotations,
                                              Class<?> targetClass) {
        if (withAnnotations == null || withAnnotations.length == 0) return true;
        for (var ann : withAnnotations) {
            if (targetClass.isAnnotationPresent(ann)) return true;
            for (var m : getDeclaredMethodsSafe(targetClass)) {
                if (m.isAnnotationPresent(ann)) return true;
            }
            // Defensive: getDeclaredFields/Constructors may throw NoClassDefFoundError
            // if a signature references an optional type absent from the classpath.
            try {
                for (var f : targetClass.getDeclaredFields()) {
                    if (f.isAnnotationPresent(ann)) return true;
                }
            } catch (LinkageError ignored) { /* skip */ }
            try {
                for (var c : targetClass.getDeclaredConstructors()) {
                    if (c.isAnnotationPresent(ann)) return true;
                }
            } catch (LinkageError ignored) { /* skip */ }
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
                    if (!matchesObserverTypes(registration.types(), observer, classLoader)) continue;
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
                if (!matchesTypes(registration.types(), bean, classLoader)) continue;
                matched = true;

                var beanInfo = new VaubanBceBeanInfo(bean, lookup);
                invokeRegistrationMethod(method, bce, beanInfo, classLoader, types, errors, beans);
            }

            // Also try matching interceptors
            for (var interceptor : interceptors) {
                if (!matchesInterceptorTypes(registration.types(), interceptor, classLoader)) continue;
                matched = true;

                var interceptorInfo = new VaubanBceInterceptorInfo(interceptor, lookup);
                invokeRegistrationMethod(method, bce, interceptorInfo, classLoader, types, errors, beans);
            }

            // If no beans matched and method doesn't use InvokerFactory, try archive classes
            if (!matched && allArchiveClasses != null && !usesInvokerFactory(method)) {
                for (var archiveClass : allArchiveClasses) {
                    if (!matchesClass(registration.types(), true, archiveClass)) continue;

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

    private static boolean matchesObserverTypes(Class<?>[] types, io.vidocq.vauban.core.bean.model.ObserverDescriptor observer, ClassLoader classLoader) {
        String declaringClassName = observer.declaringClass().value();
        try {
            var declaringClass = classLoader.loadClass(declaringClassName);
            for (var type : types) {
                if (type.isAssignableFrom(declaringClass)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException e) {
            // skip
        }
        return false;
    }

    private static boolean matchesInterceptorTypes(Class<?>[] types, io.vidocq.vauban.core.bean.model.InterceptorDescriptor interceptor, ClassLoader classLoader) {
        String className = interceptor.interceptorClass().value();
        try {
            var clazz = classLoader.loadClass(className);
            for (var type : types) {
                if (type.isAssignableFrom(clazz)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException e) {
            // skip
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

        validateInvokerLookups(invokerFactory, allBeans, classLoader, errors);
    }

    private static final Set<Class<?>> SPECIAL_LOOKUP_TYPES = Set.of(
            jakarta.enterprise.inject.Instance.class,
            jakarta.enterprise.event.Event.class,
            jakarta.enterprise.inject.spi.BeanManager.class
    );

    private static void validateInvokerLookups(VaubanInvokerFactory factory,
                                                List<BeanDescriptor> allBeans,
                                                ClassLoader classLoader,
                                                List<String> errors) {
        for (var builder : factory.getBuilders()) {
            var argLookups = builder.getArgumentLookups();
            if (argLookups.isEmpty()) continue;

            var reflectMethod = builder.getMethod();
            var paramTypes = reflectMethod.getParameterTypes();

            for (int idx : argLookups) {
                var paramType = paramTypes[idx];

                if (SPECIAL_LOOKUP_TYPES.stream().anyMatch(t -> t.isAssignableFrom(paramType))) {
                    continue;
                }

                int matchCount = 0;
                for (var bean : allBeans) {
                    try {
                        var beanClass = classLoader.loadClass(bean.beanClass().value());
                        if (paramType.isAssignableFrom(beanClass)) {
                            matchCount++;
                        }
                    } catch (ClassNotFoundException ignored) { // intentionally empty
                    }
                }

                if (matchCount == 0) {
                    errors.add("Invoker argument lookup unsatisfied: no bean found for parameter type "
                            + paramType.getName() + " at position " + idx
                            + " of method " + reflectMethod.getDeclaringClass().getName() + "." + reflectMethod.getName());
                } else if (matchCount > 1) {
                    errors.add("Invoker argument lookup ambiguous: " + matchCount + " beans found for parameter type "
                            + paramType.getName() + " at position " + idx
                            + " of method " + reflectMethod.getDeclaringClass().getName() + "." + reflectMethod.getName());
                }
            }
        }
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

    /**
     * Check if a bean matches any of the Registration/Enhancement types.
     * CDI spec: matches if the bean's set of bean types contains a type
     * that is assignable from at least one of the listed types.
     */
    private static boolean matchesTypes(Class<?>[] types, BeanDescriptor bean, ClassLoader classLoader) {
        for (var beanTypeInfo : bean.types()) {
            String beanTypeName = switch (beanTypeInfo) {
                case io.vidocq.vauban.indexer.model.TypeInfo.ClassType ct -> ct.name().value();
                case io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt -> pt.rawType().value();
                default -> null;
            };
            if (beanTypeName == null) continue;
            try {
                var beanType = classLoader.loadClass(beanTypeName);
                for (var type : types) {
                    if (type.isAssignableFrom(beanType)) {
                        return true;
                    }
                }
            } catch (ClassNotFoundException e) {
                // skip
            }
        }
        return false;
    }

    // Valid parameter types for each extension phase
    private static final java.util.Set<Class<?>> ENHANCEMENT_CONFIG_TYPES = java.util.Set.of(
            ClassConfig.class, MethodConfig.class, FieldConfig.class,
            jakarta.enterprise.lang.model.declarations.ClassInfo.class,
            jakarta.enterprise.lang.model.declarations.MethodInfo.class,
            jakarta.enterprise.lang.model.declarations.FieldInfo.class
    );
    private static final java.util.Set<Class<?>> ENHANCEMENT_OPTIONAL_TYPES = java.util.Set.of(
            Messages.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );
    private static final java.util.Set<Class<?>> REGISTRATION_PRIMARY_TYPES = java.util.Set.of(
            BeanInfo.class, InterceptorInfo.class, ObserverInfo.class
    );
    private static final java.util.Set<Class<?>> REGISTRATION_OPTIONAL_TYPES = java.util.Set.of(
            Messages.class, InvokerFactory.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );

    /**
     * Validate BCE method signatures. Invalid signatures → DefinitionException.
     */
    private static void validateExtensionMethods(Class<?> bceClass, List<String> errors) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            if (method.getAnnotation(Enhancement.class) != null) {
                validateEnhancementMethod(method, errors);
            }
            if (method.getAnnotation(Registration.class) != null) {
                validateRegistrationMethod(method, errors);
            }
        }
    }

    private static void validateEnhancementMethod(Method method, List<String> errors) {
        var params = method.getParameterTypes();
        // Must have exactly 1 config/declaration parameter
        int configCount = 0;
        for (var p : params) {
            if (ENHANCEMENT_CONFIG_TYPES.stream().anyMatch(t -> t.isAssignableFrom(p))) {
                configCount++;
            } else if (ENHANCEMENT_OPTIONAL_TYPES.stream().noneMatch(t -> t.isAssignableFrom(p))) {
                errors.add("@Enhancement method " + method.getName() + " has invalid parameter type: " + p.getName());
                return;
            }
        }
        if (configCount != 1) {
            errors.add("@Enhancement method " + method.getName()
                    + " must have exactly one ClassConfig/MethodConfig/FieldConfig parameter, found " + configCount);
        }
    }

    private static void validateRegistrationMethod(Method method, List<String> errors) {
        var params = method.getParameterTypes();
        // Must have exactly 1 BeanInfo or InterceptorInfo parameter
        int primaryCount = 0;
        for (var p : params) {
            if (REGISTRATION_PRIMARY_TYPES.stream().anyMatch(t -> t.isAssignableFrom(p))) {
                primaryCount++;
            } else if (REGISTRATION_OPTIONAL_TYPES.stream().noneMatch(t -> t.isAssignableFrom(p))) {
                errors.add("@Registration method " + method.getName() + " has invalid parameter type: " + p.getName());
                return;
            }
        }
        if (primaryCount != 1) {
            errors.add("@Registration method " + method.getName()
                    + " must have exactly one BeanInfo/InterceptorInfo parameter, found " + primaryCount);
        }
    }

    /**
     * Apply enhancement modifications to interceptor descriptors.
     * Handles @Priority additions from Enhancement phase.
     */
    public static List<InterceptorDescriptor> applyInterceptorEnhancements(
            List<InterceptorDescriptor> interceptors,
            Map<DotName, List<VaubanClassConfig>> modifications) {
        if (modifications.isEmpty()) return interceptors;

        var result = new ArrayList<InterceptorDescriptor>(interceptors.size());
        for (var descriptor : interceptors) {
            var configs = modifications.get(descriptor.interceptorClass());
            if (configs == null || configs.isEmpty()) {
                result.add(descriptor);
                continue;
            }
            var updated = descriptor;
            for (var config : configs) {
                for (var annInfo : config.getAddedAnnotationInfos()) {
                    if ("jakarta.annotation.Priority".equals(annInfo.name())) {
                        int priorityValue = 0;
                        var valueMember = annInfo.hasMember("value") ? annInfo.member("value") : null;
                        if (valueMember != null && valueMember.isInt()) {
                            priorityValue = valueMember.asInt();
                        }
                        updated = new InterceptorDescriptor(
                                updated.interceptorClass(), updated.bindings(),
                                updated.aroundInvokeMethod(), updated.aroundConstructMethod(),
                                priorityValue, true, updated.bindingAnnotations());
                    }
                }
            }
            result.add(updated);
        }
        return result;
    }

    /**
     * Apply enhancement modifications to bean descriptors. Returns the modified list.
     */
    public static List<BeanDescriptor> applyEnhancements(
            List<BeanDescriptor> descriptors,
            Map<DotName, List<VaubanClassConfig>> modifications) {

        if (modifications.isEmpty()) return descriptors;

        var result = new ArrayList<BeanDescriptor>(descriptors.size());
        for (var bean : descriptors) {
            var configs = modifications.get(bean.beanClass());
            if (configs == null || configs.isEmpty()) {
                result.add(bean);
                continue;
            }
            result.add(applyClassConfigs(bean, configs));
        }
        return result;
    }

    /**
     * Apply enhancement modifications to observer descriptors. Returns the modified list.
     * Parameter-level modifications on observer method parameters update observer qualifiers.
     * If removeAllAnnotations() was called on the observed parameter, the observer is removed entirely.
     */
    public static List<ObserverDescriptor> applyObserverEnhancements(
            List<ObserverDescriptor> observers,
            Map<DotName, List<VaubanClassConfig>> modifications) {

        if (modifications.isEmpty()) return observers;

        var result = new ArrayList<ObserverDescriptor>(observers.size());
        for (var observer : observers) {
            var configs = modifications.get(observer.declaringClass());
            if (configs == null || configs.isEmpty()) {
                result.add(observer);
                continue;
            }
            var modified = applyObserverConfigs(observer, configs);
            if (modified != null) {
                result.add(modified);
            }
            // null means the observer was removed (removeAllAnnotations removed @Observes)
        }
        return result;
    }

    @SuppressWarnings("java:S135")
    private static ObserverDescriptor applyObserverConfigs(ObserverDescriptor observer, List<VaubanClassConfig> configs) {
        var qualifiers = new LinkedHashSet<>(observer.qualifiers());
        boolean removed = false;

        for (var config : configs) {
            for (var methodConfig : config.getMethodConfigs()) {
                if (!methodConfig.info().name().equals(observer.methodName())) continue;
                if (!methodConfig.isModified()) continue;

                for (var paramConfig : methodConfig.getParameterConfigs()) {
                    if (!paramConfig.isModified()) continue;

                    // Check if this is the first parameter (the observed event parameter)
                    var paramIndex = methodConfig.getParameterConfigs().indexOf(paramConfig);
                    if (paramIndex != 0) continue; // Observer event parameter is typically the first

                    if (paramConfig.isAllAnnotationsRemoved()) {
                        // removeAllAnnotations removes @Observes/@ObservesAsync too → observer is removed
                        removed = true;
                        qualifiers.clear();
                    }

                    // Apply remove predicates to existing qualifiers
                    for (var predicate : paramConfig.getRemovePredicates()) {
                        qualifiers.removeIf(q -> {
                            var annInfo = qualifierToAnnotationInfo(q);
                            return annInfo != null && predicate.test(annInfo);
                        });
                    }

                    // Add new qualifier annotations
                    boolean hasExplicit = false;
                    for (var annClass : paramConfig.getAddedAnnotationClasses()) {
                        var qName = DotName.of(annClass.getName());
                        qualifiers.add(new QualifierInstance(qName, Map.of()));
                        if (!qName.equals(QualifierInstance.ANY_NAME) && !qName.equals(QualifierInstance.NAMED_NAME)
                                && !qName.equals(QualifierInstance.DEFAULT_NAME)) {
                            hasExplicit = true;
                        }
                    }
                    for (var annInfo : paramConfig.getAddedAnnotations()) {
                        var qi = annotationInfoToQualifier(annInfo);
                        qualifiers.add(qi);
                        if (!qi.annotationName().equals(QualifierInstance.ANY_NAME)
                                && !qi.annotationName().equals(QualifierInstance.NAMED_NAME)
                                && !qi.annotationName().equals(QualifierInstance.DEFAULT_NAME)) {
                            hasExplicit = true;
                        }
                    }
                    if (hasExplicit) {
                        qualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
                    }
                }
            }
        }

        if (removed && qualifiers.isEmpty()) {
            return null; // Observer removed
        }

        return new ObserverDescriptor(
                observer.declaringClass(), observer.methodName(), observer.eventType(),
                List.copyOf(qualifiers), observer.async(), observer.priority(),
                observer.reception(), observer.transactionPhase(), observer.syntheticInvoker()
        );
    }

    private static BeanDescriptor applyClassConfigs(BeanDescriptor bean, List<VaubanClassConfig> configs) {
        var qualifiers = new LinkedHashSet<>(bean.qualifiers());
        var interceptorBindings = new LinkedHashSet<>(bean.interceptorBindings());
        var interceptorBindingAnnotations = new ArrayList<>(bean.interceptorBindingAnnotations());
        var injectionPoints = new ArrayList<>(bean.injectionPoints());

        for (var config : configs) {
            // Class-level annotation removals
            for (var predicate : config.getRemovePredicates()) {
                qualifiers.removeIf(q -> {
                    var annInfo = qualifierToAnnotationInfo(q);
                    return annInfo != null && predicate.test(annInfo);
                });
            }
            if (config.isAllAnnotationsRemoved()) {
                qualifiers.clear();
                interceptorBindings.clear();
                interceptorBindingAnnotations.clear();
            }

            // Class-level annotation additions — only annotations that are actually
            // qualifiers should land in the qualifier set. Scopes / stereotypes /
            // bean-defining annotations are handled separately (index rebuild + scope
            // re-extraction) so they must not pollute qualifiers nor evict @Default.
            boolean classHasExplicit = false;
            for (var ann : config.getAddedAnnotations()) {
                if (!isQualifierAnnotation(ann)) continue;
                var qName = DotName.of(ann.getName());
                qualifiers.add(new QualifierInstance(qName, Map.of()));
                if (!qName.equals(QualifierInstance.ANY_NAME) && !qName.equals(QualifierInstance.NAMED_NAME)) {
                    classHasExplicit = true;
                }
            }
            for (var annInfo : config.getAddedAnnotationInfos()) {
                if (!isQualifierAnnotationInfo(annInfo)) continue;
                var qi = annotationInfoToQualifier(annInfo);
                qualifiers.add(qi);
                if (!qi.annotationName().equals(QualifierInstance.ANY_NAME)
                        && !qi.annotationName().equals(QualifierInstance.NAMED_NAME)) {
                    classHasExplicit = true;
                }
            }
            if (classHasExplicit) {
                qualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
            }

            // Class-level @InterceptorBinding additions -> propagate to bean's
            // interceptor bindings so they take part in interceptor resolution.
            // Without this, a BCE that uses ClassConfig.addAnnotation(SomeBinding.class)
            // to mark a bean class with a marker interceptor binding (e.g. SmallRye's
            // @FaultToleranceBinding pattern) is silently ignored — the binding never
            // reaches BeanDescriptor.interceptorBindings(), so InterceptorBeanWrapper
            // and InterceptorManager never wire the corresponding @Interceptor.
            for (var ann : config.getAddedAnnotations()) {
                if (!isInterceptorBindingAnnotation(ann)) continue;
                interceptorBindings.add(DotName.of(ann.getName()));
            }
            for (var annInfo : config.getAddedAnnotationInfos()) {
                if (!isInterceptorBindingAnnotationInfo(annInfo)) continue;
                interceptorBindings.add(DotName.of(annInfo.name()));
                if (annInfo instanceof BuiltAnnotationInfo built) {
                    try {
                        var proxy = createAnnotationProxy(built);
                        if (proxy != null) interceptorBindingAnnotations.add(proxy);
                    } catch (Exception ignored) {
                        // intentionally empty — best effort binding-member capture
                    }
                }
            }

            // Field-level modifications -> update injection points
            for (var fieldConfig : config.getFieldConfigs()) {
                if (!fieldConfig.isModified()) continue;
                applyFieldEnhancement(fieldConfig, injectionPoints);
            }

            // Method-level modifications -> update interceptor bindings
            for (var methodConfig : config.getMethodConfigs()) {
                if (!methodConfig.isModified()) continue;
                applyMethodEnhancement(methodConfig, interceptorBindings, interceptorBindingAnnotations);

                // Parameter-level modifications -> update observer/injection qualifiers
                for (var paramConfig : methodConfig.getParameterConfigs()) {
                    if (!paramConfig.isModified()) continue;
                    applyParameterEnhancement();
                }
            }
        }

        // Re-add @Any if qualifiers were modified and it was there before
        if (!qualifiers.isEmpty() && qualifiers.stream().noneMatch(QualifierInstance::isAny)
                && bean.qualifiers().stream().anyMatch(QualifierInstance::isAny)) {
            qualifiers.add(QualifierInstance.ANY);
        }

        return new BeanDescriptor(
                bean.id(), bean.beanClass(), bean.kind(), bean.types(),
                qualifiers, bean.scope(), bean.isAlternative(), bean.priority(),
                injectionPoints, bean.name(), interceptorBindings,
                bean.constructorBindings(), interceptorBindingAnnotations
        );
    }

    @SuppressWarnings("java:S135")
    private static void applyFieldEnhancement(VaubanFieldConfig fieldConfig,
                                               List<InjectionPointInfo> injectionPoints) {
        String fieldName = fieldConfig.info().name();

        for (int i = 0; i < injectionPoints.size(); i++) {
            var ip = injectionPoints.get(i);
            if (ip.kind() != InjectionPointInfo.InjectionKind.FIELD) continue;
            if (!ip.description().contains(fieldName)) continue;

            var ipQualifiers = new LinkedHashSet<>(ip.qualifiers());

            if (fieldConfig.isAllAnnotationsRemoved()) {
                ipQualifiers.clear();
            }

            boolean hasExplicitQualifier = false;
            for (var ann : fieldConfig.getAddedAnnotations()) {
                var qName = DotName.of(ann.getName());
                ipQualifiers.add(new QualifierInstance(qName, Map.of()));
                if (!qName.equals(QualifierInstance.ANY_NAME) && !qName.equals(QualifierInstance.NAMED_NAME)) {
                    hasExplicitQualifier = true;
                }
            }
            for (var annInfo : fieldConfig.getAddedAnnotationInfos()) {
                var qi = annotationInfoToQualifier(annInfo);
                ipQualifiers.add(qi);
                if (!qi.annotationName().equals(QualifierInstance.ANY_NAME)
                        && !qi.annotationName().equals(QualifierInstance.NAMED_NAME)) {
                    hasExplicitQualifier = true;
                }
            }
            // CDI spec: @Default is removed when an explicit qualifier is added
            if (hasExplicitQualifier) {
                ipQualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
            }

            injectionPoints.set(i, new InjectionPointInfo(
                    ip.requiredType(), ipQualifiers, ip.kind(), ip.description()));
        }
    }

    private static void applyMethodEnhancement(VaubanMethodConfig methodConfig,
                                                Set<DotName> interceptorBindings,
                                                List<Annotation> interceptorBindingAnnotations) {
        for (var ann : methodConfig.getAddedAnnotations()) {
            interceptorBindings.add(DotName.of(ann.getName()));
        }
        // Store annotation instances for member value matching
        for (var ann : methodConfig.getAddedAnnotationInstances()) {
            interceptorBindingAnnotations.add(ann);
        }
        for (var annInfo : methodConfig.getAddedAnnotationInfos()) {
            interceptorBindings.add(DotName.of(annInfo.name()));
            if (annInfo instanceof BuiltAnnotationInfo built) {
                try {
                    var proxy = createAnnotationProxy(built);
                    if (proxy != null) interceptorBindingAnnotations.add(proxy);
                } catch (Exception ignored) {
                    // intentionally empty
                }
            }
        }
    }

    private static void applyParameterEnhancement() {
        // Parameter modifications affect observer qualifiers, handled via observer descriptors
        // For now this is mainly used by ChangeObserverQualifierTest which modifies observer parameters
        // The actual observer modification happens in the observer discovery phase
    }

    private static boolean isQualifierAnnotation(Class<? extends Annotation> ann) {
        return ann.isAnnotationPresent(jakarta.inject.Qualifier.class)
                || ann == jakarta.enterprise.inject.Default.class
                || ann == jakarta.enterprise.inject.Any.class
                || ann == jakarta.inject.Named.class;
    }

    private static boolean isQualifierAnnotationInfo(AnnotationInfo annInfo) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            @SuppressWarnings("unchecked")
            var clazz = (Class<? extends Annotation>) (cl != null
                    ? Class.forName(annInfo.name(), false, cl)
                    : Class.forName(annInfo.name()));
            return isQualifierAnnotation(clazz);
        } catch (ClassNotFoundException e) {
            // Unknown annotation: treat as qualifier to keep prior behaviour for cases
            // where the annotation is not on the runtime classpath.
            return true;
        }
    }

    private static boolean isInterceptorBindingAnnotation(Class<? extends Annotation> ann) {
        return ann.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class);
    }

    private static boolean isInterceptorBindingAnnotationInfo(AnnotationInfo annInfo) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            @SuppressWarnings("unchecked")
            var clazz = (Class<? extends Annotation>) (cl != null
                    ? Class.forName(annInfo.name(), false, cl)
                    : Class.forName(annInfo.name()));
            return isInterceptorBindingAnnotation(clazz);
        } catch (ClassNotFoundException e) {
            // Unknown annotation on the runtime classpath: don't assume it is
            // an interceptor binding (the previous behaviour was to drop it).
            return false;
        }
    }

    private static QualifierInstance annotationInfoToQualifier(AnnotationInfo annInfo) {
        var members = new LinkedHashMap<String, AnnotationValue>();
        if (annInfo.members() != null) {
            for (var entry : annInfo.members().entrySet()) {
                var member = entry.getValue();
                members.put(entry.getKey(), annotationMemberToValue(member));
            }
        }
        return new QualifierInstance(DotName.of(annInfo.name()), members);
    }

    private static AnnotationValue annotationMemberToValue(AnnotationMember member) {
        return switch (member.kind()) {
            case STRING -> new AnnotationValue.StringVal(member.asString());
            case BOOLEAN -> new AnnotationValue.BooleanVal(member.asBoolean());
            case INT -> new AnnotationValue.IntVal(member.asInt());
            case LONG -> new AnnotationValue.LongVal(member.asLong());
            case DOUBLE -> new AnnotationValue.DoubleVal(member.asDouble());
            case FLOAT -> new AnnotationValue.FloatVal(member.asFloat());
            case BYTE -> new AnnotationValue.ByteVal(member.asByte());
            case SHORT -> new AnnotationValue.ShortVal(member.asShort());
            case CHAR -> new AnnotationValue.CharVal(member.asChar());
            default -> new AnnotationValue.StringVal(member.toString());
        };
    }

    private static AnnotationInfo qualifierToAnnotationInfo(QualifierInstance q) {
        var members = new LinkedHashMap<String, AnnotationMember>();
        return new SimpleAnnotationInfo(q.annotationName().value(), members);
    }

    @SuppressWarnings("unchecked")
    private static Annotation createAnnotationProxy(BuiltAnnotationInfo built) {
        var annotationType = built.annotationType();
        return (Annotation) java.lang.reflect.Proxy.newProxyInstance(
                annotationType.getClassLoader(),
                new Class<?>[]{annotationType},
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) return annotationType;
                    if ("toString".equals(method.getName())) return "@" + annotationType.getName();
                    if ("hashCode".equals(method.getName())) return 0;
                    if ("equals".equals(method.getName())) return false;
                    var member = built.member(method.getName());
                    if (member != null) {
                        return switch (member.kind()) {
                            case STRING -> member.asString();
                            case BOOLEAN -> member.asBoolean();
                            case INT -> member.asInt();
                            case LONG -> member.asLong();
                            case DOUBLE -> member.asDouble();
                            case FLOAT -> member.asFloat();
                            case BYTE -> member.asByte();
                            case SHORT -> member.asShort();
                            case CHAR -> member.asChar();
                            default -> method.getDefaultValue();
                        };
                    }
                    return method.getDefaultValue();
                }
        );
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
    private static Method[] getDeclaredMethodsSafe(Class<?> cls) {
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

    /**
     * Converts a {@link VaubanSyntheticBeanBuilder} (the in-memory result of a BCE {@code @Synthesis}
     * method) into a {@link BeanDescriptor} that the resolver and validator can reason about.
     *
     * <p>Used both by the runtime container (to register the bean in the live bean manager) and by
     * the APT processor (to feed the deployment validator so {@code @Inject MyBceBean} compiles
     * cleanly without an {@code Instance<>} workaround).
     *
     * <p>The {@code slot} parameter is folded into the synthetic bean's unique key — it must be
     * unique within the surrounding bean list (callers typically pass {@code descriptors.size()}).
     * No factory or creator is materialized here; that is a runtime-only concern handled by the
     * container.
     */
    public static BeanDescriptor toBeanDescriptor(VaubanSyntheticBeanBuilder<?> synBean, int slot) {
        var beanClass = synBean.getBeanClass();
        var beanName = DotName.of(beanClass.getName());

        var beanTypes = new LinkedHashSet<TypeInfo>();
        for (var type : synBean.getTypes()) {
            if (type instanceof Class<?> cls) {
                beanTypes.add(new TypeInfo.ClassType(DotName.of(cls.getName())));
            }
        }
        // Bean types added via the lang-model API (type(jakarta...Type)) are stored
        // separately and already converted to TypeInfo. Cf. VAU-BCE-001.
        beanTypes.addAll(synBean.getIndexTypes());
        if (beanTypes.isEmpty()) {
            beanTypes.add(new TypeInfo.ClassType(beanName));
            beanTypes.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
        }

        var scope = ScopeInfo.DEPENDENT;
        if (synBean.getScopeAnnotation() != null) {
            var scopeAnn = synBean.getScopeAnnotation();
            if (scopeAnn == jakarta.enterprise.context.ApplicationScoped.class) {
                scope = ScopeInfo.APPLICATION;
            } else if (scopeAnn == jakarta.enterprise.context.RequestScoped.class) {
                scope = ScopeInfo.REQUEST;
            } else if (scopeAnn == jakarta.inject.Singleton.class) {
                scope = ScopeInfo.SINGLETON;
            } else if (scopeAnn == jakarta.enterprise.context.SessionScoped.class) {
                scope = ScopeInfo.SESSION;
            } else if (scopeAnn == jakarta.enterprise.context.ConversationScoped.class) {
                scope = ScopeInfo.CONVERSATION;
            }
        }

        var qualifiers = new LinkedHashSet<QualifierInstance>();
        boolean hasExplicitQualifier = false;
        for (var q : synBean.getQualifiers()) {
            var qName = DotName.of(q.annotationType().getName());
            if (!qName.equals(QualifierInstance.DEFAULT_NAME) && !qName.equals(QualifierInstance.ANY_NAME)) {
                hasExplicitQualifier = true;
            }
            // Preserve qualifier members so the resolver can match @Tagged("scalar")
            // against an injection point whose qualifier has the same value (and
            // reject one with a different value). Cf. VAU-BCE-001.
            qualifiers.add(new QualifierInstance(qName, extractAnnotationMembers(q)));
        }
        if (!hasExplicitQualifier) {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
        qualifiers.add(QualifierInstance.ANY);

        var syntheticKey = DotName.of(beanName.value() + "#synthetic#" + slot);
        return new BeanDescriptor(
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
    }

    /**
     * Extract the member values of an annotation instance via reflection so the
     * resolver can compare qualifier members at runtime. Annotation members
     * with non-trivial types (Class, Enum, nested annotation, arrays) are
     * mapped to their {@link AnnotationValue} counterpart; unsupported shapes
     * fall through to a string representation. Cf. VAU-BCE-001.
     */
    @SuppressWarnings("java:S3011")
    private static Map<String, AnnotationValue> extractAnnotationMembers(Annotation annotation) {
        var annType = annotation.annotationType();
        var out = new LinkedHashMap<String, AnnotationValue>();
        for (var m : annType.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            try {
                m.setAccessible(true);
                var value = m.invoke(annotation);
                var converted = toAnnotationValue(value);
                if (converted != null) out.put(m.getName(), converted);
            } catch (ReflectiveOperationException ignored) {
                // member not readable — skip silently, the resolver simply won't have
                // a value to compare against, which is the same as before this fix.
            }
        }
        return out;
    }

    private static AnnotationValue toAnnotationValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return new AnnotationValue.StringVal(s);
        if (value instanceof Boolean b) return new AnnotationValue.BooleanVal(b);
        if (value instanceof Byte b) return new AnnotationValue.ByteVal(b);
        if (value instanceof Character c) return new AnnotationValue.CharVal(c);
        if (value instanceof Short s) return new AnnotationValue.ShortVal(s);
        if (value instanceof Integer i) return new AnnotationValue.IntVal(i);
        if (value instanceof Long l) return new AnnotationValue.LongVal(l);
        if (value instanceof Float f) return new AnnotationValue.FloatVal(f);
        if (value instanceof Double d) return new AnnotationValue.DoubleVal(d);
        if (value instanceof Class<?> c) return new AnnotationValue.ClassVal(DotName.of(c.getName()));
        if (value instanceof Enum<?> e) return new AnnotationValue.EnumVal(
                DotName.of(e.getDeclaringClass().getName()), e.name());
        if (value instanceof Annotation a) return new AnnotationValue.AnnotationVal(
                new io.vidocq.vauban.indexer.model.AnnotationInfo(
                        DotName.of(a.annotationType().getName()),
                        extractAnnotationMembers(a)));
        if (value.getClass().isArray()) {
            var len = java.lang.reflect.Array.getLength(value);
            var elements = new ArrayList<AnnotationValue>(len);
            for (int i = 0; i < len; i++) {
                var converted = toAnnotationValue(java.lang.reflect.Array.get(value, i));
                if (converted != null) elements.add(converted);
            }
            return new AnnotationValue.ArrayVal(elements);
        }
        return new AnnotationValue.StringVal(value.toString());
    }
}
