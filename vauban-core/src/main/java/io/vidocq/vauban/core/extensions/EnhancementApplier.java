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
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.langmodel.BuiltAnnotationInfo;
import io.vidocq.vauban.core.langmodel.LangModelAnnotations;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies recorded {@code @Enhancement} modifications ({@link VaubanClassConfig} and its
 * field/method/parameter configs) back onto bean, interceptor and observer descriptors.
 * Extracted from {@link BceProcessor} (which stays the facade and delegates).
 *
 * <p>Also hosts the annotation bridge this application step relies on: qualifier /
 * interceptor-binding detection on added annotations, {@code AnnotationInfo} ↔
 * {@link QualifierInstance} conversion, and the proxy materialization of
 * {@link BuiltAnnotationInfo} for binding-member matching.
 */
final class EnhancementApplier {

    private EnhancementApplier() {
    }

    static List<InterceptorDescriptor> applyInterceptorEnhancements(
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
     *
     * <p>A class that gains {@code @Vetoed} through an enhancement is excluded entirely —
     * bean discovery must see the enhanced annotations, so an added {@code @Vetoed} has the
     * same effect as a source-level one (CDI 4.1). This covers index-discovered beans; classes
     * vetoed before discovery are already filtered by {@code BeanDiscovery.isVetoed}.
     */
    static List<BeanDescriptor> applyEnhancements(
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
            if (addsVeto(configs)) continue;
            result.add(applyClassConfigs(bean, configs));
        }
        return result;
    }

