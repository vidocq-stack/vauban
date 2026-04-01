package fr.vidocq.vauban.core.types;

import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import fr.vidocq.vauban.indexer.model.TypeInfo.*;

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
public final class AssignabilityRules {

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

    public boolean isAssignable(TypeInfo beanType, TypeInfo requiredType) {
        // Same type
        if (beanType.equals(requiredType)) return true;

        // CDI spec: primitive types and their wrappers are considered identical
        if (requiredType instanceof PrimitiveType rp) {
            var wrapperName = PRIMITIVE_TO_WRAPPER.get(rp.kind().name());
            if (wrapperName != null) {
                if (beanType instanceof ClassType bc && bc.name().equals(wrapperName)) return true;
            }
        }
        if (beanType instanceof PrimitiveType bp) {
            var wrapperName = PRIMITIVE_TO_WRAPPER.get(bp.kind().name());
            if (wrapperName != null) {
                if (requiredType instanceof ClassType rc && rc.name().equals(wrapperName)) return true;
            }
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
            case ParameterizedType bean -> isSubtypeOf(bean.rawType(), required.name());
            default -> false;
        };
    }

    private boolean isAssignableToParameterized(TypeInfo beanType, ParameterizedType required) {
        return switch (beanType) {
            case ParameterizedType bean -> {
                if (!bean.rawType().equals(required.rawType())) yield false;
                if (bean.typeArguments().size() != required.typeArguments().size()) yield false;
                // Each type argument must match
                for (int i = 0; i < bean.typeArguments().size(); i++) {
                    if (!isTypeArgumentAssignable(bean.typeArguments().get(i), required.typeArguments().get(i))) {
                        yield false;
                    }
                }
                yield true;
            }
            // CDI spec: A raw bean type is assignable to a parameterized required type if they have identical raw types.
            case ClassType bean -> bean.name().equals(required.rawType());
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
                if (wildcard.upperBound() != null) {
                    // ? extends X — bean arg must be assignable to X
                    yield isAssignable(beanArg, wildcard.upperBound());
                } else if (wildcard.lowerBound() != null) {
                    // ? super X — X must be assignable to bean arg
                    yield isAssignable(wildcard.lowerBound(), beanArg);
                }
                // Unbounded wildcard ? — any type matches
                yield true;
            }
            case TypeVariable tv -> {
                // Type variable in required: bean arg must satisfy all bounds
                for (var bound : tv.bounds()) {
                    if (!isAssignable(beanArg, bound)) yield false;
                }
                yield true;
            }
            default -> {
                // Exact match required for non-wildcard type arguments, EXCEPT if beanArg is a TypeVariable
                if (beanArg instanceof TypeVariable tv) {
                     // If bean arg is a type variable, it matches if all its bounds are assignable to requiredArg
                     for (var bound : tv.bounds()) {
                         if (isAssignable(bound, requiredArg)) yield true;
                     }
                     yield false;
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
        if ("java.lang.Object".equals(superName.value())) return true;

        var visited = new HashSet<DotName>();
        return isSubtypeOfRecursive(subName, superName, visited);
    }

    private boolean isSubtypeOfRecursive(DotName current, DotName target, Set<DotName> visited) {
        if (!visited.add(current)) return false;
        if (current.equals(target)) return true;

        var classInfo = index.getClassByName(current);
        if (classInfo.isEmpty()) return false;

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
