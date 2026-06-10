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
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.Set;

/**
 * Target matching for the {@code @Enhancement} / {@code @Registration} type and
 * annotation filters: decides which classes, beans, observers and interceptors an
 * extension method applies to. Extracted from {@link BceProcessor} (which stays
 * the facade). Index-based variants ({@code *ByIndex}) work off the Vauban index;
 * the others resolve through a {@link ClassLoader}.
 */
final class BceTypeMatcher {

    private BceTypeMatcher() {
    }

    /** Index-based annotation matching: checks class, methods, fields, and constructors. */
    static boolean matchesAnnotationsByIndex(Set<DotName> annotationNames,
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
    static boolean matchesClassByIndex(Class<?>[] types, boolean withSubtypes,
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

    /** Check if a class matches Enhancement types filter. */
    static boolean matchesClass(Class<?>[] types, boolean withSubtypes, Class<?> targetClass) {
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
    static boolean matchesAnnotations(Class<? extends java.lang.annotation.Annotation>[] withAnnotations,
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
    static boolean matchesAnnotations(Class<? extends java.lang.annotation.Annotation>[] withAnnotations,
                                      Class<?> targetClass) {
        if (withAnnotations == null || withAnnotations.length == 0) return true;
        for (var ann : withAnnotations) {
            if (targetClass.isAnnotationPresent(ann)) return true;
            for (var m : BceProcessor.getDeclaredMethodsSafe(targetClass)) {
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

    static boolean matchesObserverTypes(Class<?>[] types, io.vidocq.vauban.core.bean.model.ObserverDescriptor observer, ClassLoader classLoader) {
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

    static boolean matchesInterceptorTypes(Class<?>[] types, io.vidocq.vauban.core.bean.model.InterceptorDescriptor interceptor, ClassLoader classLoader) {
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

    /**
     * Check if a bean matches any of the Registration/Enhancement types.
     * CDI spec: matches if the bean's set of bean types contains a type
     * that is assignable from at least one of the listed types.
     */
    static boolean matchesTypes(Class<?>[] types, BeanDescriptor bean, ClassLoader classLoader) {
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
}
