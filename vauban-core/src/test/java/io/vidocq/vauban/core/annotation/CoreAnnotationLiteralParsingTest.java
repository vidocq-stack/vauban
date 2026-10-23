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

import io.vidocq.vauban.api.AnnotationInstanceFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Each literal grammar {@link CoreAnnotationInstanceFactory} reads — strings, chars, booleans, class literals,
 * enum constants, every integer radix and floating form — and the exact refusal of each malformed literal.
 */
class CoreAnnotationLiteralParsingTest {

    private static final String TEXT = "text";
    private static final String LETTER = "letter";
    private static final String RATIO = "ratio";
    private static final String SCALE = "scale";
    private static final String SMALL = "small";
    private static final String TINY = "tiny";
    private static final String BIG = "big";
    private static final String TYPE = "type";
    private static final String ZONE = "zone";

    public enum Zone { EU, US }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Literals {
        String text() default "";

        boolean flag() default false;

        char letter() default ' ';

        double ratio() default 0;

        float scale() default 0;

        long big() default 0;

        short small() default 0;

        byte tiny() default 0;

        Class<?> type() default Object.class;

        Zone zone() default Zone.US;

        Marker marker() default @Marker;
    }

    private final AnnotationInstanceFactory factory = new CoreAnnotationInstanceFactory();

    private Literals built(String member, String literal) {
        return factory.create(Literals.class, Map.of(member, literal), getClass().getClassLoader());
    }

    static Stream<Arguments> acceptedLiterals() {
        Function<Literals, Object> text = Literals::text;
        Function<Literals, Object> letter = Literals::letter;
        Function<Literals, Object> ratio = Literals::ratio;
        Function<Literals, Object> scale = Literals::scale;
        Function<Literals, Object> big = Literals::big;
        Function<Literals, Object> small = Literals::small;
        Function<Literals, Object> tiny = Literals::tiny;
        return Stream.of(
                Arguments.of(TEXT, "\"a\\tb\\rc\\bd\\fe\\0\"", text, "a\tb\rc\bd\fe\0"),
                Arguments.of(TEXT, "\"\\\\ \\' \\n\"", text, "\\ ' \n"),
                Arguments.of("flag", "false", (Function<Literals, Object>) Literals::flag, false),
                Arguments.of(LETTER, "'\\t'", letter, '\t'),
                Arguments.of(TYPE, "java.util.Map.Entry.class", (Function<Literals, Object>) Literals::type,
                        Map.Entry.class),
                Arguments.of(ZONE, "Zone.EU", (Function<Literals, Object>) Literals::zone, Zone.EU),
                Arguments.of(ZONE, Zone.class.getName() + ".EU", (Function<Literals, Object>) Literals::zone, Zone.EU),
                // Floating members: an integer literal of any radix is widened; a floating literal is parsed.
                Arguments.of(RATIO, "0x10", ratio, 16.0),
                Arguments.of(RATIO, "0X10", ratio, 16.0),
                Arguments.of(RATIO, "-0x10", ratio, -16.0),
                Arguments.of(RATIO, "-0X10", ratio, -16.0),
                Arguments.of(RATIO, "0x1p3", ratio, 8.0),
                Arguments.of(RATIO, "0x1P3", ratio, 8.0),
                Arguments.of(RATIO, "0x1.8p1", ratio, 3.0),
                Arguments.of(RATIO, "1e3", ratio, 1000.0),
                Arguments.of(RATIO, "1E3", ratio, 1000.0),
                Arguments.of(RATIO, "2d", ratio, 2.0),
                Arguments.of(RATIO, "2D", ratio, 2.0),
                Arguments.of(RATIO, "0b101", ratio, 5.0),
                Arguments.of(RATIO, "0B101", ratio, 5.0),
                Arguments.of(SCALE, "3", scale, 3.0f),
                Arguments.of(SCALE, "1.5f", scale, 1.5f),
                Arguments.of(SCALE, "2F", scale, 2.0f),
                Arguments.of(SCALE, "2f", scale, 2.0f),
                Arguments.of(BIG, "-0x10L", big, -16L),
                Arguments.of(BIG, "0x10", big, 16L),
                Arguments.of(SMALL, "-32768", small, Short.MIN_VALUE),
                Arguments.of(TINY, "127", tiny, Byte.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("acceptedLiterals")
    void aLiteralBecomesTheValueItsMemberDeclares(String member, String literal, Function<Literals, Object> read,
            Object expected) {
        assertEquals(expected, read.apply(built(member, literal)));
    }

    static Stream<Arguments> refusedLiterals() {
        return Stream.of(
                Arguments.of("marker", "@Marker", "marker is an annotation, which a literal cannot be written for"),
                Arguments.of(TEXT, "\"", "text takes a String, not \""),
                Arguments.of(TEXT, "eu\"", "text takes a String, not eu\""),
                Arguments.of(TEXT, "\"eu", "text takes a String, not \"eu"),
                Arguments.of(TEXT, "\"a\\\"", "text takes a String, not \"a\\\""),
                Arguments.of(TEXT, "\"\\q\"", "text takes a String, not \"\\q\""),
                Arguments.of("flag", "yes", "flag takes a boolean, not yes"),
                Arguments.of(LETTER, "''", "letter takes a char, not ''"),
                Arguments.of(LETTER, "ab'", "letter takes a char, not ab'"),
                Arguments.of(LETTER, "'ab", "letter takes a char, not 'ab"),
                Arguments.of(LETTER, "'ab'", "letter takes a char, not 'ab'"),
                Arguments.of(TYPE, "java.lang.String", "type takes a Class, not java.lang.String"),
                Arguments.of(ZONE, "Region.EU", "zone takes a Zone, not Region.EU"),
                Arguments.of(RATIO, "0x1.8", "ratio takes a double, not 0x1.8"),
                Arguments.of(SMALL, "40000", "small takes a short, not 40000"),
                Arguments.of(SMALL, "-40000", "small takes a short, not -40000"),
                Arguments.of(TINY, "300", "tiny takes a byte, not 300"),
                Arguments.of(TINY, "-300", "tiny takes a byte, not -300"),
                Arguments.of(BIG, "", "big takes a long, not "));
    }

    @ParameterizedTest
    @MethodSource("refusedLiterals")
    void aMalformedLiteralIsRefusedWithTheMemberAndTheLiteral(String member, String literal, String reason) {
        var failure = assertThrows(IllegalArgumentException.class, () -> built(member, literal));
        assertEquals("@Literals: " + reason, failure.getMessage());
    }
}
