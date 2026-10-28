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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.bean.model.DisposerDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.loading.DeploymentClasses;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Observer-method and disposer-method discovery, extracted from {@link BeanDiscovery}
 * (which stays the single facade — its {@code discoverObservers()} /
 * {@code discoverDisposerMethods()} delegate here). Shares the host's index, filters
 * (vetoed / bean-defining / disabled-alternative) and qualifier computation.
 */
final class ObserverDisposerDiscovery {

    private static final String DEFAULT_RECEPTION = "ALWAYS";
    private static final String DEFAULT_TRANSACTION_PHASE = "IN_PROGRESS";

    private final BeanDiscovery host;

    ObserverDisposerDiscovery(BeanDiscovery host) {
        this.host = host;
    }

    List<ObserverDescriptor> discoverObservers() {
        var result = new ArrayList<ObserverDescriptor>();

        for (var classInfo : host.index.getKnownClasses()) {
            if (isObserverBeanClass(classInfo)) {
                // Check declared methods in the index
                discoverObserversFromMethods(classInfo, classInfo.methods(), result);
                // Check inherited methods via reflection (not in bytecode index)
                discoverInheritedObservers(classInfo, result);
            }
        }

        return List.copyOf(result);
    }

    /**
     * A class whose observer methods count: not vetoed, a bean, and not a disabled alternative
     * (CDI spec: observer methods of disabled beans are NOT registered).
     */
    private boolean isObserverBeanClass(ClassInfo classInfo) {
        return !host.isVetoed(classInfo)
                && host.hasBeanDefiningAnnotation(classInfo)
                && !host.isDisabledAlternative(classInfo);
    }

    private void discoverInheritedObservers(ClassInfo classInfo, List<ObserverDescriptor> result) {
        try {
            var clazz = DeploymentClasses.load(classInfo.name().value());
            for (var method : getAllInheritedMethods(clazz)) {
                int observed = observedParameterIndex(method);
                if (observed >= 0) {
                    result.add(inheritedObserver(classInfo, clazz, method, observed));
                }
            }
        } catch (ClassNotFoundException _) {
            // The index alone describes the observers
        }
    }

