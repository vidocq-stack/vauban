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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link TypeInfoUtils#fromReflectType} for each kind of reflected type. */
class TypeInfoUtilsFromReflectTypeTest {

    private static final TypeInfo NUMBER = new TypeInfo.ClassType(DotName.of("java.lang.Number"));
    private static final TypeInfo STRING = new TypeInfo.ClassType(DotName.of("java.lang.String"));

    /** Declarations to read the reflected types off. */
    @SuppressWarnings({"unused", "java:S1068"})
    private static final class Declared<N extends Number, F> {
        List<? super String> lowerBounded;
        List<? extends Number> upperBounded;
        N[][] genericArray;
        F free;
    }

    private static Type declared(String field) {
        try {
            return Declared.class.getDeclaredField(field).getGenericType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(field, e);
        }
    }

    private static TypeInfo argumentOf(String field) {
        var parameterized = (TypeInfo.ParameterizedType) TypeInfoUtils.fromReflectType(declared(field));
        return parameterized.typeArguments().getFirst();
    }

    @Test
    void noTypeMapsToNothing() {
        assertNull(TypeInfoUtils.fromReflectType(null));
    }

    @Test
    void wildcardsKeepTheirBoundsButObject() {
        assertEquals(new TypeInfo.WildcardType(null, STRING), argumentOf("lowerBounded"));
        assertEquals(new TypeInfo.WildcardType(NUMBER, null), argumentOf("upperBounded"));
    }

    @Test
    void aGenericArrayCountsItsDimensionsOverATypeVariable() {
        var expected = new TypeInfo.ArrayType(new TypeInfo.TypeVariable("N", List.of(NUMBER)), 2);

        assertEquals(expected, TypeInfoUtils.fromReflectType(declared("genericArray")));
    }

    @Test
    void aTypeVariableDropsItsObjectBound() {
        assertEquals(new TypeInfo.TypeVariable("F", List.of()), TypeInfoUtils.fromReflectType(declared("free")));
    }

    @Test
    void anotherTypeImplementationIsNamedAsAClass() {
        Type custom = new Type() {
            @Override
            public String getTypeName() {
                return "com.acme.Custom";
            }
        };

        assertEquals(new TypeInfo.ClassType(DotName.of("com.acme.Custom")), TypeInfoUtils.fromReflectType(custom));
    }
}
