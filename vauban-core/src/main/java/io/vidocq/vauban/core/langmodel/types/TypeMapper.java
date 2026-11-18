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
package io.vidocq.vauban.core.langmodel.types;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.types.TypeInfoUtils;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Converts indexer {@link TypeInfo} to CDI lang model {@link Type}.
 * <p>
 * Multi-dimensional arrays are represented as nested {@link VaubanArrayType} instances,
 * matching the CDI spec's recursive component type model.
 */
public final class TypeMapper {

    private static final TypeInfo OBJECT = new TypeInfo.ClassType(DotName.of("java.lang.Object"));

    private TypeMapper() {
    }

    public static Type map(TypeInfo typeInfo, IndexLookup lookup) {
        return map(typeInfo, lookup, TypeUse.NONE);
    }

    /**
     * The type {@code typeInfo}, used where {@code use} says, with the annotations the use puts on it and inside it
     * (BUG-20261117-60).
     */
    public static Type map(TypeInfo typeInfo, IndexLookup lookup, TypeUse use) {
        return map(typeInfo, lookup, use, Map.of());
    }

    /**
     * The type {@code typeInfo}, inside the bounds of the type variables {@code reading} names, which are being mapped,
     * each with the bounds it is mapped with.
     */
    private static Type map(TypeInfo typeInfo, IndexLookup lookup, TypeUse use, Map<String, List<TypeInfo>> reading) {
        return switch (typeInfo) {
            case TypeInfo.VoidType _ -> VaubanVoidType.INSTANCE;
            case TypeInfo.PrimitiveType p -> new VaubanPrimitiveType(p, use.annotations(lookup));
            // A primitive injection point / field type can reach the BCE boundary encoded as a
            // ClassType whose name is a reserved primitive ("boolean", "int", …) rather than a
            // TypeInfo.PrimitiveType. The CDI Lite lang model requires it to be a PrimitiveType,
            // so portable extensions can do `type instanceof PrimitiveType` (e.g. to box it).
            // Without this, such a type was exposed as ClassType[name=boolean] and broke synthetic
            // bean registration for primitive injection points (Cervantes @Claim boolean). The
            // names are the JDK's, as the extensions' type matcher reads them (BceTypeMatcher).
            case TypeInfo.ClassType(var name) when TypeInfoUtils.fromReflectType(Class.forPrimitiveName(name.value()))
                    instanceof TypeInfo.PrimitiveType primitive ->
                    new VaubanPrimitiveType(primitive, use.annotations(lookup));
            case TypeInfo.ClassType(var name) -> new VaubanClassType(name, lookup, use.annotations(lookup));
            case TypeInfo.ArrayType a -> mapArray(a, lookup, use, reading);
            // Outer<String>.Plain: the language model has no owner, and a parameterized type there has arguments
            // (BUG-20261118-47)
            case TypeInfo.ParameterizedType(var rawType, var typeArguments, _) when typeArguments.isEmpty() ->
                    new VaubanClassType(rawType, lookup, use.annotations(lookup));
            case TypeInfo.ParameterizedType(var rawType, var typeArguments, _) -> new VaubanParameterizedType(
                    rawType, mapAll(typeArguments, use::typeArgument, lookup, reading), lookup,
                    use.annotations(lookup));
            case TypeInfo.TypeVariable(var name, var bounds) -> typeVariable(name, bounds, lookup, use, reading);
            case TypeInfo.WildcardType(var upperBound, var lowerBound) ->
                    wildcard(upperBound, lowerBound, lookup, use, reading);
        };
    }

    /**
     * A type variable with the bounds its type parameter declares, {@code java.lang.Object} when it declares none, as
     * the language model asks: "never {@code null} or empty" (BUG-20261117-33). The index records a type variable met
     * again inside its own bounds, or one of an enclosing declaration, without bounds, so that its model stays finite:
     * those come from the type parameters where the type is used. Met again inside its own bounds, {@code T} in
     * {@code <T extends Comparable<T>>}, it is the same type variable, whose bounds are mapped when asked.
     */
    private static Type typeVariable(String name, List<TypeInfo> recorded, IndexLookup lookup, TypeUse use,
            Map<String, List<TypeInfo>> reading) {
        var annotations = use.annotations(lookup);
        var outer = reading.get(name);
        if (recorded.isEmpty() && outer != null) {
            return new VaubanTypeVariableReference(name, outer, () -> mapAll(outer,
                    position -> use.bound(name, position), lookup, reading), annotations);
        }
        var declared = recorded.isEmpty() ? use.declaredBounds(name) : recorded;
        var bounds = declared == null || declared.isEmpty() ? List.of(OBJECT) : declared;
        var inner = new HashMap<>(reading);
        inner.put(name, bounds);
        return new VaubanTypeVariable(name, mapAll(bounds, position -> use.bound(name, position), lookup,
                Map.copyOf(inner)), annotations);
    }

    /** Each of {@code types}, used where {@code uses} says for its position. */
    private static List<Type> mapAll(List<TypeInfo> types, IntFunction<TypeUse> uses, IndexLookup lookup,
            Map<String, List<TypeInfo>> reading) {
        var mapped = new ArrayList<Type>(types.size());
        for (int i = 0; i < types.size(); i++) {
            mapped.add(map(types.get(i), lookup, uses.apply(i), reading));
        }
        return mapped;
    }

    /**
     * A wildcard of the language model, where "{@code ?} is equivalent to {@code ? extends Object} and is represented
     * as such": the index records {@code ?} without bounds, as the class files and reflection read it, and the
     * language model gives it {@code java.lang.Object} as its upper bound (BUG-20261117-34).
     */
    private static Type wildcard(TypeInfo upperBound, TypeInfo lowerBound, IndexLookup lookup, TypeUse use,
            Map<String, List<TypeInfo>> reading) {
        var upper = upperBound == null && lowerBound == null ? OBJECT : upperBound;
        var bound = use.wildcardBound();
        return new VaubanWildcardType(upper != null ? map(upper, lookup, bound, reading) : null,
                lowerBound != null ? map(lowerBound, lookup, bound, reading) : null, use.annotations(lookup));
    }

    /**
     * Maps an indexer array type to a CDI array type.
     * The indexer model stores dimensions as an int, but the CDI model uses
     * nested component types. For example, {@code int[][]} (dimensions=2) becomes
     * {@code ArrayType(ArrayType(int))}. The outermost array is the first a type annotation's path steps into.
     */
    private static Type mapArray(TypeInfo.ArrayType arrayType, IndexLookup lookup, TypeUse use,
            Map<String, List<TypeInfo>> reading) {
        var uses = new ArrayList<TypeUse>(arrayType.dimensions() + 1);
        uses.add(use);
        for (int i = 0; i < arrayType.dimensions(); i++) {
            uses.add(uses.getLast().component());
        }
        Type result = map(arrayType.componentType(), lookup, uses.getLast(), reading);
        for (int i = arrayType.dimensions() - 1; i >= 0; i--) {
            result = new VaubanArrayType(result, uses.get(i).annotations(lookup));
        }
        return result;
    }
}