    /** The position of the first {@code @Observes} or {@code @ObservesAsync} parameter, or -1. */
    private static int observedParameterIndex(java.lang.reflect.Method method) {
        var parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                    || parameters[i].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                return i;
            }
        }
        return -1;
    }

    private ObserverDescriptor inheritedObserver(ClassInfo classInfo, Class<?> clazz,
            java.lang.reflect.Method method, int paramIndex) {
        var param = method.getParameters()[paramIndex];
        boolean async = param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class);

        var reception = DEFAULT_RECEPTION;
        var transactionPhase = DEFAULT_TRANSACTION_PHASE;
        var observesAnn = param.getAnnotation(jakarta.enterprise.event.Observes.class);
        if (observesAnn != null) {
            reception = observesAnn.notifyObserver().name();
            transactionPhase = observesAnn.during().name();
        }

        var priority = jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY;
        if (method.isAnnotationPresent(jakarta.annotation.Priority.class)) {
            priority = method.getAnnotation(jakarta.annotation.Priority.class).value();
        }

        // Resolve generic type with type variable substitution from the concrete bean class
        var genericParamType = method.getGenericParameterTypes()[paramIndex];
        var resolvedReflectType = BeanDiscovery.resolveReflectType(genericParamType, clazz);
        var eventType = BeanDiscovery.reflectTypeToTypeInfo(resolvedReflectType);
        if (eventType == null) eventType = new TypeInfo.ClassType(DotName.of(param.getType().getName()));
        return new ObserverDescriptor(
                classInfo.name(), method.getName(), eventType,
                reflectedQualifiers(param), async, priority, reception, transactionPhase);
    }

    /** The qualifiers of an observed parameter, {@code @Observes} and {@code @ObservesAsync} left out. */
    private List<QualifierInstance> reflectedQualifiers(java.lang.reflect.Parameter param) {
        var qualifiers = new ArrayList<QualifierInstance>();
        for (var ann : param.getAnnotations()) {
            var type = ann.annotationType();
            if (type != jakarta.enterprise.event.Observes.class
                    && type != jakarta.enterprise.event.ObservesAsync.class
                    && host.isQualifierAnnotation(DotName.of(type.getName()))) {
                qualifiers.add(QualifierInstance.from(new AnnotationInfo(DotName.of(type.getName()), Map.of())));
            }
        }
        return qualifiers;
    }

    private static List<java.lang.reflect.Method> getAllInheritedMethods(Class<?> clazz) {
        var result = new ArrayList<java.lang.reflect.Method>();
        var seen = new java.util.HashSet<String>();
        // Record methods declared in the concrete class to skip overridden inherited methods
        for (var m : clazz.getDeclaredMethods()) {
            seen.add(m.getName() + ":" + java.util.Arrays.toString(m.getParameterTypes()));
        }
        // Walk superclass hierarchy for inherited methods
        var current = clazz.getSuperclass();
        while (current != null && current != Object.class) {
            for (var m : current.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPrivate(m.getModifiers())) continue;
                var sig = m.getName() + ":" + java.util.Arrays.toString(m.getParameterTypes());
                if (seen.add(sig)) {
                    result.add(m);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private void discoverObserversFromMethods(ClassInfo classInfo, List<MethodInfo> methods,
            List<ObserverDescriptor> result) {
        // CDI 4.1: static observer methods are supported
        for (var method : methods) {
            int observed = method.isConstructor() ? -1 : observedParameterIndex(method);
            if (observed >= 0) {
                // only one observed parameter per method
                result.add(indexedObserver(classInfo, method, observed));
            }
        }
    }

    /** The position of the first {@code @Observes} or {@code @ObservesAsync} parameter, or -1. */
    private static int observedParameterIndex(MethodInfo method) {
        var parameters = method.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            var annotations = parameters.get(i).annotations();
            if (BeanDiscovery.hasAnnotation(annotations, BeanDiscovery.OBSERVES)
                    || BeanDiscovery.hasAnnotation(annotations, BeanDiscovery.OBSERVES_ASYNC)) {
                return i;
            }
        }
        return -1;
    }

    private ObserverDescriptor indexedObserver(ClassInfo classInfo, MethodInfo method, int paramIndex) {
        var param = method.parameters().get(paramIndex);
        boolean isObservesAsync = BeanDiscovery.hasAnnotation(param.annotations(), BeanDiscovery.OBSERVES_ASYNC);
        // Qualifiers on the observed parameter (excluding @Observes/@ObservesAsync)
        var qualifiers = host.computeObserverQualifiers(param.annotations().stream()
                .filter(a -> !isObservesAnnotation(a))
                .toList());
        // CDI spec: @Priority on observer is on the @Observes parameter, not the method
        var priority = host.extractPriority(param.annotations());
        if (priority == 0) priority = host.extractPriority(method.annotations());
        if (priority == 0) priority = jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY;

        // Extract reception and transactionPhase from @Observes annotation
        var reception = DEFAULT_RECEPTION;
        var transactionPhase = DEFAULT_TRANSACTION_PHASE;
        var observesAnn = param.annotations().stream()
                .filter(ObserverDisposerDiscovery::isObservesAnnotation)
                .findFirst();
        if (observesAnn.isPresent()) {
            reception = enumConstant(observesAnn.get(), "notifyObserver", reception);
            transactionPhase = enumConstant(observesAnn.get(), "during", transactionPhase);
        }

        // Try to get the parameterized type via reflection
        var eventType = resolveObserverParamType(
                classInfo.name().value(), method.name(), paramIndex, method.parameters(), param.type());

        return new ObserverDescriptor(
                classInfo.name(),
                method.name(),
                eventType,
                List.copyOf(qualifiers),
                isObservesAsync,
                priority,
                reception,
                transactionPhase
        );
    }

    private static boolean isObservesAnnotation(AnnotationInfo annotation) {
        return annotation.name().equals(BeanDiscovery.OBSERVES)
                || annotation.name().equals(BeanDiscovery.OBSERVES_ASYNC);
    }

    /** The constant an enum member of the annotation names, or {@code otherwise} when it names none. */
    private static String enumConstant(AnnotationInfo annotation, String member, String otherwise) {
        return annotation.member(member) instanceof io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal ev
                ? ev.constantName()
                : otherwise;
    }

    private static String getBaseTypeName(TypeInfo t) {
        return switch (t) {
            case io.vidocq.vauban.indexer.model.TypeInfo.ClassType(var name) -> name.value();
            case io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt -> pt.rawType().value();
            case io.vidocq.vauban.indexer.model.TypeInfo.ArrayType at -> getBaseTypeName(at.componentType()) + "[]";
            case null, default -> "";
        };
    }

    /**
     * Resolve the observer parameter type to a ParameterizedType via reflection if possible.
     */
    private TypeInfo resolveObserverParamType(String className, String methodName, int paramIndex, java.util.List<io.vidocq.vauban.indexer.model.ParameterInfo> params, TypeInfo fallback) {
        try {
            var clazz = DeploymentClasses.load(className);
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == params.size()
                        && parameterTypesMatch(m, params)) {
                    var resolved = genericParameterType(m, paramIndex);
                    if (resolved != null) return resolved;
                }
            }
        } catch (Exception _) { /* fallback */ }
        return fallback;
    }

    /** Whether each parameter type of {@code m} matches the indexed one, by name or simple name. */
    private static boolean parameterTypesMatch(java.lang.reflect.Method m,
            List<io.vidocq.vauban.indexer.model.ParameterInfo> params) {
        var parameterTypes = m.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            var pClass = parameterTypes[i];
            var pTypeName = pClass.isArray() ? pClass.getName() : pClass.getName().replace('$', '.');
            String infoTypeName = getBaseTypeName(params.get(i).type());
            // Simple name check since rawName() might differ slightly for nested classes
            if (!infoTypeName.isEmpty() && !pTypeName.equals(infoTypeName) && !pClass.getSimpleName().equals(infoTypeName.substring(infoTypeName.lastIndexOf('.') + 1))) {
                return false;
            }
        }
        return true;
    }

    private static TypeInfo genericParameterType(java.lang.reflect.Method m, int paramIndex) {
        var genericParamTypes = m.getGenericParameterTypes();
        return paramIndex < genericParamTypes.length
                ? BeanDiscovery.reflectTypeToTypeInfo(genericParamTypes[paramIndex])
                : null;
    }

    /**
     * Every parameter of a disposer other than the {@code @Disposes} one, as an injection point.
     * CDI 4.1 §10.4.3 makes them injection points like any other, so they are described the same way
     * — and found again by the same description — as a producer's or an initializer's (vauban#89).
     */
    private List<InjectionPointInfo> otherParameters(ClassInfo classInfo, MethodInfo method,
            int disposedIndex) {
        var points = new ArrayList<InjectionPointInfo>();
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i == disposedIndex) continue;
            var param = method.parameters().get(i);
            points.add(new InjectionPointInfo(
                    param.type(),
                    host.computeInjectionPointQualifiers(param.annotations()),
                    host.declaredInjectionPointQualifiers(param.annotations()),
                    InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                    InjectionPointInfo.parameterDescription(classInfo.name().simpleName(), method.name(), i,
                            method.parameters().stream().map(ParameterInfo::type).toList())));
        }
        return points;
    }

    /**
     * Discovers all disposer methods in the index.
     * A disposer method has exactly one parameter annotated with {@code @Disposes}.
     */
    List<DisposerDescriptor> discoverDisposerMethods() {
        var disposers = new ArrayList<DisposerDescriptor>();

        for (var classInfo : host.index.getKnownClasses()) {
            if (!host.isVetoed(classInfo) && host.hasBeanDefiningAnnotation(classInfo)) {
                addDisposers(classInfo, disposers);
            }
        }

        return List.copyOf(disposers);
    }

    private void addDisposers(ClassInfo classInfo, List<DisposerDescriptor> disposers) {
        // CDI spec: disposer methods can be static
        for (var method : classInfo.methods()) {
            int disposed = method.isConstructor() ? -1 : disposedParameterIndex(method);
            if (disposed >= 0) {
                // only one @Disposes per method
                var param = method.parameters().get(disposed);
                // Qualifiers on the disposed parameter (excluding @Disposes)
                var qualifiers = host.computeQualifiers(param.annotations().stream()
                        .filter(a -> !a.name().equals(BeanDiscovery.DISPOSES))
                        .toList());
                disposers.add(new DisposerDescriptor(
                        classInfo.name(), method.name(), param.type(),
                        qualifiers, disposed, otherParameters(classInfo, method, disposed)));
            }
        }
    }

    /** The position of the first {@code @Disposes} parameter, or -1. */
    private static int disposedParameterIndex(MethodInfo method) {
        var parameters = method.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (BeanDiscovery.hasAnnotation(parameters.get(i).annotations(), BeanDiscovery.DISPOSES)) {
                return i;
            }
        }
        return -1;
    }
}
