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

import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.model.TypeInfo.*;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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

    public AssignabilityRules(VaubanIndex index) {
        this.index = Objects.requireNonNull(index);
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
        if (requiredType instanceof PrimitiveType rp) {
            var wrapperName = PRIMITIVE_TO_WRAPPER.get(rp.kind().name());
            if (wrapperName != null
                    && beanType instanceof ClassType bc && bc.name().equals(wrapperName)) return true;
        }
        if (beanType instanceof PrimitiveType bp) {
            var wrapperName = PRIMITIVE_TO_WRAPPER.get(bp.kind().name());
            if (wrapperName != null
                    && requiredType instanceof ClassType rc && rc.name().equals(wrapperName)) return true;
        }

        // Handle ClassType with primitive name (e.g. ClassType[name=boolean] <-> ClassType[name=java.lang.Boolean])
        if (beanType instanceof ClassType bc && requiredType instanceof ClassType rc) {
            var beanWrapper = PRIMITIVE_NAME_TO_WRAPPER.get(bc.name().value());
            var reqWrapper = PRIMITIVE_NAME_TO_WRAPPER.get(rc.name().value());
            if (beanWrapper != null && rc.name().equals(beanWrapper)) return true;
            if (reqWrapper != null && bc.name().equals(reqWrapper)) return true;
            if (beanWrapper != null && reqWrapper != null && beanWrapper.equals(reqWrapper)) return true;
        }
        // Handle PrimitiveType <-> ClassType[primitive name]
        if (beanType instanceof PrimitiveType bp2 && requiredType instanceof ClassType rc) {
            var primName = bp2.kind().name().toLowerCase();
            if (rc.name().value().equals(primName)) return true;
        }
        if (requiredType instanceof PrimitiveType rp2 && beanType instanceof ClassType bc) {
            var primName = rp2.kind().name().toLowerCase();
            if (bc.name().value().equals(primName)) return true;
        }

        return switch (requiredType) {
            case ClassType req -> isAssignableToClass(beanType, req);
            case ParameterizedType req -> isAssignableToParameterized(beanType, req);
            case WildcardType req -> isAssignableToWildcard(beanType, req);
            case ArrayType req -> isAssignableToArray(beanType, req);
            case TypeVariable req -> isAssignableToBounds(beanType, req);
            default -> false;
        };
    }

    private boolean isAssignableToClass(TypeInfo beanType, ClassType required) {
        return switch (beanType) {
            case ClassType bean -> isSubtypeOf(bean.name(), required.name());
            case TypeVariable tv -> {
                // A TypeVariable is assignable to a ClassType if at least one bound is assignable
                for (var bound : tv.bounds()) {
                    if (isAssignable(bound, required)) {
                        yield true;
                    }
                }
                yield false;
            }
            case ParameterizedType bean -> {
                // CDI 4.1 Section 5.2.4: A parameterized bean type is assignable to
                // a raw required type if the raw types are identical and all type parameters
                // of the bean type are either unbounded type variables or java.lang.Object.
                if (!bean.rawType().equals(required.name())) {
                    // Check if there's any supertype that matches
                    yield isSubtypeOf(bean.rawType(), required.name());
                } else {
                    for (TypeInfo arg : bean.typeArguments()) {
                        if (arg instanceof TypeVariable tv) {
                            for (TypeInfo bound : tv.bounds()) {
                                if (bound instanceof ClassType ct && !ct.name().value().equals(JAVA_LANG_OBJECT)) {
                                    yield false;
                                }
                            }
                        } else if (arg instanceof ClassType ct) {
                            if (!ct.name().value().equals(JAVA_LANG_OBJECT)) {
                                yield false;
                            }
                        } else {
                            yield false;
                        }
                    }
                    yield true;
                }
            }
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
            case ClassType req -> switch (beanType) {
                case ClassType bean -> {
                    if (bean.name().equals(req.name())) yield true;
                    var beanWrapper = PRIMITIVE_NAME_TO_WRAPPER.get(bean.name().value());
                    var reqWrapper = PRIMITIVE_NAME_TO_WRAPPER.get(req.name().value());
                    if (beanWrapper != null && req.name().equals(beanWrapper)) yield true;
                    if (reqWrapper != null && bean.name().equals(reqWrapper)) yield true;
                    if (beanWrapper != null && reqWrapper != null && beanWrapper.equals(reqWrapper)) yield true;
                    yield false;
                }
                case ParameterizedType bean -> {
                    if (!bean.rawType().equals(req.name())) {
                        yield false;
                    }
                    for (TypeInfo arg : bean.typeArguments()) {
                        if (arg instanceof TypeVariable tv) {
                            for (TypeInfo bound : tv.bounds()) {
                                if (bound instanceof ClassType ct && !ct.name().value().equals(JAVA_LANG_OBJECT)) {
                                    yield false;
                                }
                            }
                        } else if (arg instanceof ClassType ct) {
                            if (!ct.name().value().equals(JAVA_LANG_OBJECT)) {
                                yield false;
                            }
                        } else {
                            yield false;
                        }
                    }
                    yield true;
                }
                default -> false;
            };
            case ParameterizedType req -> isAssignableToParameterized(beanType, req);
            default -> isAssignable(beanType, requiredType);
        };
    }

    private boolean isAssignableToParameterized(TypeInfo beanType, ParameterizedType required) {
        return switch (beanType) {
            case ParameterizedType bean -> {
                if (!bean.rawType().equals(required.rawType())) yield false;
                if (bean.typeArguments().size() != required.typeArguments().size()) yield false;
                // Each type argument must match (CDI 4.1 Section 2.4.1)
                for (int i = 0; i < bean.typeArguments().size(); i++) {
                    if (!isTypeArgumentAssignable(bean.typeArguments().get(i), required.typeArguments().get(i))) {
                        yield false;
                    }
                }
                yield true;
            }
            // CDI spec 4.1 Section 2.4.1: A raw bean type is assignable to a parameterized required type if they have identical raw types.
            // This case specifically applies when the bean itself IS a raw type (no type parameters in declaration).
            // However, most beans in these tests ARE parameterized, so they fall into the ParameterizedType case above.
            case ClassType bean -> {
                if (!bean.name().equals(required.rawType())) yield false;
                yield true;
            }
            default -> false;
        };
    }

    /**
     * CDI type argument matching:
     * - Actual type must match actual type exactly (or be a subtype for wildcards)
     * - Wildcard ? extends X: bean arg must be subtype of X
     * - Wildcard ? super X: bean arg must be supertype of X
     */
    private boolean isTypeArgumentAssignable(TypeInfo beanArg, TypeInfo requiredArg) {
        return switch (requiredArg) {
            case WildcardType wildcard -> {
                if (beanArg instanceof TypeVariable beanTv && !beanTv.bounds().isEmpty()) {
                    // Bean TV with bounds: lower bound must be assignable to ALL bounds,
                    // and at least one bound must be assignable to upper bound
                    if (wildcard.lowerBound() != null) {
                        for (var beanBound : beanTv.bounds()) {
                            if (!isAssignable(wildcard.lowerBound(), beanBound)) yield false;
                        }
                    }
                    if (wildcard.upperBound() != null) {
                        boolean anyMatch = false;
                        for (var beanBound : beanTv.bounds()) {
                            if (isAssignable(beanBound, wildcard.upperBound())) {
                                anyMatch = true;
                                break;
                            }
                        }
                        if (!anyMatch) yield false;
                    }
                    yield true;
                }

                TypeInfo effectiveBeanArg = beanArg;
                if (beanArg instanceof WildcardType bw) {
                    effectiveBeanArg = bw.upperBound() != null ? bw.upperBound()
                            : new ClassType(DotName.of(JAVA_LANG_OBJECT));
                }

                if (wildcard.lowerBound() != null
                        && !isAssignable(wildcard.lowerBound(), effectiveBeanArg)) yield false;
                if (wildcard.upperBound() != null
                        && !isAssignable(effectiveBeanArg, wildcard.upperBound())) yield false;
                yield true;
            }
            case TypeVariable tv -> {
                if (beanArg instanceof TypeVariable beanTv) {
                    // CDI spec rule (f): bean TV vs required TV
                    // Each bound of the bean TV must be covered by at least one bound of the required TV
                    for (var beanBound : beanTv.bounds()) {
                        boolean covered = false;
                        for (var reqBound : tv.bounds()) {
                            if (isAssignable(reqBound, beanBound)) {
                                covered = true;
                                break;
                            }
                        }
                        if (!covered) yield false;
                    }
                    yield true;
                }
                // CDI spec: no rule for required TV vs bean actual type -> no match
                yield false;
            }
            default -> {
                // CDI spec rule (e): bean arg is a type variable, required arg is an actual type
                // The actual type must be assignable to all bounds of the bean type variable
                if (beanArg instanceof TypeVariable tv) {
                     for (var bound : tv.bounds()) {
                         if (!isAssignable(requiredArg, bound)) yield false;
                     }
                     yield true;
                }
                if (beanArg instanceof WildcardType beanWild) {
                    // Bean wildcard vs required concrete: check bounds
                    if (beanWild.upperBound() != null) {
                        yield isAssignable(beanWild.upperBound(), requiredArg);
                    }
                    yield false;
                }
                yield beanArg.equals(requiredArg);
            }
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
        if (beanType instanceof ArrayType beanArray) {
            if (beanArray.dimensions() != required.dimensions()) return false;
            return isAssignable(beanArray.componentType(), required.componentType());
        }
        return false;
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
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(current.value(), false, cl)
                        : Class.forName(current.value());
                var superClass = clazz.getSuperclass();
                if (superClass != null && isSubtypeOfRecursive(DotName.of(superClass.getName()), target, visited)) {
                    return true;
                }
                for (var iface : clazz.getInterfaces()) {
                    if (isSubtypeOfRecursive(DotName.of(iface.getName()), target, visited)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // skip
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
