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

    /**
     * The qualifier an added annotation stands for, with the member values of the instance an
     * extension gave, when it gave one (BUG-20261008-05).
     */
    private static QualifierInstance qualifierOf(Class<? extends Annotation> type, Annotation instance) {
        return instance != null
                ? QualifierInstance.from(io.vidocq.vauban.core.annotation.AnnotationValues.infoOf(instance))
                : new QualifierInstance(DotName.of(type.getName()), Map.of());
    }

    /**
     * The name an added {@code @Named} gives the bean — its value, or the default name when it has
     * none — or {@code null} when the configs add no {@code @Named}.
     */
    private static String addedName(List<VaubanClassConfig> configs) {
        String name = null;
        for (var config : configs) {
            for (var info : config.getAddedAnnotationsIndexed()) {
                if (!info.name().equals(QualifierInstance.NAMED_NAME)) continue;
                var value = info.member("value") instanceof io.vidocq.vauban.indexer.model.AnnotationValue.StringVal sv
                        ? sv.value() : "";
                if (value.isEmpty() && config.info() != null) {
                    var simpleName = config.info().simpleName();
                    value = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
                }
                if (!value.isEmpty()) name = value;
            }
        }
        return name;
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
                qualifiers.add(qualifierOf(ann, config.getAddedAnnotationInstance(ann)));
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

        // An added @Named names the bean, and its qualifier carries that name, as discovery gives it
        var name = bean.name();
        var addedName = addedName(configs);
        if (addedName != null) {
            name = addedName;
            qualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.NAMED_NAME));
            qualifiers.add(new QualifierInstance(QualifierInstance.NAMED_NAME,
                    Map.of("value", new io.vidocq.vauban.indexer.model.AnnotationValue.StringVal(addedName))));
        }

        return new BeanDescriptor(
                bean.id(), bean.beanClass(), bean.kind(), bean.types(),
                qualifiers, bean.scope(), bean.isAlternative(), bean.priority(),
                injectionPoints, name, interceptorBindings,
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
                ipQualifiers.add(qualifierOf(ann, fieldConfig.getAddedAnnotationInstance(ann)));
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
