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
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
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

    private final BeanDiscovery host;

    ObserverDisposerDiscovery(BeanDiscovery host) {
        this.host = host;
    }

    List<ObserverDescriptor> discoverObservers() {
        var result = new ArrayList<ObserverDescriptor>();

        for (var classInfo : host.index.getKnownClasses()) {
            if (host.isVetoed(classInfo)) continue;
            if (!host.hasBeanDefiningAnnotation(classInfo)) continue;
            // CDI spec: observer methods of disabled beans are NOT registered
            if (host.isDisabledAlternative(classInfo)) continue;

            // Check declared methods in the index
            discoverObserversFromMethods(classInfo, classInfo.methods(), result);

            // Check inherited methods via reflection (not in bytecode index)
            try {
                var clazz = Class.forName(classInfo.name().value());
                for (var method : getAllInheritedMethods(clazz)) {
                    for (var param : method.getParameters()) {
                        if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                            boolean async = param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class);

                            var reception = "ALWAYS";
                            var transactionPhase = "IN_PROGRESS";
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
                            var paramIndex = java.util.List.of(method.getParameters()).indexOf(param);
                            var genericParamType = method.getGenericParameterTypes()[paramIndex];
                            var resolvedReflectType = BeanDiscovery.resolveReflectType(genericParamType, clazz);
                            var eventType = BeanDiscovery.reflectTypeToTypeInfo(resolvedReflectType);
                            if (eventType == null) eventType = new TypeInfo.ClassType(DotName.of(param.getType().getName()));
                            // Collect qualifier annotations from the parameter
                            var qualifiers = new ArrayList<QualifierInstance>();
                            for (var ann : param.getAnnotations()) {
                                if (ann.annotationType() == jakarta.enterprise.event.Observes.class
                                        || ann.annotationType() == jakarta.enterprise.event.ObservesAsync.class)
                                    continue;
                                if (host.isQualifierAnnotation(DotName.of(ann.annotationType().getName()))) {
                                    qualifiers.add(QualifierInstance.from(
                                            new AnnotationInfo(DotName.of(ann.annotationType().getName()), Map.of())));
                                }
                            }
                            result.add(new ObserverDescriptor(
                                    classInfo.name(), method.getName(), eventType,
                                    qualifiers, async, priority, reception, transactionPhase));
                            break;
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                // skip
            }
        }

        return List.copyOf(result);
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
        for (var method : methods) {
            if (method.isConstructor()) continue;
            // CDI 4.1: static observer methods are supported

            for (var param : method.parameters()) {
                boolean isObserves = BeanDiscovery.hasAnnotation(param.annotations(), BeanDiscovery.OBSERVES);
                boolean isObservesAsync = BeanDiscovery.hasAnnotation(param.annotations(), BeanDiscovery.OBSERVES_ASYNC);

                if (isObserves || isObservesAsync) {
                        // Qualifiers on the observed parameter (excluding @Observes/@ObservesAsync)
                        var qualifiers = host.computeObserverQualifiers(param.annotations().stream()
                                .filter(a -> !a.name().equals(BeanDiscovery.OBSERVES)
                                        && !a.name().equals(BeanDiscovery.OBSERVES_ASYNC))
                                .toList());
                        // CDI spec: @Priority on observer is on the @Observes parameter, not the method
                        var priority = host.extractPriority(param.annotations());
                        if (priority == 0) priority = host.extractPriority(method.annotations());
                        if (priority == 0) priority = jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY;

                        // Extract reception and transactionPhase from @Observes annotation
                        var reception = "ALWAYS";
                        var transactionPhase = "IN_PROGRESS";
                        var observesAnn = param.annotations().stream()
                                .filter(a -> a.name().equals(BeanDiscovery.OBSERVES)
                                        || a.name().equals(BeanDiscovery.OBSERVES_ASYNC))
                                .findFirst();
                        if (observesAnn.isPresent()) {
                            var recVal = observesAnn.get().member("notifyObserver");
                            if (recVal instanceof io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal ev) {
                                reception = ev.constantName();
                            }
                            var txVal = observesAnn.get().member("during");
                            if (txVal instanceof io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal ev) {
                                transactionPhase = ev.constantName();
                            }
                        }

                        // Try to get the parameterized type via reflection
                        var eventType = resolveObserverParamType(
                                classInfo.name().value(), method.name(),
                                method.parameters().indexOf(param), method.parameters(), param.type());

                        result.add(new ObserverDescriptor(
                                classInfo.name(),
                                method.name(),
                                eventType,
                                List.copyOf(qualifiers),
                                isObservesAsync,
                                priority,
                                reception,
                                transactionPhase
                        ));
                        break; // only one observed parameter per method
                }
            }
        }
    }

    private String getBaseTypeName(TypeInfo t) {
        return switch (t) {
            case io.vidocq.vauban.indexer.model.TypeInfo.ClassType ct -> ct.name().value();
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
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(className, false, cl)
                    : Class.forName(className);
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == params.size()) {
                    boolean match = true;
                    for (int i = 0; i < m.getParameterTypes().length; i++) {
                        var pClass = m.getParameterTypes()[i];
                        var pTypeName = pClass.isArray() ? pClass.getName() : pClass.getName().replace('$', '.');
                        String infoTypeName = getBaseTypeName(params.get(i).type());
                        // Simple name check since rawName() might differ slightly for nested classes
                        if (!infoTypeName.isEmpty() && !pTypeName.equals(infoTypeName) && !pClass.getSimpleName().equals(infoTypeName.substring(infoTypeName.lastIndexOf('.') + 1))) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        var genericParamTypes = m.getGenericParameterTypes();
                        if (paramIndex < genericParamTypes.length) {
                            var genericType = genericParamTypes[paramIndex];
                            var resolved = BeanDiscovery.reflectTypeToTypeInfo(genericType);
                            if (resolved != null) return resolved;
                        }
                    }
                }
            }
        } catch (Exception e) { /* fallback */ }
        return fallback;
    }

    /**
     * Discovers all disposer methods in the index.
     * A disposer method has exactly one parameter annotated with {@code @Disposes}.
     */
    @SuppressWarnings("java:S135")
    List<DisposerDescriptor> discoverDisposerMethods() {
        var disposers = new ArrayList<DisposerDescriptor>();

        for (var classInfo : host.index.getKnownClasses()) {
            if (host.isVetoed(classInfo)) continue;
            if (!host.hasBeanDefiningAnnotation(classInfo)) continue;

            for (var method : classInfo.methods()) {
                if (method.isConstructor()) continue;
                // CDI spec: disposer methods can be static

                for (int i = 0; i < method.parameters().size(); i++) {
                    var param = method.parameters().get(i);
                    if (BeanDiscovery.hasAnnotation(param.annotations(), BeanDiscovery.DISPOSES)) {
                        // Qualifiers on the disposed parameter (excluding @Disposes)
                        var qualifiers = host.computeQualifiers(param.annotations().stream()
                                .filter(a -> !a.name().equals(BeanDiscovery.DISPOSES))
                                .toList());
                        disposers.add(new DisposerDescriptor(
                                classInfo.name(), method.name(), param.type(),
                                qualifiers, i));
                        break; // only one @Disposes per method
                    }
                }
            }
        }

        return List.copyOf(disposers);
    }
}
