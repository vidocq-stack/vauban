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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A type and the classes and interfaces above it, their type variables replaced by the arguments the walk gave them, as
 * the Java compiler replaces them (JLS 4.10.2): inside a wildcard, an array and the type that encloses an inner class
 * too, and the type variables of an enclosing class given by the enclosing type of a parameterized one
 * (BUG-20261117-14). A supertype a class names raw is walked from its declaration, its type variables left as they are.
 */
public class TypeHierarchyResolver {

    private TypeHierarchyResolver() {}

    public static Set<Type> resolveAllSupertypes(Type type) {
        Set<Type> result = new LinkedHashSet<>();
        resolveInternal(type, Map.of(), result);
        return result;
    }

    private static void resolveInternal(Type type, Map<TypeVariable<?>, Type> typeMap, Set<Type> result) {
        if (type == null || type == Object.class) return;

        Type resolvedType = substitute(type, typeMap, new HashSet<>());
        result.add(resolvedType);

        switch (resolvedType) {
            case Class<?> c -> walkSupertypes(c, typeMap, result);
            case ParameterizedType pt -> walkSupertypes((Class<?>) pt.getRawType(), withArguments(pt, typeMap), result);
            default -> {
                // A type variable, a wildcard or an array type adds no supertype to walk.
            }
        }
    }

    private static void walkSupertypes(Class<?> type, Map<TypeVariable<?>, Type> typeMap, Set<Type> result) {
        resolveInternal(type.getGenericSuperclass(), typeMap, result);
        for (Type gi : type.getGenericInterfaces()) {
            resolveInternal(gi, typeMap, result);
        }
    }

    /**
     * {@code typeMap}, with the type variables of {@code type}'s class, and of each class that encloses it, mapped to
     * the arguments {@code type} and its enclosing types give them: {@code Outer<String>.Inner<Integer>} maps the
     * {@code O} of {@code Outer<O>} to {@code String}.
     */
    private static Map<TypeVariable<?>, Type> withArguments(ParameterizedType type, Map<TypeVariable<?>, Type> typeMap) {
        Map<TypeVariable<?>, Type> newMap = new HashMap<>(typeMap);
        for (var current = type; current != null;
                current = current.getOwnerType() instanceof ParameterizedType owner ? owner : null) {
            TypeVariable<?>[] typeVars = ((Class<?>) current.getRawType()).getTypeParameters();
            Type[] actualArgs = current.getActualTypeArguments();
            for (int i = 0; i < typeVars.length && i < actualArgs.length; i++) {
                newMap.put(typeVars[i], actualArgs[i]);
            }
        }
        return newMap;
    }

    /**
     * {@code type}, each type variable {@code typeMap} maps replaced by its argument, at any depth; {@code type} itself
     * when none is. {@code seen} holds the type variables being replaced, so that a cycle ends.
     */
    private static Type substitute(Type type, Map<TypeVariable<?>, Type> typeMap, Set<TypeVariable<?>> seen) {
        return switch (type) {
            case TypeVariable<?> variable -> substituteVariable(variable, typeMap, seen);
            case ParameterizedType parameterized -> substituteParameterized(parameterized, typeMap, seen);
            case WildcardType wildcard -> substituteWildcard(wildcard, typeMap, seen);
            case GenericArrayType array -> substituteArray(array, typeMap, seen);
            case null, default -> type;
        };
    }

    private static Type substituteVariable(TypeVariable<?> variable, Map<TypeVariable<?>, Type> typeMap,
            Set<TypeVariable<?>> seen) {
        Type resolved = typeMap.get(variable);
        if (resolved == null || resolved == variable || !seen.add(variable)) {
            return variable;
        }
        Type result = substitute(resolved, typeMap, seen);
        seen.remove(variable);
        return result;
    }

    private static Type substituteParameterized(ParameterizedType type, Map<TypeVariable<?>, Type> typeMap,
            Set<TypeVariable<?>> seen) {
        Type[] args = type.getActualTypeArguments();
        Type[] newArgs = substituteAll(args, typeMap, seen);
        Type owner = type.getOwnerType();
        Type newOwner = owner == null ? null : substitute(owner, typeMap, seen);
        return newArgs == args && newOwner == owner
                ? type
                : new SubstitutedParameterizedType((Class<?>) type.getRawType(), newArgs, newOwner);
    }