    /** True when any enhancement config adds {@code @Vetoed} at class level. */
    private static boolean addsVeto(List<VaubanClassConfig> configs) {
        for (var config : configs) {
            if (config.getAddedAnnotations().contains(jakarta.enterprise.inject.Vetoed.class)) {
                return true;
            }
            for (var annInfo : config.getAddedAnnotationInfos()) {
                if ("jakarta.enterprise.inject.Vetoed".equals(annInfo.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Apply enhancement modifications to observer descriptors. Returns the modified list.
     * Parameter-level modifications on observer method parameters update observer qualifiers.
     * If removeAllAnnotations() was called on the observed parameter, the observer is removed entirely.
     */
    static List<ObserverDescriptor> applyObserverEnhancements(
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
        var enhancedInjectionMethods = new LinkedHashSet<>(bean.enhancedInjectionMethods());

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

            // Method-level modifications -> update interceptor bindings and injection points
            for (var methodConfig : config.getMethodConfigs()) {
                if (!methodConfig.isModified()) continue;
                applyMethodEnhancement(methodConfig, interceptorBindings, interceptorBindingAnnotations);

                boolean initializer = !methodConfig.info().isConstructor()
                        && !methodConfig.info().isStatic()
                        && hasAnnotation(methodConfig, jakarta.inject.Inject.class);
                if (initializer && hasAddedAnnotation(methodConfig, jakarta.inject.Inject.class)) {
                    enhancedInjectionMethods.add(initializerMethodKey(methodConfig));
                }

                for (int i = 0; i < methodConfig.getParameterConfigs().size(); i++) {
                    var paramConfig = methodConfig.getParameterConfigs().get(i);
                    if (paramConfig.isModified()) {
                        applyParameterEnhancement(methodConfig, paramConfig, i, initializer, injectionPoints);
                    }
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
                bean.constructorBindings(), interceptorBindingAnnotations, enhancedInjectionMethods
        );
    }

    private static void applyFieldEnhancement(VaubanFieldConfig fieldConfig,
                                               List<InjectionPointInfo> injectionPoints) {
        String description = InjectionPointInfo.fieldDescription(
                indexedSimpleName(fieldConfig.info().declaringClass().name()), fieldConfig.info().name());
        int existingIndex = findPoint(injectionPoints, InjectionPointInfo.InjectionKind.FIELD, description);
        var sourceInject = fieldConfig.info().annotation(jakarta.inject.Inject.class);
        boolean removedInject = fieldConfig.isAllAnnotationsRemoved()
                || (sourceInject != null && fieldConfig.getRemovePredicates().stream().anyMatch(p -> p.test(sourceInject)));
        boolean injected = hasAddedAnnotation(fieldConfig, jakarta.inject.Inject.class)
                || (sourceInject != null && !removedInject);
        if (!injected) {
            if (existingIndex >= 0) injectionPoints.remove(existingIndex);
            return;
        }

        var existing = existingIndex >= 0 ? injectionPoints.get(existingIndex) : null;
        var declared = existing == null
                ? new LinkedHashSet<QualifierInstance>()
                : new LinkedHashSet<>(existing.declaredQualifiers());
        if (fieldConfig.isAllAnnotationsRemoved()) declared.clear();
        for (var predicate : fieldConfig.getRemovePredicates()) {
            declared.removeIf(q -> {
                var annotation = qualifierToAnnotationInfo(q);
                return annotation != null && predicate.test(annotation);
            });
        }
        addQualifiers(declared, fieldConfig.getAddedAnnotations(), fieldConfig.getAddedAnnotationInfos(),
                fieldConfig.getAddedAnnotationInstances());
        completeDeclaredQualifiers(declared);
        var resolved = completedQualifiers(declared);

        var requiredType = existing == null
                ? LangModelTypeMapper.toIndexType(fieldConfig.info().type())
                : existing.requiredType();
        var point = new InjectionPointInfo(requiredType, resolved, declared,
                InjectionPointInfo.InjectionKind.FIELD, description);
        if (existingIndex >= 0) injectionPoints.set(existingIndex, point);
        else injectionPoints.add(point);
    }

    private static void applyMethodEnhancement(VaubanMethodConfig methodConfig,
                                                Set<DotName> interceptorBindings,
                                                List<Annotation> interceptorBindingAnnotations) {
        for (var ann : methodConfig.getAddedAnnotations()) {
            if (isInterceptorBindingAnnotation(ann)) interceptorBindings.add(DotName.of(ann.getName()));
        }
        // Store annotation instances for member value matching
        for (var ann : methodConfig.getAddedAnnotationInstances()) {
            if (isInterceptorBindingAnnotation(ann.annotationType())) interceptorBindingAnnotations.add(ann);
        }
        for (var annInfo : methodConfig.getAddedAnnotationInfos()) {
            if (!isInterceptorBindingAnnotationInfo(annInfo)) continue;
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

    private static void applyParameterEnhancement(VaubanMethodConfig methodConfig,
            VaubanParameterConfig parameterConfig, int index, boolean initializer,
            List<InjectionPointInfo> injectionPoints) {
        var method = methodConfig.info();
        var kind = method.isConstructor()
                ? InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER
                : InjectionPointInfo.InjectionKind.METHOD_PARAMETER;
        var description = InjectionPointInfo.parameterDescription(
                indexedSimpleName(method.declaringClass().name()),
                method.isConstructor() ? null : method.name(), index,
                method.parameters().stream()
                        .map(parameter -> LangModelTypeMapper.toIndexType(parameter.type())).toList());
        int existingIndex = findPoint(injectionPoints, kind, description);
        if (existingIndex < 0 && !initializer) return;

        var existing = existingIndex >= 0 ? injectionPoints.get(existingIndex) : null;
        var declared = existing == null
                ? new LinkedHashSet<QualifierInstance>()
                : new LinkedHashSet<>(existing.declaredQualifiers());
        if (parameterConfig.isAllAnnotationsRemoved()) declared.clear();
        for (var predicate : parameterConfig.getRemovePredicates()) {
            declared.removeIf(q -> {
                var annotation = qualifierToAnnotationInfo(q);
                return annotation != null && predicate.test(annotation);
            });
        }
        addQualifiers(declared, parameterConfig.getAddedAnnotationClasses(),
                parameterConfig.getAddedAnnotations(), parameterConfig.getAddedAnnotationInstances());
        completeDeclaredQualifiers(declared);
        var resolved = completedQualifiers(declared);

        var requiredType = existing == null
                ? LangModelTypeMapper.toIndexType(parameterConfig.info().type())
                : existing.requiredType();
        var point = new InjectionPointInfo(requiredType, resolved, declared, kind, description);
        if (existingIndex >= 0) injectionPoints.set(existingIndex, point);
        else injectionPoints.add(point);
    }

    private static int findPoint(List<InjectionPointInfo> points,
            InjectionPointInfo.InjectionKind kind, String description) {
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            if (point.kind() == kind && point.description().equals(description)) return i;
        }
        return -1;
    }

    private static void completeDeclaredQualifiers(Set<QualifierInstance> qualifiers) {
        boolean explicit = qualifiers.stream().anyMatch(q -> !q.annotationName().equals(QualifierInstance.ANY_NAME)
                && !q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
        if (explicit) {
            qualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
        } else {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
    }

    private static Set<QualifierInstance> completedQualifiers(Set<QualifierInstance> declared) {
        var qualifiers = new LinkedHashSet<>(declared);
        qualifiers.add(QualifierInstance.ANY);
        return qualifiers;
    }

    private static void addQualifiers(Set<QualifierInstance> qualifiers,
            Set<Class<? extends Annotation>> classes, List<AnnotationInfo> infos,
            List<Annotation> instances) {
        var instanceNames = instances.stream().map(a -> a.annotationType().getName())
                .collect(java.util.stream.Collectors.toSet());
        for (var annotation : classes) {
            if (isQualifierAnnotation(annotation) && !instanceNames.contains(annotation.getName())) {
                qualifiers.add(new QualifierInstance(DotName.of(annotation.getName()), Map.of()));
            }
        }
        for (var annotation : infos) {
            if (isQualifierAnnotationInfo(annotation)) qualifiers.add(annotationInfoToQualifier(annotation));
        }
        for (var annotation : instances) {
            if (isQualifierAnnotation(annotation.annotationType())) {
                qualifiers.add(QualifierInstance.from(
                        io.vidocq.vauban.core.annotation.AnnotationValues.infoOf(annotation)));
            }
        }
    }

    private static boolean hasAddedAnnotation(VaubanFieldConfig config, Class<? extends Annotation> type) {
        return config.getAddedAnnotations().contains(type)
                || config.getAddedAnnotationInfos().stream().anyMatch(a -> a.name().equals(type.getName()))
                || config.getAddedAnnotationInstances().stream().anyMatch(a -> a.annotationType() == type);
    }

    private static boolean hasAddedAnnotation(VaubanMethodConfig config, Class<? extends Annotation> type) {
        return config.getAddedAnnotations().contains(type)
                || config.getAddedAnnotationInfos().stream().anyMatch(a -> a.name().equals(type.getName()))
                || config.getAddedAnnotationInstances().stream().anyMatch(a -> a.annotationType() == type);
    }

    private static boolean hasAddedAnnotation(VaubanParameterConfig config, Class<? extends Annotation> type) {
        return config.getAddedAnnotationClasses().contains(type)
                || config.getAddedAnnotations().stream().anyMatch(a -> a.name().equals(type.getName()))
                || config.getAddedAnnotationInstances().stream().anyMatch(a -> a.annotationType() == type);
    }

    private static boolean hasAnnotation(VaubanMethodConfig config, Class<? extends Annotation> type) {
        var original = config.info().annotation(type);
        boolean removed = config.isAllAnnotationsRemoved()
                || (original != null && config.getRemovePredicates().stream().anyMatch(p -> p.test(original)));
        return hasAddedAnnotation(config, type) || (original != null && !removed);
    }

    private static String initializerMethodKey(VaubanMethodConfig config) {
        var method = config.info();
        return InjectionPointInfo.enhancedInitializerMethod(
                method.declaringClass().name(), method.name(),
                InjectionPointInfo.methodDescriptor(method.parameters().stream()
                        .map(parameter -> LangModelTypeMapper.toIndexType(parameter.type())).toList()));
    }

    private static String indexedSimpleName(String className) {
        int packageSeparator = className.lastIndexOf('.');
        return className.substring(packageSeparator + 1);
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

    /**
     * Every member kind kept: an enum, a class, a nested annotation or an array added by an extension
     * has to compare equal to the same annotation written in source (BUG-20260914-10).
     */
    private static QualifierInstance annotationInfoToQualifier(AnnotationInfo annInfo) {
        return QualifierInstance.from(LangModelAnnotations.toIndex(annInfo));
    }

    private static AnnotationInfo qualifierToAnnotationInfo(QualifierInstance q) {
        var members = new LinkedHashMap<String, AnnotationMember>();
        return new SimpleAnnotationInfo(q.annotationName().value(), members);
    }

    /** The instance of a binding an extension added, for the member-value comparison of interceptor resolution. */
    private static Annotation createAnnotationProxy(BuiltAnnotationInfo built) {
        return io.vidocq.vauban.core.annotation.AnnotationInstances.create(built.annotationType(),
                LangModelAnnotations.toIndex(built), built.annotationType().getClassLoader());
    }
}
