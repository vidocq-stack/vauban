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
import java.util.stream.Collectors;

public final class TypeInfoUtils {

    public static TypeInfo fromReflectType(Type type) {
        if (type == null) return null;
        if (type instanceof Class<?> c) {
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
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            List<TypeInfo> args = Arrays.stream(pt.getActualTypeArguments())
                    .map(TypeInfoUtils::fromReflectType)
                    .collect(Collectors.toList());
            return new io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType(DotName.of(raw.getName()), args);
        }
        if (type instanceof java.lang.reflect.WildcardType wt) {
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
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            int dims = 1;
            Type comp = gat.getGenericComponentType();
            while (comp instanceof java.lang.reflect.GenericArrayType inner) {
                dims++;
                comp = inner.getGenericComponentType();
            }
            return new ArrayType(fromReflectType(comp), dims);
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> tv) {
            List<TypeInfo> bounds = Arrays.stream(tv.getBounds())
                    .filter(b -> b != Object.class)
                    .map(TypeInfoUtils::fromReflectType)
                    .collect(Collectors.toList());
            return new io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable(tv.getName(), bounds);
        }
        return new ClassType(DotName.of(type.getTypeName()));
    }

    public static Type toReflectType(TypeInfo type, ClassLoader cl) {
        if (type == null) return null;
        try {
            return switch (type) {
                case VoidType _ -> void.class;
                case PrimitiveType p -> switch (p.kind()) {
                    case BOOLEAN -> boolean.class;
                    case BYTE -> byte.class;
                    case CHAR -> char.class;
                    case SHORT -> short.class;
                    case INT -> int.class;
                    case LONG -> long.class;
                    case FLOAT -> float.class;
                    case DOUBLE -> double.class;
                };
                case ClassType ct -> Class.forName(ct.name().value(), true, cl);
                case ArrayType at -> {
                    Class<?> comp = (Class<?>) toReflectType(at.componentType(), cl);
                    yield java.lang.reflect.Array.newInstance(comp, new int[at.dimensions()]).getClass();
                }
                case io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt -> {
                    Class<?> raw = Class.forName(pt.rawType().value(), true, cl);
                    Type[] args = pt.typeArguments().stream()
                            .map(a -> toReflectType(a, cl))
                            .toArray(Type[]::new);
                    yield new ParameterizedTypeImpl(raw, args, raw.getDeclaringClass());
                }
                default -> Object.class; // Fallback
            };
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static class ParameterizedTypeImpl implements java.lang.reflect.ParameterizedType {
        private final Class<?> rawType;
        private final java.lang.reflect.Type[] actualTypeArguments;
        private final java.lang.reflect.Type ownerType;

        public ParameterizedTypeImpl(Class<?> rawType, java.lang.reflect.Type[] actualTypeArguments, java.lang.reflect.Type ownerType) {
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments;
            this.ownerType = ownerType;
        }
        @Override public java.lang.reflect.Type[] getActualTypeArguments() { return actualTypeArguments; }
        @Override public java.lang.reflect.Type getRawType() { return rawType; }
        @Override public java.lang.reflect.Type getOwnerType() { return ownerType; }
        @Override public String toString() { 
            return rawType.getName() + "<" + 
                Arrays.stream(actualTypeArguments).map(Type::getTypeName).collect(Collectors.joining(",")) + ">"; 
        }
    }
}
