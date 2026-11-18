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

import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.types.TypeInfoUtils;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Target matching for the {@code @Enhancement} / {@code @Registration} type and
 * annotation filters: decides which classes, beans, observers and interceptors an
 * extension method applies to. Extracted from {@link BceProcessor} (which stays
 * the facade). Index-based variants ({@code *ByIndex}) work off the Vauban index; they, the
 * interceptor matcher and the observer matcher read the classes and interfaces above a class from
 * what {@link IndexLookup#describe} gives, and load only a class it does not describe; the others
 * resolve through a {@link ClassLoader}.
 */
final class BceTypeMatcher {

    /** The types every array has, besides the arrays of the types of its component (JLS 4.10.3). */
    private static final Set<Class<?>> ARRAY_SUPERTYPES =
            Set.of(Object.class, Cloneable.class, java.io.Serializable.class);

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

    /**
     * Whether {@code classInfo} is one of {@code types}, or, {@code withSubtypes}, has one of them among the classes and
     * interfaces above it, read as {@code isSubtypeOfAny} reads them. Reading only the superclasses the index holds and
     * the interfaces the class declares itself missed a type it has through a class it extends or an interface it
     * implements (BUG-20261117-07).
     */
    static boolean matchesClassByIndex(Class<?>[] types, boolean withSubtypes,
                                       io.vidocq.vauban.indexer.model.ClassInfo classInfo,
                                       IndexLookup lookup, ClassLoader classLoader) {
        if (withSubtypes) {
            return isSubtypeOfAny(types, classInfo.name(), lookup, classLoader);
        }
        for (var type : types) {
            if (type == Object.class || classInfo.name().value().equals(type.getName())) return true;
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

    /**
     * Whether the event type an observer observes is assignable to one of {@code types}, and not the class that
     * declares it, which the container matched (BUG-20261117-05). See {@code isAssignableToAny} for what is assignable,
     * and {@code asPrimitives} for a primitive the container reads by reflection.
     */
    static boolean matchesObserverTypes(Class<?>[] types,
                                        io.vidocq.vauban.core.bean.model.ObserverDescriptor observer,
                                        IndexLookup lookup, ClassLoader classLoader) {
        return isAssignableToAny(types, asPrimitives(observer.eventType()), lookup, classLoader);
    }

    /**
     * {@code type}, with a class type named after a primitive, {@code int} say, read as that primitive type, the
     * component of an array included. The container reads the event type of an observer it loads by reflection so,
     * where the processor reads the primitive type: both then match alike. The names are the JDK's
     * ({@link Class#forPrimitiveName}), as the language model's {@code TypeMapper} reads them.
     */
    private static TypeInfo asPrimitives(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType(var name) when TypeInfoUtils.fromReflectType(Class.forPrimitiveName(name.value()))
                    instanceof TypeInfo.PrimitiveType primitive -> primitive;
            case TypeInfo.ArrayType(var component, var dimensions) ->
                    new TypeInfo.ArrayType(asPrimitives(component), dimensions);
            default -> type;
        };
    }

    /**
     * Whether {@code type} is assignable to one of {@code types}, which name raw classes: a class or a parameterized
     * type when its class is one of them or has one among the classes and interfaces above it, read as
     * {@code isSubtypeOfAny} reads them; a primitive type as its wrapper class; an array as
     * {@code isArrayAssignableToAny} says; a type variable when one of its bounds is assignable, and one without a
     * bound to {@code Object} only. Any other type, a wildcard say, is assignable to {@code Object} only.
     */
    private static boolean isAssignableToAny(Class<?>[] types, TypeInfo type, IndexLookup lookup,
                                             ClassLoader classLoader) {
        return switch (type) {
            case TypeInfo.ClassType(var name) -> isSubtypeOfAny(types, name, lookup, classLoader);
            case TypeInfo.ParameterizedType(var rawType, _, _) -> isSubtypeOfAny(types, rawType, lookup, classLoader);
            case TypeInfo.PrimitiveType(var kind) -> isSubtypeOfAny(types, DotName.of(wrapperOf(kind).getName()),
                    lookup, classLoader);
            case TypeInfo.ArrayType(var component, var dimensions) ->
                    isArrayAssignableToAny(types, component, dimensions, lookup, classLoader);
            case TypeInfo.TypeVariable(_, var bounds) when !bounds.isEmpty() ->
                    bounds.stream().anyMatch(bound -> isAssignableToAny(types, bound, lookup, classLoader));
            default -> List.of(types).contains(Object.class);
        };
    }

    /**
     * Whether an array of {@code component} with {@code dimensions} dimensions is assignable to one of {@code types}
     * (JLS 4.10.3): every array is to {@code Object}, {@code Cloneable} and {@code Serializable}, and it is to an array
     * of one dimension less of a type it is assignable to.
     */
    private static boolean isArrayAssignableToAny(Class<?>[] types, TypeInfo component, int dimensions,
                                                  IndexLookup lookup, ClassLoader classLoader) {
        for (var type : types) {
            if (ARRAY_SUPERTYPES.contains(type)
                    || (type.isArray() && isArrayAssignableTo(type.componentType(), component, dimensions, lookup,
                    classLoader))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an array of {@code component} with {@code dimensions} dimensions is assignable to an array of
     * {@code expected}: an array of primitives to an array of the same primitives only.
     */
    private static boolean isArrayAssignableTo(Class<?> expected, TypeInfo component, int dimensions,
                                               IndexLookup lookup, ClassLoader classLoader) {
        if (dimensions > 1) {
            return isArrayAssignableToAny(new Class<?>[] {expected}, component, dimensions - 1, lookup, classLoader);
        }
        if (component instanceof TypeInfo.PrimitiveType(var kind)) {
            return expected == primitiveOf(kind);
        }
        return !expected.isPrimitive() && isAssignableToAny(new Class<?>[] {expected}, component, lookup, classLoader);
    }

    private static Class<?> primitiveOf(TypeInfo.PrimitiveType.Kind kind) {
        return Class.forPrimitiveName(kind.name().toLowerCase(Locale.ROOT));
    }

    private static Class<?> wrapperOf(TypeInfo.PrimitiveType.Kind kind) {
        return MethodType.methodType(primitiveOf(kind)).wrap().returnType();
    }

    /**
     * Whether the bean types of an interceptor, its class and every class and interface above it, hold one of
     * {@code types}, read as {@code isSubtypeOfAny} reads them. Matching it by loading the interceptor's class matched
     * none the compilation compiles (BUG-20261117-02).
     */
    static boolean matchesInterceptorTypes(Class<?>[] types,
                                           io.vidocq.vauban.core.bean.model.InterceptorDescriptor interceptor,
                                           IndexLookup lookup, ClassLoader classLoader) {
        return isSubtypeOfAny(types, interceptor.interceptorClass(), lookup, classLoader);
    }

    /**
     * Whether {@code className}, or a class or an interface above it, is one of {@code types}. The hierarchy is read as
     * {@link IndexLookup#describe} gives it: the index, and, while Vauban's annotation processor runs the extensions,
     * the processor's own model of a class the index does not hold. A class the compilation compiles does not load
     * through the processor's class loader. A class nothing describes, one of the JDK say, is loaded.
     */
    private static boolean isSubtypeOfAny(Class<?>[] types, DotName className, IndexLookup lookup,
                                          ClassLoader classLoader) {
        var names = new HashSet<DotName>();
        for (var type : types) {
            if (type == Object.class) return true;
            names.add(DotName.of(type.getName()));
        }
        var pending = new ArrayDeque<DotName>();
        var reached = new HashSet<DotName>();
        pending.add(className);
        reached.add(className);
        while (!pending.isEmpty()) {
            var name = pending.poll();
            var described = lookup.describe(name);
            if (names.contains(name)
                    || (described.isEmpty() && loadsAsSubtypeOfAny(types, name.value(), classLoader))) {
                return true;
            }
            described.ifPresent(classInfo -> supertypesOf(classInfo).filter(reached::add).forEach(pending::add));
        }
        return false;
    }

    /** The class {@code classInfo} extends, if any, and the interfaces it implements. */
    private static Stream<DotName> supertypesOf(io.vidocq.vauban.indexer.model.ClassInfo classInfo) {
        return Stream.concat(Stream.ofNullable(classInfo.superName()), classInfo.interfaces().stream());
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

    /**
     * Whether a bean of a {@code @Registration} method matches: its bean types contain one of {@code types}. A class or
     * parameterized type matches the class it names, by name, and no supertype: the bean types already hold those the
     * bean has, and a {@code @Typed} bean has no other. Loading the class and asking whether it is a subtype gave a
     * {@code @Typed} bean for the supertypes of its types at start (BUG-20261118-43). The bean
     * types of a producer of a primitive or an array type are that type and {@code Object} (CDI 4.1, 2.2.1): a
     * primitive type, or a class type named after one as the container reads it by reflection, matches its primitive
     * class and its wrapper, an array its own array class; {@code Object} is matched as a bean type of its own. A
     * producer of {@code int} or {@code String[]} matched no type but {@code Object} (BUG-20261117-11).
     */
    static boolean matchesRegistrationTypes(Class<?>[] types, BeanDescriptor bean) {
        for (var beanType : bean.types()) {
            boolean matches = switch (asPrimitives(beanType)) {
                case TypeInfo.PrimitiveType(var kind) -> List.of(types).contains(primitiveOf(kind))
                        || List.of(types).contains(wrapperOf(kind));
                case TypeInfo.ArrayType array -> {
                    var name = BeanDiscovery.erasedName(array);
                    yield Stream.of(types).anyMatch(type -> type.isArray() && type.getTypeName().equals(name));
                }
                default -> rawTypeName(beanType)
                        .filter(name -> Stream.of(types).anyMatch(type -> type.getName().equals(name))).isPresent();
            };
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /** The class a bean type names, or empty for a type that names none (a type variable, an array…). */
    private static Optional<String> rawTypeName(TypeInfo beanType) {
        return switch (beanType) {
            case TypeInfo.ClassType(DotName(var name)) -> Optional.of(name);
            case TypeInfo.ParameterizedType(DotName(var rawName), _, _) -> Optional.of(rawName);
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
