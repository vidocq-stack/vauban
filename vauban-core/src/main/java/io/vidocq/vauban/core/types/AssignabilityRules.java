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
package io.vidocq.vauban.core.types;

import io.vidocq.vauban.core.loading.DeploymentClasses;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.model.TypeInfo.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * CDI 4.1 type assignability rules (Section 2.4).
 * <p>
 * Determines if a bean type is assignable to a required type
 * at an injection point, following CDI spec rules for
 * raw types, parameterized types, and wildcards.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class AssignabilityRules {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    private final VaubanIndex index;
    /** Where a class the index does not hold is read from, or {@code null} for the caller's context loader. */
    private final ClassLoader loader;

    /** The rules over {@code index}, a class outside it read through the caller's context loader. */
    public AssignabilityRules(VaubanIndex index) {
        this(index, null);
    }

    /**
     * The rules over {@code index}, a class outside it read through {@code loader}: a container's, whose
     * {@code BeanManager.getBeans} runs on threads whose context loader may not see the deployment
     * (BUG-20261117-16).
     */
    public AssignabilityRules(VaubanIndex index, ClassLoader loader) {
        this.index = Objects.requireNonNull(index);
        this.loader = loader;
    }

    /**
     * Checks if {@code beanType} is assignable to {@code requiredType}
     * following CDI assignability rules.
     */
    private static final java.util.Map<String, DotName> PRIMITIVE_TO_WRAPPER = java.util.Map.of(
            "BOOLEAN", DotName.of("java.lang.Boolean"),
            "BYTE", DotName.of("java.lang.Byte"),
            "CHAR", DotName.of("java.lang.Character"),
            "SHORT", DotName.of("java.lang.Short"),
            "INT", DotName.of("java.lang.Integer"),
            "LONG", DotName.of("java.lang.Long"),
            "FLOAT", DotName.of("java.lang.Float"),
            "DOUBLE", DotName.of("java.lang.Double")
    );

    private static final java.util.Map<String, DotName> PRIMITIVE_NAME_TO_WRAPPER = java.util.Map.of(
            "boolean", DotName.of("java.lang.Boolean"),
            "byte", DotName.of("java.lang.Byte"),
            "char", DotName.of("java.lang.Character"),
            "short", DotName.of("java.lang.Short"),
            "int", DotName.of("java.lang.Integer"),
            "long", DotName.of("java.lang.Long"),
            "float", DotName.of("java.lang.Float"),
            "double", DotName.of("java.lang.Double")
    );

    public boolean isAssignable(TypeInfo beanType, TypeInfo requiredType) {
        // Same type
        if (beanType.equals(requiredType)) return true;

        // CDI spec: primitive types and their wrappers are considered identical
        if (identicalPrimitives(beanType, requiredType)) return true;

        return switch (requiredType) {
            case ClassType req -> isAssignableToClass(beanType, req);
            case ParameterizedType req -> isAssignableToParameterized(beanType, req);
            case WildcardType req -> isAssignableToWildcard(beanType, req);
            case ArrayType req -> isAssignableToArray(beanType, req);
            case TypeVariable req -> isAssignableToBounds(beanType, req);
            default -> false;
        };
    }

    /**
     * A primitive type and its wrapper, whichever stands for the bean and which for the required type,
     * the primitive given either as a {@code PrimitiveType} or as a {@code ClassType} with a primitive
     * name ({@code ClassType[name=boolean]}).
     */
    private static boolean identicalPrimitives(TypeInfo beanType, TypeInfo requiredType) {
        if (requiredType instanceof PrimitiveType(var kind) && isWrapperOf(beanType, kind)) return true;
        if (beanType instanceof PrimitiveType(var kind) && isWrapperOf(requiredType, kind)) return true;

        // Handle ClassType with primitive name (e.g. ClassType[name=boolean] <-> ClassType[name=java.lang.Boolean])
        if (beanType instanceof ClassType(var beanName) && requiredType instanceof ClassType(var requiredName)
                && primitiveAndWrapper(beanName, requiredName)) return true;

        // Handle PrimitiveType <-> ClassType[primitive name]
        if (beanType instanceof PrimitiveType(var kind) && namesPrimitive(requiredType, kind)) return true;
        return requiredType instanceof PrimitiveType(var kind) && namesPrimitive(beanType, kind);
    }

    /** {@code type} is the {@code ClassType} of the wrapper of the primitive {@code kind}. */
    private static boolean isWrapperOf(TypeInfo type, PrimitiveType.Kind kind) {
        var wrapperName = PRIMITIVE_TO_WRAPPER.get(kind.name());
        return wrapperName != null && type instanceof ClassType(var name) && name.equals(wrapperName);
    }

    /**
     * {@code type} is a {@code ClassType} with the name of the primitive {@code kind}, whatever the default locale: a
     * Turkish one lowers {@code INT} to {@code ınt}, with a dotless i (BUG-20261117-20).
     */
    private static boolean namesPrimitive(TypeInfo type, PrimitiveType.Kind kind) {
        return type instanceof ClassType(DotName(var name)) && name.equals(kind.name().toLowerCase(Locale.ROOT));
    }

    /** One name is a primitive's and the other its wrapper's, or both are names of the same primitive. */
    private static boolean primitiveAndWrapper(DotName beanName, DotName requiredName) {
        var beanWrapper = PRIMITIVE_NAME_TO_WRAPPER.get(beanName.value());
        var reqWrapper = PRIMITIVE_NAME_TO_WRAPPER.get(requiredName.value());
        return (beanWrapper != null && requiredName.equals(beanWrapper))
                || (reqWrapper != null && beanName.equals(reqWrapper))
                || (beanWrapper != null && beanWrapper.equals(reqWrapper));
    }

    private boolean isAssignableToClass(TypeInfo beanType, ClassType required) {
        return switch (beanType) {
            case ClassType(var beanName) -> isSubtypeOf(beanName, required.name());
            // A TypeVariable is assignable to a ClassType if at least one bound is assignable
            case TypeVariable(_, var bounds) -> bounds.stream().anyMatch(bound -> isAssignable(bound, required));
            // CDI 4.1 Section 5.2.4: A parameterized bean type is assignable to
            // a raw required type if the raw types are identical and all type parameters
            // of the bean type are either unbounded type variables or java.lang.Object.
            // Otherwise, check if there's any supertype that matches.
            case ParameterizedType(var rawType, var typeArguments) -> rawType.equals(required.name())
                    ? rawEquivalentArguments(typeArguments)
                    : isSubtypeOf(rawType, required.name());
            default -> false;
        };
    }

    /**
     * Every argument is {@code java.lang.Object} or an unbounded type variable: what lets a raw type and a
     * parameterized type of the same class match, whichever is the bean's (CDI 4.1, 5.2.4).
     */
    private static boolean rawEquivalentArguments(List<TypeInfo> arguments) {
        return arguments.stream().allMatch(AssignabilityRules::isObjectOrUnboundedTypeVariable);
    }

    /**
     * {@code java.lang.Object}, or a type variable that declares no bound but {@code java.lang.Object}: {@code T},
     * {@code T extends Object}, and {@code (T extends java.lang.Object)} as a synthetic bean's type reads back. Any
     * other bound bounds it, a type variable too: {@code T extends U} is bounded, {@code U} unbounded or not, as Java
     * has it (JLS 4.4) and Weld's {@code isUnboundedTypeVariable}. A wildcard is neither. {@code BeanManager.getBeans}
     * calls these same rules (BUG-20261117-16).
     */
    private static boolean isObjectOrUnboundedTypeVariable(TypeInfo argument) {
        return switch (argument) {
            case TypeVariable(_, var bounds) -> bounds.stream().allMatch(AssignabilityRules::isObjectClass);
            case ClassType _ -> isObjectClass(argument);
            default -> false;
        };
    }

    /**
     * CDI bean type matching: checks if a bean type exactly matches a required type.
     * Unlike isAssignable, this uses equality for ClassType (since bean type sets
     * already include all supertypes).
     */
    public boolean beanTypeMatches(TypeInfo beanType, TypeInfo requiredType) {
        if (beanType.equals(requiredType)) return true;
        return switch (requiredType) {
            case ClassType(var requiredName) -> switch (beanType) {
                case ClassType(var beanName) ->
                        beanName.equals(requiredName) || primitiveAndWrapper(beanName, requiredName);
                case ParameterizedType(var rawType, var typeArguments) ->
                        rawType.equals(requiredName) && rawEquivalentArguments(typeArguments);
                default -> false;
            };
            case ParameterizedType req -> isAssignableToParameterized(beanType, req);
            default -> isAssignable(beanType, requiredType);
        };
    }

    private boolean isAssignableToParameterized(TypeInfo beanType, ParameterizedType required) {
        return switch (beanType) {
            case ParameterizedType(var rawType, var typeArguments) -> rawType.equals(required.rawType())
                    && typeArgumentsAssignable(typeArguments, required.typeArguments());
            // CDI 4.1, 5.2.4: "A raw bean type is considered assignable to a parameterized required type if the raw
            // types are identical and all type parameters of the required type are either unbounded type variables
            // or java.lang.Object." A raw Foo satisfies Foo<Object>, not Foo<Integer> nor Foo<?> (BUG-20261117-16).
            case ClassType(var beanName) -> beanName.equals(required.rawType())
                    && rawEquivalentArguments(required.typeArguments());
            default -> false;
        };
    }

    private boolean typeArgumentsAssignable(List<TypeInfo> beanArguments, List<TypeInfo> requiredArguments) {
        if (beanArguments.size() != requiredArguments.size()) return false;
        // Each type argument must match (CDI 4.1 Section 2.4.1)
        for (int i = 0; i < beanArguments.size(); i++) {
            if (!isTypeArgumentAssignable(beanArguments.get(i), requiredArguments.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * CDI type argument matching:
     * - Actual type must match actual type exactly (or be a subtype for wildcards)
     * - Wildcard ? extends X: bean arg must be subtype of X
     * - Wildcard ? super X: bean arg must be supertype of X
     */
    private boolean isTypeArgumentAssignable(TypeInfo beanArg, TypeInfo requiredArg) {
        return switch (requiredArg) {
            case WildcardType(var upperBound, var lowerBound) -> wildcardAdmits(beanArg, upperBound, lowerBound);
            // CDI spec rule (f): bean TV vs required TV. No rule for required TV vs bean actual type: no match.
            case TypeVariable(_, var requiredBounds) -> beanArg instanceof TypeVariable(_, var beanBounds)
                    && boundsCovered(beanBounds, requiredBounds);
            default -> actualTypeAdmits(beanArg, requiredArg);
        };
    }

    /** A required wildcard argument with these bounds admits the bean's argument. */
    private boolean wildcardAdmits(TypeInfo beanArg, TypeInfo upperBound, TypeInfo lowerBound) {
        if (beanArg instanceof TypeVariable(_, var beanBounds)) {
            return wildcardAdmitsTypeVariable(upperBounds(beanBounds), upperBound, lowerBound);
        }

        TypeInfo effectiveBeanArg = beanArg;
        if (beanArg instanceof WildcardType(var beanUpperBound, _)) {
            effectiveBeanArg = beanUpperBound != null ? beanUpperBound
                    : new ClassType(DotName.of(JAVA_LANG_OBJECT));
        }

        // Rule c, the bounds as Java sees them: a List<String> is within ? extends List (BUG-20261117-16).
        return (lowerBound == null || isAssignableToBound(lowerBound, effectiveBeanArg))
                && (upperBound == null || isAssignableToBound(effectiveBeanArg, upperBound));
    }

    /**
     * CDI 4.1, 5.2.4, rules da, db and dc: "the upper bound of the type variable is assignable to or assignable from
     * the upper bound, if any, of the wildcard and assignable from the lower bound, if any, of the wildcard". The
     * resolution of an injection point checked the "assignable to" half only, and a type variable with no bound
     * against no bound at all, where {@code BeanManager.getBeans} checks both, with {@code Object} for no bound
     * (BUG-20261117-24).
     */
    private boolean wildcardAdmitsTypeVariable(List<TypeInfo> bounds, TypeInfo upperBound, TypeInfo lowerBound) {
        var lowerAdmitted = lowerBound == null
                || bounds.stream().allMatch(bound -> isAssignableToBound(lowerBound, bound));
        return lowerAdmitted && (upperBound == null
                || bounds.stream().anyMatch(bound -> isAssignable(bound, upperBound))
                || bounds.stream().allMatch(bound -> isAssignableToBound(upperBound, bound)));
    }

    /**
     * The upper bounds of a type variable, those of a type variable among them in its place, and {@code Object} when it
     * declares none: the index leaves the implicit {@code Object} out.
     */
    private static List<TypeInfo> upperBounds(List<TypeInfo> bounds) {
        var upper = bounds.stream()
                .flatMap(bound -> bound instanceof TypeVariable(_, var nested)
                        ? upperBounds(nested).stream() : Stream.of(bound))
                .toList();
        return upper.isEmpty() ? List.of(new ClassType(DotName.of(JAVA_LANG_OBJECT))) : upper;
    }

    /**
     * {@code type} is assignable to {@code bound}, the upper bound of a type variable, a class against a parameterized
     * type by their classes, as {@code BeanManager.getBeans} and the Java compiler see them: {@code Integer} is within
     * {@code T extends Comparable<T>}. The resolution of an injection point compared the names of {@code Integer} and
     * {@code Comparable} (BUG-20261117-24).
     */
    private boolean isAssignableToBound(TypeInfo type, TypeInfo bound) {
        return switch (bound) {
            case ParameterizedType(var rawType, _) when type instanceof ClassType(var name) ->
                    isSubtypeOf(name, rawType);
            case ClassType(var name) when type instanceof ParameterizedType(var rawType, _) ->
                    isSubtypeOf(rawType, name);
            default -> isAssignable(type, bound);
        };
    }

    /**
     * Rule f, "the upper bound of the required type parameter is assignable to the upper bound, if any, of the bean
     * type parameter": each uppermost bound of the bean's type variable is assignable from one of the required one's,
     * as Weld's {@code boundsMatch}. Uppermost, with {@code Object} for no bound: {@code (T extends
     * java.lang.Object)}, as a synthetic bean's type reads back, against {@code T} (BUG-20261117-16).
     */
    private boolean boundsCovered(List<TypeInfo> beanBounds, List<TypeInfo> requiredBounds) {
        var required = upperBounds(requiredBounds);
        return upperBounds(beanBounds).stream().allMatch(beanBound ->
                required.stream().anyMatch(reqBound -> isAssignableToBound(reqBound, beanBound)));
    }

    /** A required actual type argument admits the bean's argument. */
    private boolean actualTypeAdmits(TypeInfo beanArg, TypeInfo requiredArg) {
        return switch (beanArg) {
            // CDI spec rule (e): bean arg is a type variable, required arg is an actual type
            // The actual type must be assignable to all bounds of the bean type variable
            case TypeVariable(_, var bounds) ->
                    upperBounds(bounds).stream().allMatch(bound -> isAssignableToBound(requiredArg, bound));
            // Bean wildcard vs required concrete: check bounds
            case WildcardType(var upperBound, _) -> upperBound != null && isAssignable(upperBound, requiredArg);
            // Rule b: "actual types with identical raw type, and, if the type is parameterized, the bean type parameter
            // is assignable to the required type parameter according to these rules" (BUG-20261117-16).
            case ParameterizedType _ when requiredArg instanceof ParameterizedType required ->
                    isAssignableToParameterized(beanArg, required);
            default -> beanArg.equals(requiredArg);
        };
    }

    private boolean isAssignableToWildcard(TypeInfo beanType, WildcardType required) {
        if (required.upperBound() != null) {
            return isAssignable(beanType, required.upperBound());
        }
        if (required.lowerBound() != null) {
            return isAssignable(required.lowerBound(), beanType);
        }
        return true; // unbounded
    }

    private boolean isAssignableToArray(TypeInfo beanType, ArrayType required) {
        return beanType instanceof ArrayType(var beanComponent, var beanDimensions)
                && beanDimensions == required.dimensions()
                && arrayElementMatches(beanComponent, required.componentType());
    }

    /**
     * CDI 4.1 §5.2.4: array types match only when their element types are
     * identical. Identity is structural — no subtype walking and no
     * primitive boxing ({@code boolean[]} is not {@code Boolean[]}) — but the
     * raw ↔ parameterized equivalence still applies to reference element
     * types (a {@code Class[]} bean type satisfies a {@code Class<?>[]}
     * injection point). Cf. VAU-BCE-004.
     */
    private boolean arrayElementMatches(TypeInfo bean, TypeInfo required) {
        if (bean.equals(required)) return true;
        if (bean instanceof ClassType && required instanceof ClassType) return false;
        if (bean instanceof PrimitiveType || required instanceof PrimitiveType) {
            // Cross-representation primitive identity only (PrimitiveType(BOOLEAN)
            // vs ClassType("boolean")) — never boxing.
            var beanName = primitiveName(bean);
            var requiredName = primitiveName(required);
            return beanName != null && beanName.equals(requiredName);
        }
        if (bean instanceof ClassType(var beanName)
                && required instanceof ParameterizedType(var rawType, var typeArguments)) {
            return beanName.equals(rawType) && allArgumentsUnbounded(typeArguments);
        }
        if (bean instanceof ParameterizedType(var rawType, var typeArguments)
                && required instanceof ClassType(var requiredName)) {
            return rawType.equals(requiredName) && allArgumentsUnbounded(typeArguments);
        }
        // An array of a type variable, T[], is no legal required type: it matches no other element type, which its
        // bounds would admit (BeanManager.getBeans, BUG-20261117-16)
        return !(required instanceof TypeVariable) && isAssignable(bean, required);
    }

    private static String primitiveName(TypeInfo type) {
        return switch (type) {
            case PrimitiveType(var kind) -> kind.name().toLowerCase(Locale.ROOT);
            case ClassType(DotName(var name)) -> name;
            default -> null;
        };
    }

    private static boolean allArgumentsUnbounded(List<TypeInfo> arguments) {
        return arguments.stream().allMatch(AssignabilityRules::isUnbounded);
    }

    /**
     * {@link #isObjectOrUnboundedTypeVariable}, or a wildcard with no bound but {@code Object}: an array element
     * takes {@code Class[]} for {@code Class<?>[]}, which MicroProfile Config injects (VAU-BCE-004). A raw bean type
     * outside an array satisfies no wildcard argument.
     */
    private static boolean isUnbounded(TypeInfo argument) {
        return argument instanceof WildcardType(var upperBound, var lowerBound)
                ? lowerBound == null && (upperBound == null || isObjectClass(upperBound))
                : isObjectOrUnboundedTypeVariable(argument);
    }

    private static boolean isObjectClass(TypeInfo type) {
        return type instanceof ClassType(DotName(var name)) && name.equals(JAVA_LANG_OBJECT);
    }

    private boolean isAssignableToBounds(TypeInfo beanType, TypeVariable required) {
        for (var bound : required.bounds()) {
            if (!isAssignable(beanType, bound)) return false;
        }
        return true;
    }

    /**
     * Checks if {@code subName} is the same as or a subtype of {@code superName}
     * by walking the class hierarchy in the index.
     */
    boolean isSubtypeOf(DotName subName, DotName superName) {
        if (subName.equals(superName)) return true;
        if (JAVA_LANG_OBJECT.equals(superName.value())) return true;

        var visited = new HashSet<DotName>();
        return isSubtypeOfRecursive(subName, superName, visited);
    }

    private boolean isSubtypeOfRecursive(DotName current, DotName target, Set<DotName> visited) {
        if (!visited.add(current)) return false;
        if (current.equals(target)) return true;

        var classInfo = index.getClassByName(current);
        if (classInfo.isEmpty()) {
            // Fallback to reflection if not in index (e.g. JDK classes like java.lang.Integer)
            try {
                var clazz = DeploymentClasses.load(current.value(), loader);
                var superClass = clazz.getSuperclass();
                if (superClass != null && isSubtypeOfRecursive(DotName.of(superClass.getName()), target, visited)) {
                    return true;
                }
                for (var iface : clazz.getInterfaces()) {
                    if (isSubtypeOfRecursive(DotName.of(iface.getName()), target, visited)) {
                        return true;
                    }
                }
            } catch (Exception _) {
                // Not loadable from here: not a subtype as far as this walk can tell.
            }
            return false;
        }

        var info = classInfo.get();

        // Check superclass
        if (info.superName() != null && isSubtypeOfRecursive(info.superName(), target, visited)) {
            return true;
        }

        // Check interfaces
        for (var iface : info.interfaces()) {
            if (isSubtypeOfRecursive(iface, target, visited)) {
                return true;
            }
        }

        return false;
    }
}
