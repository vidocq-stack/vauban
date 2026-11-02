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
package io.vidocq.vauban.core.container;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the container reads off a class each time it creates a request-scoped or dependent bean and its
 * interceptors: the annotations of a class, of its methods and constructors, whether a type is an interceptor
 * binding or an interceptor, the generic type of a field, an interceptor's {@code @PostConstruct}. Every answer
 * depends on the declaration alone, so it is read once per class and kept (VAU-PERF-009), instead of by reflection at
 * each creation.
 *
 * <p>A {@link ClassValue} ties each answer to its class, so a class a hot reload replaces gets its own and the cache
 * never keeps an old one alive. The reflective reading itself is unchanged: it is what runs the first time, and the
 * only thing that runs when nothing else describes the class.
 *
 * <p>What an extension can change after a first reading, the interceptor binding types it declares
 * ({@link VaubanBeanManager#isCustomInterceptorBinding}), is not kept here: callers ask it each time.
 */
final class ClassMetadataCache {

    private static final ClassValue<List<Annotation>> CLASS_ANNOTATIONS = new ClassValue<>() {
        @Override
        protected List<Annotation> computeValue(Class<?> type) {
            return List.of(type.getAnnotations());
        }
    };

    private static final ClassValue<Boolean> DECLARED_BINDING_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class);
        }
    };

    private static final ClassValue<Boolean> DECLARED_QUALIFIERS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.isAnnotationPresent(jakarta.inject.Qualifier.class);
        }
    };

    private static final ClassValue<Boolean> INTERCEPTOR_CLASSES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.isAnnotationPresent(jakarta.interceptor.Interceptor.class);
        }
    };

    private static final ClassValue<List<Annotation>> METHOD_ANNOTATIONS = new ClassValue<>() {
        @Override
        protected List<Annotation> computeValue(Class<?> type) {
            var annotations = new ArrayList<Annotation>();
            for (var method : type.getDeclaredMethods()) {
                annotations.addAll(List.of(method.getAnnotations()));
            }
            return List.copyOf(annotations);
        }
    };

    private static final ClassValue<List<Constructor<?>>> CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected List<Constructor<?>> computeValue(Class<?> type) {
            return List.of(type.getDeclaredConstructors());
        }
    };

    private static final ClassValue<Map<Constructor<?>, List<Annotation>>> CONSTRUCTOR_ANNOTATIONS =
            new ClassValue<>() {
                @Override
                protected Map<Constructor<?>, List<Annotation>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private static final ClassValue<Map<Field, Type>> FIELD_TYPES = new ClassValue<>() {
        @Override
        protected Map<Field, Type> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static final ClassValue<Optional<Method>> INTERCEPTOR_POST_CONSTRUCTS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            for (var method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.annotation.PostConstruct.class)
                        && method.getParameterCount() == 0) {
                    return Optional.of(method);
                }
            }
            return Optional.empty();
        }
    };

    private ClassMetadataCache() {
    }

    /** The annotations of {@code type}, as {@code getAnnotations()} reports them. */
    static List<Annotation> annotationsOf(Class<?> type) {
        return CLASS_ANNOTATIONS.get(type);
    }

    /** Whether {@code annotationType} is declared an {@code @InterceptorBinding}; an extension may still make it one. */
    static boolean isDeclaredInterceptorBinding(Class<?> annotationType) {
        return DECLARED_BINDING_TYPES.get(annotationType);
    }

    /** Whether {@code annotationType} is an interceptor binding, declared as one or made one by an extension. */
    static boolean isInterceptorBinding(Class<?> annotationType) {
        return isDeclaredInterceptorBinding(annotationType)
                || VaubanBeanManager.isCustomInterceptorBinding(annotationType);
    }

    /** Whether {@code annotationType} is declared a {@code @Qualifier}; an extension may still make it one. */
    static boolean isDeclaredQualifier(Class<?> annotationType) {
        return DECLARED_QUALIFIERS.get(annotationType);
    }

    /** Whether {@code type} is annotated {@code @Interceptor}. */
    static boolean isInterceptorClass(Class<?> type) {
        return INTERCEPTOR_CLASSES.get(type);
    }

    /** The annotations of every method {@code type} declares, in declaration order. */
    static List<Annotation> methodAnnotationsOf(Class<?> type) {
        return METHOD_ANNOTATIONS.get(type);
    }

    /** The constructors {@code type} declares, as {@code getDeclaredConstructors()} reports them. */
    static List<Constructor<?>> constructorsOf(Class<?> type) {
        return CONSTRUCTORS.get(type);
    }

    /** The annotations of {@code constructor}. */
    static List<Annotation> annotationsOf(Constructor<?> constructor) {
        return CONSTRUCTOR_ANNOTATIONS.get(constructor.getDeclaringClass())
                .computeIfAbsent(constructor, c -> List.of(c.getAnnotations()));
    }

    /** The generic type of {@code field}. */
    static Type genericTypeOf(Field field) {
        return FIELD_TYPES.get(field.getDeclaringClass()).computeIfAbsent(field, Field::getGenericType);
    }

    /** The first {@code @PostConstruct} method without parameters that the interceptor class itself declares. */
    static Optional<Method> interceptorPostConstruct(Class<?> interceptorClass) {
        return INTERCEPTOR_POST_CONSTRUCTS.get(interceptorClass);
    }
}
