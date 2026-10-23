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
import java.util.List;

public final class TypeInfoUtils {

    private TypeInfoUtils() {
        // static utility: no instances
    }

    public static TypeInfo fromReflectType(Type type) {
        return switch (type) {
            case null -> null;
            case Class<?> c -> fromClass(c);
            case java.lang.reflect.ParameterizedType pt -> fromParameterizedType(pt);
            case java.lang.reflect.WildcardType wt -> fromWildcardType(wt);
            case java.lang.reflect.GenericArrayType gat -> fromGenericArrayType(gat);
            case java.lang.reflect.TypeVariable<?> tv -> fromTypeVariable(tv);
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

    private static TypeInfo fromParameterizedType(java.lang.reflect.ParameterizedType pt) {
        Class<?> raw = (Class<?>) pt.getRawType();
        List<TypeInfo> args = Arrays.stream(pt.getActualTypeArguments())
                .map(TypeInfoUtils::fromReflectType)
                .toList();
        return new io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType(DotName.of(raw.getName()), args);
    }

    private static TypeInfo fromWildcardType(java.lang.reflect.WildcardType wt) {
        TypeInfo upper = null;
        if (wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class) {
            upper = fromReflectType(wt.getUpperBounds()[0]);
        }
        TypeInfo lower = null;
        if (wt.getLowerBounds().length > 0) {
            lower = fromReflectType(wt.getLowerBounds()[0]);
        }
        return new io.vidocq.vauban.indexer.model.TypeInfo.WildcardType(upper, lower);
    }

    private static TypeInfo fromGenericArrayType(java.lang.reflect.GenericArrayType gat) {
        int dims = 1;
        Type comp = gat.getGenericComponentType();
        while (comp instanceof java.lang.reflect.GenericArrayType inner) {
            dims++;
            comp = inner.getGenericComponentType();
        }
        return new ArrayType(fromReflectType(comp), dims);
    }

    private static TypeInfo fromTypeVariable(java.lang.reflect.TypeVariable<?> tv) {
        List<TypeInfo> bounds = Arrays.stream(tv.getBounds())
                .filter(b -> b != Object.class)
                .map(TypeInfoUtils::fromReflectType)
                .toList();
        return new io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable(tv.getName(), bounds);
    }
}
