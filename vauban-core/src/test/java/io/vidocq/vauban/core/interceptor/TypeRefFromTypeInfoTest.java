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
package io.vidocq.vauban.core.interceptor;

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link TypeRef#fromTypeInfo} erases index types as bytecode descriptors do. */
class TypeRefFromTypeInfoTest {

    private static final String STRING = "java.lang.String";

    @ParameterizedTest
    @EnumSource(TypeInfo.PrimitiveType.Kind.class)
    void aPrimitiveKeepsItsKind(TypeInfo.PrimitiveType.Kind kind) {
        assertEquals(TypeRef.ofPrimitive(TypeRef.Primitive.valueOf(kind.name())),
                TypeRef.fromTypeInfo(new TypeInfo.PrimitiveType(kind)));
    }

    @Test
    void anArrayAddsItsDimensionsToItsComponent() {
        var ints = new TypeInfo.ArrayType(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT), 2);
        var strings = new TypeInfo.ArrayType(new TypeInfo.ClassType(DotName.of(STRING)), 1);

        assertEquals(TypeRef.ofPrimitive(TypeRef.Primitive.INT, 2), TypeRef.fromTypeInfo(ints));
        assertEquals(TypeRef.ofReference(STRING, 1), TypeRef.fromTypeInfo(strings));
    }

    @Test
    void aParameterizedTypeErasesToItsRawType() {
        var listOfString = new TypeInfo.ParameterizedType(DotName.of("java.util.List"),
                List.of(new TypeInfo.ClassType(DotName.of(STRING))));

        assertEquals(TypeRef.ofReference("java.util.List"), TypeRef.fromTypeInfo(listOfString));
    }
}
