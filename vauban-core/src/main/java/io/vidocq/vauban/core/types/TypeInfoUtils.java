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

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.model.TypeInfo.*;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TypeInfoUtils {

    private TypeInfoUtils() {
        // static utility: no instances
    }

    /**
     * {@code type} in the index model, with its conventions: the implicit {@code Object} bound of a type variable left
     * out, and a type variable met again while its own bounds are converted ({@code T} in
     * {@code T extends Comparable<T>}) given no bound, so that the model stays finite, as the index records it.
     */
    public static TypeInfo fromReflectType(Type type) {
        return fromReflectType(type, new HashSet<>());
    }

    private static TypeInfo fromReflectType(Type type, Set<java.lang.reflect.TypeVariable<?>> converting) {
        return switch (type) {
            case null -> null;
            case Class<?> c -> fromClass(c);
            case java.lang.reflect.ParameterizedType pt -> fromParameterizedType(pt, converting);
            case java.lang.reflect.WildcardType wt -> fromWildcardType(wt, converting);
            case java.lang.reflect.GenericArrayType gat -> fromGenericArrayType(gat, converting);
            case java.lang.reflect.TypeVariable<?> tv -> fromTypeVariable(tv, converting);
            default -> new ClassType(DotName.of(type.getTypeName()));
        };
    }

    private static TypeInfo fromClass(Class<?> c) {
        if (c == void.class) return new VoidType();
        if (c.isPrimitive()) {
            return new PrimitiveType(switch (c.getName()) {
                case "boolean" -> PrimitiveType.Kind.BOOLEAN;
                case "byte" -> PrimitiveType.Kind.BYTE;
                case "char" -> PrimitiveType.Kind.CHAR;
                case "short" -> PrimitiveType.Kind.SHORT;
                case "int" -> PrimitiveType.Kind.INT;
                case "long" -> PrimitiveType.Kind.LONG;
                case "float" -> PrimitiveType.Kind.FLOAT;
                case "double" -> PrimitiveType.Kind.DOUBLE;
                default -> throw new IllegalArgumentException("Unknown primitive: " + c.getName());
            });
        }
        if (c.isArray()) {
            int dims = 0;
            Class<?> comp = c;
            while (comp.isArray()) {
                dims++;
                comp = comp.getComponentType();
            }
            return new ArrayType(fromReflectType(comp), dims);
        }
        return new ClassType(DotName.of(c.getName()));
    }

    private static TypeInfo fromParameterizedType(java.lang.reflect.ParameterizedType pt,
            Set<java.lang.reflect.TypeVariable<?>> converting) {
        Class<?> raw = (Class<?>) pt.getRawType();
        List<TypeInfo> args = Arrays.stream(pt.getActualTypeArguments())
                .map(argument -> fromReflectType(argument, converting))
                .toList();
        // The enclosing type, where it is parameterized, as the index reads it (BUG-20261117-21)
        var owner = pt.getOwnerType() instanceof java.lang.reflect.ParameterizedType parameterized
                ? fromParameterizedType(parameterized, converting) : null;
        return new io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType(DotName.of(raw.getName()), args, owner);
    }

    private static TypeInfo fromWildcardType(java.lang.reflect.WildcardType wt,
            Set<java.lang.reflect.TypeVariable<?>> converting) {
        TypeInfo upper = null;
        if (wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class) {
            upper = fromReflectType(wt.getUpperBounds()[0], converting);
        }
        TypeInfo lower = null;
        if (wt.getLowerBounds().length > 0) {
            lower = fromReflectType(wt.getLowerBounds()[0], converting);
        }
        return new io.vidocq.vauban.indexer.model.TypeInfo.WildcardType(upper, lower);
    }

    private static TypeInfo fromGenericArrayType(java.lang.reflect.GenericArrayType gat,
            Set<java.lang.reflect.TypeVariable<?>> converting) {
        int dims = 1;
        Type comp = gat.getGenericComponentType();
        while (comp instanceof java.lang.reflect.GenericArrayType inner) {
            dims++;
            comp = inner.getGenericComponentType();
        }
        return new ArrayType(fromReflectType(comp, converting), dims);
    }

    private static TypeInfo fromTypeVariable(java.lang.reflect.TypeVariable<?> tv,
            Set<java.lang.reflect.TypeVariable<?>> converting) {
        if (!converting.add(tv)) {
            return new io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable(tv.getName(), List.of());
        }
        List<TypeInfo> bounds = Arrays.stream(tv.getBounds())
                .filter(b -> b != Object.class)
                .map(bound -> fromReflectType(bound, converting))
                .toList();
        converting.remove(tv);
        return new io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable(tv.getName(), bounds);
    }
}
