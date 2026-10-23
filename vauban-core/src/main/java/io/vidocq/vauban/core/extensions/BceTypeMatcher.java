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
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.Optional;
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
            if (typeName.equals(classInfo.name())
                    || (withSubtypes && isSubtypeByIndex(typeName, classInfo, lookup))) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code typeName} is a superclass of {@code classInfo}, as far as the index knows, or one of its interfaces. */
    private static boolean isSubtypeByIndex(DotName typeName, io.vidocq.vauban.indexer.model.ClassInfo classInfo,
                                            IndexLookup lookup) {
        var superName = classInfo.superName();
        while (superName != null) {
            if (typeName.equals(superName)) return true;
            superName = lookup.getClass(superName)
                    .map(io.vidocq.vauban.indexer.model.ClassInfo::superName)
                    .orElse(null);
        }
        return classInfo.interfaces().contains(typeName);
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
        } catch (ClassNotFoundException _) {
            return false;
        }
    }

    /** Check if a class has at least one of the required annotations (on class, methods, fields, or constructors). */
    static boolean matchesAnnotations(Class<? extends java.lang.annotation.Annotation>[] withAnnotations,
                                      Class<?> targetClass) {
        if (withAnnotations == null || withAnnotations.length == 0) return true;
        for (var ann : withAnnotations) {
            if (targetClass.isAnnotationPresent(ann)
                    || anyAnnotated(BceProcessor.getDeclaredMethodsSafe(targetClass), ann)
                    || anyFieldAnnotated(targetClass, ann)
                    || anyConstructorAnnotated(targetClass, ann)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a field of {@code targetClass} carries {@code ann}. Defensive: reading the fields, or the
     * constructors below, throws {@link NoClassDefFoundError} when a signature names an optional type
     * absent at run time; such a class then matches through its other members only.
     */
    private static boolean anyFieldAnnotated(Class<?> targetClass, Class<? extends java.lang.annotation.Annotation> ann) {
        try {
            return anyAnnotated(targetClass.getDeclaredFields(), ann);
        } catch (LinkageError _) {
            return false;
        }
    }

    private static boolean anyConstructorAnnotated(Class<?> targetClass,
                                                   Class<? extends java.lang.annotation.Annotation> ann) {
        try {
            return anyAnnotated(targetClass.getDeclaredConstructors(), ann);
        } catch (LinkageError _) {
            return false;
        }
    }

    private static boolean anyAnnotated(java.lang.reflect.AnnotatedElement[] elements,
                                        Class<? extends java.lang.annotation.Annotation> ann) {
        for (var element : elements) {
            if (element.isAnnotationPresent(ann)) return true;
        }
        return false;
    }

    static boolean matchesObserverTypes(Class<?>[] types, io.vidocq.vauban.core.bean.model.ObserverDescriptor observer, ClassLoader classLoader) {
        return loadsAsSubtypeOfAny(types, observer.declaringClass().value(), classLoader);
    }

    static boolean matchesInterceptorTypes(Class<?>[] types, io.vidocq.vauban.core.bean.model.InterceptorDescriptor interceptor, ClassLoader classLoader) {
        return loadsAsSubtypeOfAny(types, interceptor.interceptorClass().value(), classLoader);
    }

    /**
     * Check if a bean matches any of the Registration/Enhancement types.
     * CDI spec: matches if the bean's set of bean types contains a type
     * that is assignable from at least one of the listed types.
     */
    static boolean matchesTypes(Class<?>[] types, BeanDescriptor bean, ClassLoader classLoader) {
        for (var beanTypeInfo : bean.types()) {
            var beanTypeName = rawTypeName(beanTypeInfo);
            if (beanTypeName.isPresent() && loadsAsSubtypeOfAny(types, beanTypeName.get(), classLoader)) {
                return true;
            }
        }
        return false;
    }

    /** The class a bean type names, or empty for a type that names none (a type variable, an array…). */
    private static Optional<String> rawTypeName(TypeInfo beanType) {
        return switch (beanType) {
            case TypeInfo.ClassType(DotName(var name)) -> Optional.of(name);
            case TypeInfo.ParameterizedType(DotName(var rawName), _) -> Optional.of(rawName);
            default -> Optional.empty();
        };
    }

    /**
     * Whether the class {@code className}, loaded through {@code classLoader}, is a subtype of one of
     * {@code types}; {@code false} when the loader cannot find it.
     */
    private static boolean loadsAsSubtypeOfAny(Class<?>[] types, String className, ClassLoader classLoader) {
        try {
            var loaded = classLoader.loadClass(className);
            for (var type : types) {
                if (type.isAssignableFrom(loaded)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException _) {
            // Not visible through this loader: it matches nothing.
        }
        return false;
    }
}
