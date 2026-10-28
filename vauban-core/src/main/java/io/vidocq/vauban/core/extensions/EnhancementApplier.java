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
import io.vidocq.vauban.core.loading.DeploymentClasses;
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
import java.util.function.Predicate;

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

    private static final String VALUE = "value";

    private EnhancementApplier() {
    }

    static List<InterceptorDescriptor> applyInterceptorEnhancements(
            List<InterceptorDescriptor> interceptors,
            Map<DotName, List<VaubanClassConfig>> modifications) {
        if (modifications.isEmpty()) return interceptors;

        var result = new ArrayList<InterceptorDescriptor>(interceptors.size());
        for (var descriptor : interceptors) {
            var configs = modifications.get(descriptor.interceptorClass());
            result.add(configs == null || configs.isEmpty() ? descriptor : withAddedPriority(descriptor, configs));
        }
        return result;
    }

    /** The interceptor with the priority the last {@code @Priority} an enhancement added gives it, if any. */
    private static InterceptorDescriptor withAddedPriority(InterceptorDescriptor descriptor,
                                                           List<VaubanClassConfig> configs) {
        var updated = descriptor;
        for (var config : configs) {
            for (var annInfo : config.getAddedAnnotationInfos()) {
                if ("jakarta.annotation.Priority".equals(annInfo.name())) {
                    updated = new InterceptorDescriptor(
                            updated.interceptorClass(), updated.bindings(),
                            updated.aroundInvokeMethod(), updated.aroundConstructMethod(),
                            priorityValue(annInfo), true, updated.bindingAnnotations());
                }
            }
        }
        return updated;
    }

    /** The {@code int} value of an added {@code @Priority}, or {@code 0} when it carries none. */
    private static int priorityValue(AnnotationInfo priority) {
        var valueMember = priority.hasMember(VALUE) ? priority.member(VALUE) : null;
        return valueMember != null && valueMember.isInt() ? valueMember.asInt() : 0;
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
            } else if (!addsVeto(configs)) {
                result.add(applyClassConfigs(bean, configs));
            }
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

    private static ObserverDescriptor applyObserverConfigs(ObserverDescriptor observer, List<VaubanClassConfig> configs) {
        var qualifiers = new LinkedHashSet<>(observer.qualifiers());
        boolean removed = false;

        for (var config : configs) {
            for (var methodConfig : config.getMethodConfigs()) {
                if (methodConfig.info().name().equals(observer.methodName()) && methodConfig.isModified()) {
                    removed |= applyObservedParameterConfig(methodConfig, qualifiers);
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
     * Applies the modifications of the observed event parameter — the first parameter of the
     * observer method — to the observer's qualifiers.
     *
     * @return whether they removed all its annotations, {@code @Observes} or {@code @ObservesAsync}
     *         included, which removes the observer once no qualifier is left
     */
    private static boolean applyObservedParameterConfig(VaubanMethodConfig methodConfig,
                                                        Set<QualifierInstance> qualifiers) {
        var parameterConfigs = methodConfig.getParameterConfigs();
        if (parameterConfigs.isEmpty() || !parameterConfigs.getFirst().isModified()) {
            return false;
        }
        var paramConfig = parameterConfigs.getFirst();
        boolean removed = paramConfig.isAllAnnotationsRemoved();
        if (removed) {
            qualifiers.clear();
        }
        removeMatching(qualifiers, paramConfig.getRemovePredicates());

        boolean hasExplicit = false;
        for (var annClass : paramConfig.getAddedAnnotationClasses()) {
            var qName = DotName.of(annClass.getName());
            qualifiers.add(new QualifierInstance(qName, Map.of()));
            hasExplicit |= isExplicitObserverQualifier(qName);
        }
        for (var annInfo : paramConfig.getAddedAnnotations()) {
            var qi = annotationInfoToQualifier(annInfo);
            qualifiers.add(qi);
            hasExplicit |= isExplicitObserverQualifier(qi.annotationName());
        }
        if (hasExplicit) {
            removeDefault(qualifiers);
        }
        return removed;
    }

    /** A qualifier that makes an observed parameter lose {@code @Default}: neither {@code @Any}, {@code @Named} nor {@code @Default}. */
    private static boolean isExplicitObserverQualifier(DotName name) {
        return !name.equals(QualifierInstance.ANY_NAME) && !name.equals(QualifierInstance.NAMED_NAME)
                && !name.equals(QualifierInstance.DEFAULT_NAME);
    }

    /** A qualifier that makes a bean class lose {@code @Default}: neither {@code @Any} nor {@code @Named}. */
    private static boolean isExplicitClassQualifier(DotName name) {
        return !name.equals(QualifierInstance.ANY_NAME) && !name.equals(QualifierInstance.NAMED_NAME);
    }

    private static void removeDefault(Set<QualifierInstance> qualifiers) {
        qualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
    }

    /** Removes every qualifier one of {@code predicates} matches, as the extension's {@code removeAnnotation} asked. */
    private static void removeMatching(Set<QualifierInstance> qualifiers, List<Predicate<AnnotationInfo>> predicates) {
        for (var predicate : predicates) {
            qualifiers.removeIf(q -> {
                var annInfo = qualifierToAnnotationInfo(q);
                return annInfo != null && predicate.test(annInfo);
            });
        }
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
                if (info.name().equals(QualifierInstance.NAMED_NAME)) {
                    var value = namedValue(info, config);
                    if (!value.isEmpty()) name = value;
                }
            }
        }
        return name;
    }

    /** The value of an added {@code @Named}, or the default name of the class when it has none. */
    private static String namedValue(io.vidocq.vauban.indexer.model.AnnotationInfo named, VaubanClassConfig config) {
        var value = named.member(VALUE) instanceof io.vidocq.vauban.indexer.model.AnnotationValue.StringVal(var text)
                ? text : "";
        if (value.isEmpty() && config.info() != null) {
            var simpleName = config.info().simpleName();
            value = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        }
        return value;
    }

    /** The collections of a bean descriptor an enhancement changes, copied so that they can be. */
    private record BeanEnhancement(Set<QualifierInstance> qualifiers, Set<DotName> interceptorBindings,
                                   List<Annotation> interceptorBindingAnnotations,
                                   List<InjectionPointInfo> injectionPoints, Set<String> enhancedInjectionMethods) {

        static BeanEnhancement of(BeanDescriptor bean) {
            return new BeanEnhancement(new LinkedHashSet<>(bean.qualifiers()),
                    new LinkedHashSet<>(bean.interceptorBindings()),
                    new ArrayList<>(bean.interceptorBindingAnnotations()),
                    new ArrayList<>(bean.injectionPoints()),
                    new LinkedHashSet<>(bean.enhancedInjectionMethods()));
        }
    }

    private static BeanDescriptor applyClassConfigs(BeanDescriptor bean, List<VaubanClassConfig> configs) {
        var enhanced = BeanEnhancement.of(bean);
        for (var config : configs) {
            applyClassAnnotations(config, enhanced);
            applyMemberConfigs(config, enhanced);
        }
        var qualifiers = enhanced.qualifiers();

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
                    Map.of(VALUE, new io.vidocq.vauban.indexer.model.AnnotationValue.StringVal(addedName))));
        }

        return new BeanDescriptor(
                bean.id(), bean.beanClass(), bean.kind(), bean.types(),
                qualifiers, bean.scope(), bean.isAlternative(), bean.priority(),
                enhanced.injectionPoints(), name, enhanced.interceptorBindings(),
                bean.constructorBindings(), enhanced.interceptorBindingAnnotations(),
                enhanced.enhancedInjectionMethods()
        );
    }

    /** The class-level removals and additions of one config: qualifiers and interceptor bindings. */
    private static void applyClassAnnotations(VaubanClassConfig config, BeanEnhancement enhanced) {
        removeMatching(enhanced.qualifiers(), config.getRemovePredicates());
        if (config.isAllAnnotationsRemoved()) {
            enhanced.qualifiers().clear();
            enhanced.interceptorBindings().clear();
            enhanced.interceptorBindingAnnotations().clear();
        }
        addClassQualifiers(config, enhanced.qualifiers());
        addClassInterceptorBindings(config, enhanced);
    }

    /**
     * Class-level annotation additions — only annotations that are actually qualifiers land in the
     * qualifier set. Scopes / stereotypes / bean-defining annotations are handled separately (index
     * rebuild + scope re-extraction) so they must not pollute qualifiers nor evict {@code @Default}.
     */
    private static void addClassQualifiers(VaubanClassConfig config, Set<QualifierInstance> qualifiers) {
        boolean classHasExplicit = false;
        for (var ann : config.getAddedAnnotations()) {
            if (isQualifierAnnotation(ann)) {
                qualifiers.add(qualifierOf(ann, config.getAddedAnnotationInstance(ann)));
                classHasExplicit |= isExplicitClassQualifier(DotName.of(ann.getName()));
            }
        }
        for (var annInfo : config.getAddedAnnotationInfos()) {
            if (isQualifierAnnotationInfo(annInfo)) {
                var qi = annotationInfoToQualifier(annInfo);
                qualifiers.add(qi);
                classHasExplicit |= isExplicitClassQualifier(qi.annotationName());
            }
        }
        if (classHasExplicit) {
            removeDefault(qualifiers);
        }
    }

    /**
     * Class-level {@code @InterceptorBinding} additions, propagated to the bean's interceptor
     * bindings so they take part in interceptor resolution. Without this, a BCE that uses
     * {@code ClassConfig.addAnnotation(SomeBinding.class)} to mark a bean class with a marker
     * interceptor binding (e.g. SmallRye's {@code @FaultToleranceBinding} pattern) is silently
     * ignored — the binding never reaches {@code BeanDescriptor.interceptorBindings()}, so
     * {@code InterceptorBeanWrapper} and {@code InterceptorManager} never wire the corresponding
     * {@code @Interceptor}.
     */
    private static void addClassInterceptorBindings(VaubanClassConfig config, BeanEnhancement enhanced) {
        for (var ann : config.getAddedAnnotations()) {
            if (isInterceptorBindingAnnotation(ann)) {
                enhanced.interceptorBindings().add(DotName.of(ann.getName()));
            }
        }
        addInterceptorBindingInfos(config.getAddedAnnotationInfos(), enhanced.interceptorBindings(),
                enhanced.interceptorBindingAnnotations());
    }

    /** Field-level modifications update injection points; method-level ones bindings and injection points too. */
    private static void applyMemberConfigs(VaubanClassConfig config, BeanEnhancement enhanced) {
        for (var fieldConfig : config.getFieldConfigs()) {
            if (fieldConfig.isModified()) {
                applyFieldEnhancement(fieldConfig, enhanced.injectionPoints());
            }
        }
        for (var methodConfig : config.getMethodConfigs()) {
            if (methodConfig.isModified()) {
                applyMethodConfig(methodConfig, enhanced);
            }
        }
    }

    private static void applyMethodConfig(VaubanMethodConfig methodConfig, BeanEnhancement enhanced) {
        applyMethodEnhancement(methodConfig, enhanced.interceptorBindings(), enhanced.interceptorBindingAnnotations());

        // An initializer method or a bean constructor: every parameter of either is an injection point (CDI 3.5.1,
        // 3.7.1), so an @Inject the enhancement adds makes them part of the bean's metadata (CDI 13.5.1).
        boolean injected = !methodConfig.info().isStatic()
                && hasAnnotation(methodConfig, jakarta.inject.Inject.class);
        boolean injectAdded = injected && hasAddedAnnotation(methodConfig, jakarta.inject.Inject.class);
        if (injectAdded && !methodConfig.info().isConstructor()) {
            enhanced.enhancedInjectionMethods().add(initializerMethodKey(methodConfig));
        }

        var parameterConfigs = methodConfig.getParameterConfigs();
        for (int i = 0; i < parameterConfigs.size(); i++) {
            var paramConfig = parameterConfigs.get(i);
            if (paramConfig.isModified() || injectAdded) {
                applyParameterEnhancement(methodConfig, paramConfig, i, injected, enhanced.injectionPoints());
            }
        }
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
                ? sourceQualifiers(fieldConfig.info().annotations())
                : new LinkedHashSet<>(existing.declaredQualifiers());
        if (fieldConfig.isAllAnnotationsRemoved()) declared.clear();
        removeMatching(declared, fieldConfig.getRemovePredicates());
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
        addInterceptorBindingInfos(methodConfig.getAddedAnnotationInfos(), interceptorBindings,
                interceptorBindingAnnotations);
    }

    /**
     * Adds the interceptor bindings among {@code infos} by name, and, for those an extension built,
     * their instance for the member-value comparison of interceptor resolution.
     */
    private static void addInterceptorBindingInfos(List<AnnotationInfo> infos, Set<DotName> interceptorBindings,
                                                   List<Annotation> interceptorBindingAnnotations) {
        for (var annInfo : infos) {
            if (isInterceptorBindingAnnotationInfo(annInfo)) {
                interceptorBindings.add(DotName.of(annInfo.name()));
                if (annInfo instanceof BuiltAnnotationInfo built) {
                    addBindingInstance(built, interceptorBindingAnnotations);
                }
            }
        }
    }

    private static void addBindingInstance(BuiltAnnotationInfo built, List<Annotation> interceptorBindingAnnotations) {
        try {
            var proxy = createAnnotationProxy(built);
            if (proxy != null) interceptorBindingAnnotations.add(proxy);
        } catch (Exception _) {
            // Best effort: the binding still takes part by its name, without its member values.
        }
    }

    private static void applyParameterEnhancement(VaubanMethodConfig methodConfig,
            VaubanParameterConfig parameterConfig, int index, boolean injected,
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
        if (existingIndex < 0 && !injected) return;

        var existing = existingIndex >= 0 ? injectionPoints.get(existingIndex) : null;
        var declared = existing == null
                ? sourceQualifiers(parameterConfig.info().annotations())
                : new LinkedHashSet<>(existing.declaredQualifiers());
        if (parameterConfig.isAllAnnotationsRemoved()) declared.clear();
        removeMatching(declared, parameterConfig.getRemovePredicates());
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

    private static LinkedHashSet<QualifierInstance> sourceQualifiers(
            java.util.Collection<AnnotationInfo> annotations) {
        var qualifiers = new LinkedHashSet<QualifierInstance>();
        annotations.stream().filter(EnhancementApplier::isQualifierAnnotationInfo)
                .map(EnhancementApplier::annotationInfoToQualifier).forEach(qualifiers::add);
        return qualifiers;
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
            @SuppressWarnings("unchecked")
            var clazz = (Class<? extends Annotation>) DeploymentClasses.load(annInfo.name());
            return isQualifierAnnotation(clazz);
        } catch (ClassNotFoundException _) {
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
            @SuppressWarnings("unchecked")
            var clazz = (Class<? extends Annotation>) DeploymentClasses.load(annInfo.name());
            return isInterceptorBindingAnnotation(clazz);
        } catch (ClassNotFoundException _) {
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
