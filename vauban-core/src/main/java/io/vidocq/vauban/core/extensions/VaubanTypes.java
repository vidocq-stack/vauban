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

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.types.VaubanArrayType;
import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.core.langmodel.types.VaubanParameterizedType;
import io.vidocq.vauban.core.langmodel.types.VaubanPrimitiveType;
import io.vidocq.vauban.core.langmodel.types.VaubanVoidType;
import io.vidocq.vauban.core.langmodel.types.VaubanWildcardType;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.VoidType;
import jakarta.enterprise.lang.model.types.WildcardType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class VaubanTypes implements Types {

    private static final Map<Class<?>, PrimitiveType.PrimitiveKind> PRIMITIVE_MAP = Map.of(
            boolean.class, PrimitiveType.PrimitiveKind.BOOLEAN,
            byte.class, PrimitiveType.PrimitiveKind.BYTE,
            char.class, PrimitiveType.PrimitiveKind.CHAR,
            short.class, PrimitiveType.PrimitiveKind.SHORT,
            int.class, PrimitiveType.PrimitiveKind.INT,
            long.class, PrimitiveType.PrimitiveKind.LONG,
            float.class, PrimitiveType.PrimitiveKind.FLOAT,
            double.class, PrimitiveType.PrimitiveKind.DOUBLE
    );

    private final IndexLookup lookup;

    public VaubanTypes(IndexLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Type of(Class<?> clazz) {
        if (clazz == void.class) {
            return ofVoid();
        }
        if (clazz.isPrimitive()) {
            return ofPrimitive(PRIMITIVE_MAP.get(clazz));
        }
        if (clazz.isArray()) {
            int dimensions = 0;
            var component = clazz;
            while (component.isArray()) {
                dimensions++;
                component = component.getComponentType();
            }
            return ofArray(of(component), dimensions);
        }
        return ofClass(clazz.getName());
    }

    @Override
    public VoidType ofVoid() {
        return VaubanVoidType.INSTANCE;
    }

    @Override
    public PrimitiveType ofPrimitive(PrimitiveType.PrimitiveKind kind) {
        return new VaubanPrimitiveType(kind);
    }

    @Override
    public ClassType ofClass(String name) {
        // Always return a ClassType, even when the named class is not in the
        // Vauban index (typical for JDK types: java.lang.String, java.util.Optional, …).
        // VaubanClassType.declaration() falls back to a synthetic stub so spec
        // calls like ClassType.declaration().name() still produce the FQN.
        // Cf. VAU-BCE-001 — without this, types.of(String.class) returned null
        // and types.parameterized(Optional.class, types.of(String.class)) blew
        // up with NPE inside List.of(..) downstream.
        return new VaubanClassType(DotName.of(name), lookup);
    }

    @Override
    public ClassType ofClass(ClassInfo clazz) {
        return new VaubanClassType(DotName.of(clazz.name()), lookup);
    }

    @Override
    public ArrayType ofArray(Type elementType, int dimensions) {
        var result = elementType;
        for (int i = 0; i < dimensions; i++) {
            result = new VaubanArrayType(result);
        }
        return (ArrayType) result;
    }

    @Override
    public ParameterizedType parameterized(Class<?> genericType, Class<?>... typeArguments) {
        var typeArgs = Arrays.stream(typeArguments)
                .map(this::of)
                .toList();
        return new VaubanParameterizedType(DotName.of(genericType.getName()), typeArgs, lookup);
    }

    @Override
    public ParameterizedType parameterized(Class<?> genericType, Type... typeArguments) {
        return new VaubanParameterizedType(DotName.of(genericType.getName()), List.of(typeArguments), lookup);
    }

    @Override
    public ParameterizedType parameterized(ClassType genericType, Type... typeArguments) {
        var dotName = (genericType instanceof VaubanClassType vct) ? vct.dotName() : DotName.of(genericType.declaration().name());
        return new VaubanParameterizedType(dotName, List.of(typeArguments), lookup);
    }

    @Override
    public WildcardType wildcardWithUpperBound(Type upperBound) {
        return new VaubanWildcardType(upperBound, null);
    }

    @Override
    public WildcardType wildcardWithLowerBound(Type lowerBound) {
        return new VaubanWildcardType(null, lowerBound);
    }

    @Override
    public WildcardType wildcardUnbounded() {
        return new VaubanWildcardType(ofClass(Object.class.getName()), null);
    }
}
