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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.api.AnnotationInstanceFactory;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An annotation written as text, such as {@code @Region("eu")} in a configuration file, built into the instance
 * the JDK builds from the same declaration: each member literal takes the type its member declares.
 */
class CoreAnnotationInstanceFactoryTest {

    public enum Zone { EU, US }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Primary {
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Region {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tier {
        int level();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Zoned {
        Zone value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Typed {
        Class<?> value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Scalars {
        boolean flag();

        char letter();

        long big();

        double ratio();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Numbers {
        int i();

        long l();

        short s();

        byte b();

        double d();

        float f();

        char c();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface WithDefault {
        String value() default "x";
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Listed {
        String[] value();
    }

    @Primary
    @Region("eu")
    @Tier(level = 2)
    @Zoned(Zone.EU)
    @Typed(String.class)
    @Scalars(flag = true, letter = 'c', big = 3_000_000_000L, ratio = 0.5)
    @WithDefault
    static class Annotated {
    }

    @Region("say \"hi\"")
    static class Quoted {
    }

    private final AnnotationInstanceFactory factory = new CoreAnnotationInstanceFactory();

    private static <A extends Annotation> A jdk(Class<?> carrier, Class<A> type) {
        return carrier.getAnnotation(type);
    }

    private <A extends Annotation> A built(Class<A> type, Map<String, String> members) {
        return factory.create(type, members, getClass().getClassLoader());
    }

    private static void assertSameAnnotation(Annotation expected, Annotation built) {
        assertEquals(expected, built, "the JDK instance must accept ours");
        assertEquals(built, expected, "ours must accept the JDK instance");
        assertEquals(expected.hashCode(), built.hashCode());
    }

    @Test
    void aMemberLessQualifierEqualsTheJdkInstance() {
        assertSameAnnotation(jdk(Annotated.class, Primary.class), built(Primary.class, Map.of()));
    }

    @Test
    void eachLiteralKindBecomesTheMembersType() {
        assertSameAnnotation(jdk(Annotated.class, Tier.class), built(Tier.class, Map.of("level", "2")));
        assertSameAnnotation(jdk(Annotated.class, Zoned.class),
                built(Zoned.class, Map.of("value", Zone.class.getCanonicalName() + ".EU")));
        assertSameAnnotation(jdk(Annotated.class, Typed.class),
                built(Typed.class, Map.of("value", "java.lang.String.class")));
        assertSameAnnotation(jdk(Annotated.class, Scalars.class), built(Scalars.class,
                Map.of("flag", "true", "letter", "'c'", "big", "3000000000L", "ratio", "0.5")));
    }

    @Test
    void aStringLiteralIsUnquotedAndUnescaped() {
        assertSameAnnotation(jdk(Quoted.class, Region.class), built(Region.class, Map.of("value", "\"say \\\"hi\\\"\"")));
    }

    @Test
    void anEnumConstantMayBeWrittenWithoutItsType() {
        assertEquals(Zone.US, built(Zoned.class, Map.of("value", "US")).value());
    }

    @Test
    void aLeftOutMemberTakesItsDefault() {
        assertEquals("x", built(WithDefault.class, Map.of()).value());
    }

    @Test
    void anUnknownMemberIsRefused() {
        Map<String, String> members = Map.of("value", "\"eu\"", "zone", "\"x\"");

        var failure = assertThrows(IllegalArgumentException.class, () -> built(Region.class, members));

        assertTrue(failure.getMessage().contains("Region") && failure.getMessage().contains("zone"),
                failure.getMessage());
    }

    @Test
    void aLiteralOfTheWrongTypeIsRefused() {
        Map<String, String> members = Map.of("level", "\"two\"");

        var failure = assertThrows(IllegalArgumentException.class, () -> built(Tier.class, members));

        assertTrue(failure.getMessage().contains("Tier") && failure.getMessage().contains("level"),
                failure.getMessage());
    }

    @Test
    void anUnknownEnumConstantOrClassIsRefused() {
        Map<String, String> zone = Map.of("value", "MARS");
        Map<String, String> type = Map.of("value", "com.acme.Missing.class");

        assertThrows(IllegalArgumentException.class, () -> built(Zoned.class, zone));
        assertThrows(IllegalArgumentException.class, () -> built(Typed.class, type));
    }

    @Test
    void aMemberWithoutDefaultLeftOutIsRefused() {
        Map<String, String> none = Map.of();

        var failure = assertThrows(IllegalArgumentException.class, () -> built(Region.class, none));

        assertTrue(failure.getMessage().contains("Region") && failure.getMessage().contains("value"),
                failure.getMessage());
    }

    @Test
    void anArrayMemberIsRefused() {
        Map<String, String> members = Map.of("value", "\"a\"");

        var failure = assertThrows(IllegalArgumentException.class, () -> built(Listed.class, members));

        assertTrue(failure.getMessage().contains("array"), failure.getMessage());
    }

    @Test
    void theProviderIsFoundThroughServiceLoader() {
        var found = AnnotationInstanceFactory.find();

        assertTrue(found.isPresent());
        assertInstanceOf(CoreAnnotationInstanceFactory.class, found.get());
    }

    private Numbers numbers(String i, String l, String sh, String b, String d, String f, String c) {
        return built(Numbers.class, Map.of("i", i, "l", l, "s", sh, "b", b, "d", d, "f", f, "c", c));
    }

    @Test
    void integerLiteralsAreReadAsJavaReadsThem() {
        var hex = numbers("0x1F", "0xFFFFFFFFFFFFFFFFL", "-1", "-128", "0.5", "1.5f", "'A'");
        assertEquals(31, hex.i());
        assertEquals(-1L, hex.l());
        assertEquals((short) -1, hex.s());
        assertEquals((byte) -128, hex.b());
        assertEquals(0.5, hex.d());
        assertEquals(1.5f, hex.f());
        assertEquals('A', hex.c());

        var edges = numbers("0x80000000", "0b101L", "010", "0x7F", "010", "1e3f", "'\\n'");
        assertEquals(Integer.MIN_VALUE, edges.i());
        assertEquals(5L, edges.l());
        assertEquals((short) 8, edges.s());
        assertEquals((byte) 127, edges.b());
        assertEquals(8.0, edges.d(), "010 is an octal int literal, widened to a double");
        assertEquals(1000f, edges.f());
        assertEquals('\n', edges.c());

        var more = numbers("-0b11", "-9223372036854775808L", "1_000", "0", "1e3", "0x1p3f", "'\\''");
        assertEquals(-3, more.i());
        assertEquals(Long.MIN_VALUE, more.l());
        assertEquals((short) 1000, more.s());
        assertEquals(1000.0, more.d());
        assertEquals(8f, more.f());
        assertEquals('\'', more.c());
    }

    @Test
    void aNumberOutOfTheMembersRangeIsRefused() {
        Map<String, String> tooBig = Map.of("level", "0x1_0000_0000");

        assertThrows(IllegalArgumentException.class, () -> built(Tier.class, tooBig));
    }
}
