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

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Value semantics of the lang model type records (type variables, wildcards, arrays) and their mapping from
 * the indexer model.
 */
class LangModelTypeValueSemanticsTest {

    private static final String NAME_T = "T";
    private static final Type STRING = new VaubanClassType(DotName.of("java.lang.String"), null);
    private static final Type NUMBER = new VaubanClassType(DotName.of("java.lang.Number"), null);

    @Test
    void typeVariableCopiesItsBoundsAndRequiresAName() {
        var bounds = new ArrayList<Type>(List.of(NUMBER));
        var variable = new VaubanTypeVariable(NAME_T, bounds);
        bounds.add(STRING);

        assertEquals(List.of(NUMBER), variable.bounds());
        List<Type> noBounds = List.of();
        assertThrows(NullPointerException.class, () -> new VaubanTypeVariable(null, noBounds));
    }

    @Test
    void typeVariableEqualityComparesNameAndBounds() {
        var variable = new VaubanTypeVariable(NAME_T, List.of(NUMBER));

        assertEquals(variable, variable);
        assertEquals(new VaubanTypeVariable(NAME_T, List.of(NUMBER)), variable);
        assertEquals(new VaubanTypeVariable(NAME_T, List.of(NUMBER)).hashCode(), variable.hashCode());
        assertNotEquals(new VaubanTypeVariable("U", List.of(NUMBER)), variable);
        assertNotEquals(new VaubanTypeVariable(NAME_T, List.of(STRING)), variable);
        Object aType = NUMBER;
        assertNotEquals(variable, aType);
    }

    @Test
    void wildcardEqualityComparesBothBounds() {
        var upper = new VaubanWildcardType(NUMBER, null);

        assertEquals(upper, upper);
        assertEquals(new VaubanWildcardType(NUMBER, null), upper);
        assertNotEquals(new VaubanWildcardType(STRING, null), upper);
        assertNotEquals(new VaubanWildcardType(NUMBER, STRING), upper);
        Object aType = NUMBER;
        assertNotEquals(upper, aType);
        assertEquals(NUMBER, upper.upperBound());
    }

    @Test
    void arrayEqualityComparesComponentType() {
        var array = new VaubanArrayType(STRING);

        assertEquals(array, array);
        assertEquals(new VaubanArrayType(STRING), array);
        assertNotEquals(new VaubanArrayType(NUMBER), array);
        Object aType = STRING;
        assertNotEquals(array, aType);
    }

    @Test
    void mapsTypeVariableWithItsBounds() {
        var mapped = TypeMapper.map(new TypeInfo.TypeVariable(NAME_T,
                List.of(new TypeInfo.ClassType(DotName.of("java.lang.Number")))), null);

        var variable = assertInstanceOf(VaubanTypeVariable.class, mapped);
        assertEquals(NAME_T, variable.name());
        assertEquals("java.lang.Number", assertInstanceOf(VaubanClassType.class, variable.bounds().getFirst()).toString());
    }

    @Test
    void mapsWildcardsWithAndWithoutBounds() {
        var number = new TypeInfo.ClassType(DotName.of("java.lang.Number"));

        var upper = assertInstanceOf(VaubanWildcardType.class, TypeMapper.map(new TypeInfo.WildcardType(number, null), null));
        var lower = assertInstanceOf(VaubanWildcardType.class, TypeMapper.map(new TypeInfo.WildcardType(null, number), null));

        assertEquals("? extends java.lang.Number", upper.toString());
        assertNull(upper.lowerBound());
        assertEquals("? super java.lang.Number", lower.toString());
        assertNull(lower.upperBound());
    }
}
