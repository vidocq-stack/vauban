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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Type-level facts needed by BOTH code renderers (bytecode and source) belong on {@link TypeRef},
 * the shared IR — not duplicated as parallel switches inside each renderer (the wrapper-class and
 * unbox-accessor tables used to exist twice, in {@code InterceptedEmitter} and
 * {@code InterceptedSourceRenderer}).
 */
@DisplayName("TypeRef — shared type facts for both renderers")
class TypeRefTest {

    @Test
    @DisplayName("wrapperBinaryName maps every non-void primitive to its java.lang wrapper")
    void wrapperBinaryNames() {
        assertEquals("java.lang.Boolean", TypeRef.ofPrimitive(TypeRef.Primitive.BOOLEAN).wrapperBinaryName());
        assertEquals("java.lang.Byte", TypeRef.ofPrimitive(TypeRef.Primitive.BYTE).wrapperBinaryName());
        assertEquals("java.lang.Character", TypeRef.ofPrimitive(TypeRef.Primitive.CHAR).wrapperBinaryName());
        assertEquals("java.lang.Short", TypeRef.ofPrimitive(TypeRef.Primitive.SHORT).wrapperBinaryName());
        assertEquals("java.lang.Integer", TypeRef.ofPrimitive(TypeRef.Primitive.INT).wrapperBinaryName());
        assertEquals("java.lang.Long", TypeRef.ofPrimitive(TypeRef.Primitive.LONG).wrapperBinaryName());
        assertEquals("java.lang.Float", TypeRef.ofPrimitive(TypeRef.Primitive.FLOAT).wrapperBinaryName());
        assertEquals("java.lang.Double", TypeRef.ofPrimitive(TypeRef.Primitive.DOUBLE).wrapperBinaryName());
    }

    @Test
    @DisplayName("wrapperBinaryName rejects void and non-primitives (same contract as wrapperClassDesc)")
    void wrapperBinaryNameRejectsNonPrimitives() {
        assertThrows(IllegalStateException.class, () -> TypeRef.ofVoid().wrapperBinaryName());
        assertThrows(IllegalStateException.class, () -> TypeRef.ofReference("java.lang.String").wrapperBinaryName());
        // int[] is a reference type, not a primitive (VAU-INT-002)
        assertThrows(IllegalStateException.class,
                () -> TypeRef.ofPrimitive(TypeRef.Primitive.INT, 1).wrapperBinaryName());
    }

    @Test
    @DisplayName("unboxAccessorName returns the <prim>Value() accessor used to unbox a wrapper")
    void unboxAccessorNames() {
        assertEquals("booleanValue", TypeRef.ofPrimitive(TypeRef.Primitive.BOOLEAN).unboxAccessorName());
        assertEquals("charValue", TypeRef.ofPrimitive(TypeRef.Primitive.CHAR).unboxAccessorName());
        assertEquals("intValue", TypeRef.ofPrimitive(TypeRef.Primitive.INT).unboxAccessorName());
        assertEquals("doubleValue", TypeRef.ofPrimitive(TypeRef.Primitive.DOUBLE).unboxAccessorName());
    }

    @Test
    @DisplayName("sourceName renders Java source syntax: primitives, arrays, nested classes ($ → .)")
    void sourceNames() {
        assertEquals("int", TypeRef.ofPrimitive(TypeRef.Primitive.INT).sourceName());
        assertEquals("int[]", TypeRef.ofPrimitive(TypeRef.Primitive.INT, 1).sourceName());
        assertEquals("long[][]", TypeRef.ofPrimitive(TypeRef.Primitive.LONG, 2).sourceName());
        assertEquals("void", TypeRef.ofVoid().sourceName());
        assertEquals("java.lang.String", TypeRef.ofReference("java.lang.String").sourceName());
        assertEquals("java.lang.String[]", TypeRef.ofReference("java.lang.String", 1).sourceName());
        // nested class: binary name uses '$', source syntax uses '.' (VAU-PRX-003 family)
        assertEquals("com.example.Outer.Inner", TypeRef.ofReference("com.example.Outer$Inner").sourceName());
    }
}