    private static Type substituteWildcard(WildcardType wildcard, Map<TypeVariable<?>, Type> typeMap,
            Set<TypeVariable<?>> seen) {
        Type[] upper = wildcard.getUpperBounds();
        Type[] lower = wildcard.getLowerBounds();
        Type[] newUpper = substituteAll(upper, typeMap, seen);
        Type[] newLower = substituteAll(lower, typeMap, seen);
        return newUpper == upper && newLower == lower ? wildcard : new SubstitutedWildcardType(newUpper, newLower);
    }

    private static Type substituteArray(GenericArrayType array, Map<TypeVariable<?>, Type> typeMap,
            Set<TypeVariable<?>> seen) {
        Type component = array.getGenericComponentType();
        Type newComponent = substitute(component, typeMap, seen);
        if (newComponent == component) {
            return array;
        }
        // Reflection gives an array of a class as the class of that array: String[] for S[] with S = String
        return newComponent instanceof Class<?> componentClass
                ? componentClass.arrayType()
                : new SubstitutedGenericArrayType(newComponent);
    }

    /** {@code types} substituted; {@code types} itself when none changes. */
    private static Type[] substituteAll(Type[] types, Map<TypeVariable<?>, Type> typeMap, Set<TypeVariable<?>> seen) {
        Type[] substituted = types;
        for (int i = 0; i < types.length; i++) {
            Type newType = substitute(types[i], typeMap, seen);
            if (newType != types[i]) {
                if (substituted == types) {
                    substituted = types.clone();
                }
                substituted[i] = newType;
            }
        }
        return substituted;
    }

    private record SubstitutedParameterizedType(Class<?> rawType, Type[] typeArguments, Type ownerType)
            implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return typeArguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return ownerType; }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType other
                    && rawType.equals(other.getRawType())
                    && Arrays.equals(typeArguments, other.getActualTypeArguments())
                    && Objects.equals(ownerType, other.getOwnerType());
        }

        @Override
        public int hashCode() {
            // Match sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl
            return Arrays.hashCode(typeArguments)
                    ^ Objects.hashCode(ownerType)
                    ^ rawType.hashCode();
        }

        @Override
        public String toString() {
            return rawType.getTypeName() + "<"
                    + Arrays.stream(typeArguments).map(Type::getTypeName)
                            .reduce((a, b) -> a + "," + b).orElse("") + ">";
        }
    }

    /** A wildcard whose bounds a substitution changed, equal to the JDK's wildcard of the same bounds. */
    private record SubstitutedWildcardType(Type[] upperBounds, Type[] lowerBounds) implements WildcardType {
        @Override public Type[] getUpperBounds() { return upperBounds.clone(); }
        @Override public Type[] getLowerBounds() { return lowerBounds.clone(); }

        @Override
        public boolean equals(Object o) {
            return o instanceof WildcardType other
                    && Arrays.equals(lowerBounds, other.getLowerBounds())
                    && Arrays.equals(upperBounds, other.getUpperBounds());
        }

        @Override
        public int hashCode() {
            // Match sun.reflect.generics.reflectiveObjects.WildcardTypeImpl
            return Arrays.hashCode(lowerBounds) ^ Arrays.hashCode(upperBounds);
        }

        @Override
        public String toString() {
            return lowerBounds.length > 0 ? "? super " + lowerBounds[0].getTypeName()
                    : "? extends " + Arrays.stream(upperBounds).map(Type::getTypeName)
                            .collect(Collectors.joining(" & "));
        }
    }

    /** An array of a type a substitution changed, equal to the JDK's generic array type of the same component. */
    private record SubstitutedGenericArrayType(Type genericComponentType) implements GenericArrayType {
        @Override public Type getGenericComponentType() { return genericComponentType; }

        @Override
        public boolean equals(Object o) {
            return o instanceof GenericArrayType other
                    && Objects.equals(genericComponentType, other.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            // Match sun.reflect.generics.reflectiveObjects.GenericArrayTypeImpl
            return Objects.hashCode(genericComponentType);
        }

        @Override
        public String toString() {
            return genericComponentType.getTypeName() + "[]";
        }
    }
}
