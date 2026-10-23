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
package io.vidocq.vauban.core.annotation;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link AnnotationInstances} turns an index value into the Java value the member declares — the declared
 * type decides, whatever width or shape the index recorded — and the fallbacks of its lookups and equality.
 */
class AnnotationInstancesConversionTest {

    private static final ClassLoader LOADER = AnnotationInstancesConversionTest.class.getClassLoader();
    private static final String EU = "EU";

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Label {
        String value();
    }

    private static Object convert(AnnotationValue value, Class<?> target) {
        return AnnotationInstances.javaValue(value, target, LOADER);
    }

    static Stream<Arguments> numberWidening() {
        var seven = new AnnotationValue.IntVal(7);
        return Stream.of(
                Arguments.of(new AnnotationValue.ByteVal((byte) 7), Byte.class, (byte) 7),
                Arguments.of(new AnnotationValue.ShortVal((short) 7), Short.class, (short) 7),
                Arguments.of(new AnnotationValue.LongVal(7L), Integer.class, 7),
                Arguments.of(new AnnotationValue.FloatVal(7f), Long.class, 7L),
                Arguments.of(new AnnotationValue.DoubleVal(7d), Float.class, 7f),
                Arguments.of(seven, Double.class, 7d),
                Arguments.of(seven, byte.class, (byte) 7),
                Arguments.of(seven, short.class, (short) 7),
                Arguments.of(seven, long.class, 7L),
                Arguments.of(seven, float.class, 7f),
                Arguments.of(new AnnotationValue.IntVal('A'), char.class, 'A'),
                Arguments.of(new AnnotationValue.IntVal('B'), Character.class, 'B'),
                Arguments.of(seven, Number.class, 7));
    }

    @ParameterizedTest
    @MethodSource("numberWidening")
    void aNumberTakesTheTypeItsMemberDeclares(AnnotationValue value, Class<?> target, Object expected) {
        assertEquals(expected, convert(value, target));
    }

    @Test
    void scalarValuesKeepTheirKind() {
        assertEquals(Boolean.TRUE, convert(new AnnotationValue.BooleanVal(true), boolean.class));
        assertEquals('c', convert(new AnnotationValue.CharVal('c'), char.class));
        assertEquals(String.class, convert(new AnnotationValue.ClassVal(DotName.of("java.lang.String")), Class.class));
    }

    @Test
    void aStringNamesAClassOrAnEnumConstantWhenTheMemberDeclaresOne() {
        assertEquals(List.class, convert(new AnnotationValue.StringVal("java.util.List"), Class.class));
        assertEquals(ElementType.TYPE, convert(new AnnotationValue.StringVal("TYPE"), ElementType.class));
        assertEquals("TYPE", convert(new AnnotationValue.StringVal("TYPE"), String.class));
    }

    @Test
    void aSingleValueForAnArrayMemberBecomesAOneItemArray() {
        assertArrayEquals(new int[] {3}, (int[]) convert(new AnnotationValue.IntVal(3), int[].class));
    }

    @Test
    void anArrayForAScalarMemberKeepsItsFirstItemOrNothing() {
        var two = new AnnotationValue.ArrayVal(List.of(new AnnotationValue.IntVal(1), new AnnotationValue.IntVal(2)));

        assertEquals(1, convert(two, int.class));
        assertNull(convert(new AnnotationValue.ArrayVal(List.of()), int.class));
    }

    @Test
    void anEnumConstantForANonEnumMemberIsRefused() {
        var constant = new AnnotationValue.EnumVal(DotName.of(ElementType.class.getName()), EU);

        var failure = assertThrows(IllegalStateException.class, () -> convert(constant, String.class));

        assertEquals("java.lang.String is not an enum type, so it has no constant EU", failure.getMessage());
    }

    @Test
    void aNestedAnnotationForANonAnnotationMemberIsRefused() {
        var nested = new AnnotationValue.AnnotationVal(new AnnotationInfo(DotName.of(Label.class.getName()), Map.of()));

        var failure = assertThrows(IllegalStateException.class, () -> convert(nested, String.class));

        assertEquals("java.lang.String is not an annotation type", failure.getMessage());
    }

    @Test
    void typeNamedFindsOnlyAnnotationTypes() {
        assertEquals(Label.class, AnnotationInstances.typeNamed(Label.class.getName(), LOADER).orElseThrow());
        assertTrue(AnnotationInstances.typeNamed("com.acme.Missing", LOADER).isEmpty());
        assertTrue(AnnotationInstances.typeNamed("java.lang.String", LOADER).isEmpty());
    }

    @Test
    void equalityAsksTheOtherSideWhenItsMembersCannotBeRead() {
        var built = AnnotationInstances.create(Label.class,
                new AnnotationInfo(DotName.of(Label.class.getName()), Map.of("value", new AnnotationValue.StringVal(EU))),
                LOADER);

        assertTrue(built.equals(new UnreadableLabel(true)));
        assertFalse(built.equals(new UnreadableLabel(false)));
    }

    @Test
    void aMemberThatThrowsIsLeftOutOfTheIndexForm() {
        var info = AnnotationValues.toAnnotationInfo(new UnreadableLabel(true));

        assertEquals(Label.class.getName(), info.name().value());
        assertTrue(info.members().isEmpty());
    }

    /** A hand-written {@link Label} whose member throws, so only its own {@code equals} can answer. */
    @SuppressWarnings("ClassExplicitlyAnnotation")
    private static final class UnreadableLabel implements Label {
        private final boolean answer;

        UnreadableLabel(boolean answer) {
            this.answer = answer;
        }

        @Override
        public String value() {
            throw new UnsupportedOperationException("unreadable");
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Label.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Label && answer;
        }

        @Override
        public int hashCode() {
            return Boolean.hashCode(answer);
        }
    }
}
