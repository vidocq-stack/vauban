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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.types.ClassType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed accessors of the index-backed {@link VaubanAnnotationMember} and of the built
 * {@link BuiltAnnotationMember}: each returns its own kind and rejects every other kind.
 */
class AnnotationMemberAccessorsTest {

    private static final DotName ZONE_TYPE = DotName.of(Zone.class.getName());
    private static final String METHOD = "METHOD";

    public enum Zone { TYPE, METHOD }

    private static VaubanAnnotationMember member(AnnotationValue value) {
        return new VaubanAnnotationMember(value, null);
    }

    @Test
    void indexMemberReturnsEachPrimitiveValue() {
        assertTrue(member(new AnnotationValue.BooleanVal(true)).asBoolean());
        assertEquals((byte) 7, member(new AnnotationValue.ByteVal((byte) 7)).asByte());
        assertEquals((short) 8, member(new AnnotationValue.ShortVal((short) 8)).asShort());
        assertEquals(9, member(new AnnotationValue.IntVal(9)).asInt());
        assertEquals(10L, member(new AnnotationValue.LongVal(10L)).asLong());
        assertEquals(1.5f, member(new AnnotationValue.FloatVal(1.5f)).asFloat());
        assertEquals(2.5d, member(new AnnotationValue.DoubleVal(2.5d)).asDouble());
        assertEquals('x', member(new AnnotationValue.CharVal('x')).asChar());
        assertEquals("s", member(new AnnotationValue.StringVal("s")).asString());
    }

    @Test
    void indexMemberReturnsEnumConstantAndEnumClass() throws IOException {
        var lookup = LangModelTest.buildLookup(Zone.class);
        var enumMember = new VaubanAnnotationMember(new AnnotationValue.EnumVal(ZONE_TYPE, METHOD), lookup);

        assertEquals(Zone.METHOD, enumMember.asEnum(Zone.class));
        assertEquals(METHOD, enumMember.asEnumConstant());
        assertEquals(Zone.class.getName(), enumMember.asEnumClass().name());
    }

    @Test
    void indexMemberReturnsClassNestedAnnotationAndArray() {
        var type = member(new AnnotationValue.ClassVal(DotName.of("java.lang.String"))).asType();
        assertEquals("java.lang.String", assertInstanceOf(ClassType.class, type).declaration().name());

        var nested = new AnnotationInfo(DotName.of("com.example.Nested"), Map.of());
        assertEquals("com.example.Nested", member(new AnnotationValue.AnnotationVal(nested)).asNestedAnnotation().name());

        List<AnnotationMember> items = member(new AnnotationValue.ArrayVal(
                List.of(new AnnotationValue.IntVal(1), new AnnotationValue.IntVal(2)))).asArray();
        assertEquals(List.of(1, 2), items.stream().map(AnnotationMember::asInt).toList());
    }

    static Stream<Arguments> wrongKindAccessors() {
        return Stream.<Consumer<AnnotationMember>>of(
                AnnotationMember::asBoolean, AnnotationMember::asByte, AnnotationMember::asShort,
                AnnotationMember::asInt, AnnotationMember::asLong, AnnotationMember::asFloat,
                AnnotationMember::asDouble, AnnotationMember::asChar, AnnotationMember::asString,
                m -> m.asEnum(Zone.class), AnnotationMember::asEnumClass, AnnotationMember::asEnumConstant,
                AnnotationMember::asType, AnnotationMember::asNestedAnnotation, AnnotationMember::asArray)
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("wrongKindAccessors")
    void indexMemberRejectsAccessorOfAnotherKind(Consumer<AnnotationMember> accessor) {
        // A nested array is a value of none of the scalar kinds, and of the array kind only once wrapped.
        var arrayMember = member(new AnnotationValue.ArrayVal(List.of()));
        var booleanMember = member(new AnnotationValue.BooleanVal(false));
        var target = isArrayAccessor(accessor) ? booleanMember : arrayMember;
        var error = assertThrows(IllegalStateException.class, () -> accessor.accept(target));
        assertTrue(error.getMessage().startsWith("Not a"), error.getMessage());
    }

    private static boolean isArrayAccessor(Consumer<AnnotationMember> accessor) {
        try {
            accessor.accept(member(new AnnotationValue.ArrayVal(List.of())));
            return true;
        } catch (IllegalStateException _) {
            return false;
        }
    }

    @Test
    void builtMemberReturnsEnumValueAndPrintsItsValue() {
        var enumMember = BuiltAnnotationMember.ofEnum(Zone.class.getName(), METHOD);

        assertEquals(Zone.METHOD, enumMember.asEnum(Zone.class));
        assertEquals("42", BuiltAnnotationMember.ofInt(42).toString());
        assertEquals("text", BuiltAnnotationMember.ofString("text").toString());
    }

    @Test
    void builtMemberRejectsEnumAccessOnAnotherKind() {
        var intMember = BuiltAnnotationMember.ofInt(1);
        var error = assertThrows(IllegalStateException.class, () -> intMember.asEnum(Zone.class));
        assertEquals("Not a ENUM value, but INT", error.getMessage());
    }
}
