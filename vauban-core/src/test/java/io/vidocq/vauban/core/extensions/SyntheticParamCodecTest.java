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

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every value CDI 4.1 Lite lets an extension pass to {@code withParam} reaches the creator: a build
 * compatible extension run by the processor writes it to the synthetic metadata, and the container
 * reads it back at boot (BUG-20261008-02).
 */
@DisplayName("SyntheticParamCodec - every withParam value survives build time")
class SyntheticParamCodecTest {

    enum Color { RED, GREEN, BLUE { @Override public String toString() { return "blue"; } } }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Tag {
        String value();
        int[] weights() default {};
        Class<?> type() default Object.class;
        Color color() default Color.RED;
        Named nested() default @Named;
        char letter() default 'a';
        float ratio() default 1f;
        byte small() default 0;
        short medium() default 0;
        long large() default 0L;
        double precise() default 0d;
        boolean flag() default false;
    }

    @Tag(value = "a:b,c\nd=é", weights = {3, 1, 2}, type = String[].class, color = Color.BLUE,
            nested = @Named("inner"), letter = ':', ratio = 0.1f, small = -1, medium = 300, large = 1L << 40,
            precise = Math.PI, flag = true)
    static final class Tagged {
    }

    @Named("other")
    static final class NamedOther {
    }

    /** What the container reads back from what the processor wrote, through a properties file. */
    private static Object roundTrip(Object value) {
        String encoded = SyntheticParamCodec.encode(value);
        var props = new Properties();
        props.setProperty("p", encoded);
        try {
            var out = new java.io.StringWriter();
            props.store(out, null);
            var in = new Properties();
            in.load(new java.io.StringReader(out.toString()));
            return SyntheticParamCodec.decode(in.getProperty("p"), SyntheticParamCodecTest.class.getClassLoader());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("scalars keep their type and value")
    void scalars() {
        assertEquals("a:b,c\nd", roundTrip("a:b,c\nd"));
        assertEquals(true, roundTrip(true));
        assertEquals(-7, roundTrip(-7));
        assertEquals(Long.MIN_VALUE, roundTrip(Long.MIN_VALUE));
        assertEquals(0.1d, roundTrip(0.1d));
        assertEquals(String.class, roundTrip(String.class));
        assertSame(Color.GREEN, roundTrip(Color.GREEN));
        assertSame(Color.BLUE, roundTrip(Color.BLUE), "a constant with a body is a subclass of its enum");
    }

    @Test
    @DisplayName("arrays of every supported component type, empty ones included")
    void arrays() {
        assertArrayEquals(new boolean[] {true, false}, (boolean[]) roundTrip(new boolean[] {true, false}));
        assertArrayEquals(new int[] {1, -2, 3}, (int[]) roundTrip(new int[] {1, -2, 3}));
        assertArrayEquals(new long[] {Long.MAX_VALUE}, (long[]) roundTrip(new long[] {Long.MAX_VALUE}));
        assertArrayEquals(new double[] {1.5, -0.0}, (double[]) roundTrip(new double[] {1.5, -0.0}));
        assertArrayEquals(new String[] {"a,b", "", "c:d"}, (String[]) roundTrip(new String[] {"a,b", "", "c:d"}));
        assertArrayEquals(new Class<?>[] {String.class, int.class, Tagged.class, String[].class},
                (Class<?>[]) roundTrip(new Class<?>[] {String.class, int.class, Tagged.class, String[].class}));
        assertArrayEquals(new Color[] {Color.BLUE, Color.RED}, (Color[]) roundTrip(new Color[] {Color.BLUE, Color.RED}));
        assertArrayEquals(new Enum<?>[] {Color.RED}, (Enum<?>[]) roundTrip(new Enum<?>[] {Color.RED}));
        assertEquals(0, ((String[]) roundTrip(new String[0])).length);
        assertEquals(0, ((int[]) roundTrip(new int[0])).length);
        assertEquals(Color[].class, roundTrip(new Color[0]).getClass());
    }

    @Test
    @DisplayName("an annotation keeps every member, nested annotations and arrays included")
    void annotation() {
        var tag = Tagged.class.getAnnotation(Tag.class);
        var read = (Tag) roundTrip(tag);
        assertEquals(tag, read);
        assertEquals(tag.hashCode(), read.hashCode());
        assertEquals("inner", read.nested().value());
        assertEquals(':', read.letter());
    }

    @Test
    @DisplayName("an array of annotations")
    void annotationArray() {
        var values = new Annotation[] {Tagged.class.getAnnotation(Tag.class), NamedOther.class.getAnnotation(Named.class)};
        assertArrayEquals(values, (Annotation[]) roundTrip(values));
    }

    public static final class Greeter {
        public String greet(String name, int times) {
            return name.repeat(times);
        }
    }

    @Test
    @DisplayName("an invoker keeps its method and its lookups")
    void invoker() throws Exception {
        var method = Greeter.class.getMethod("greet", String.class, int.class);
        var invoker = new VaubanInvoker(method, Greeter.class, true, Set.of(1));
        var read = (VaubanInvoker) roundTrip(invoker);
        assertEquals(method, read.method());
        assertEquals(Greeter.class, read.beanClass());
        assertTrue(read.instanceLookup());
        assertEquals(Set.of(1), read.argumentLookups());

        var array = (InvokerInfo[]) roundTrip(new InvokerInfo[] {invoker});
        assertEquals(method, ((VaubanInvoker) array[0]).method());
    }

    @Test
    @DisplayName("a value of no supported type is refused, never dropped")
    void unsupported() {
        var error = assertThrows(IllegalArgumentException.class, () -> SyntheticParamCodec.encode(new Object()));
        assertTrue(error.getMessage().contains("java.lang.Object"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SyntheticParamCodec.encode(new Object[] {"x"}));
    }

    @Test
    @DisplayName("the metadata writer names the bean and the param it refuses")
    void writerRefusesWithContext() {
        var builder = new VaubanSyntheticBeanBuilder<>(Greeter.class);
        builder.getParams().put("opaque", new Object());
        var error = assertThrows(IllegalArgumentException.class, () -> SyntheticMetadataSerializer.write(
                java.util.List.of(builder), java.util.List.of(), new java.io.ByteArrayOutputStream()));
        assertTrue(error.getMessage().contains(Greeter.class.getName()), error.getMessage());
        assertTrue(error.getMessage().contains("'opaque'"), error.getMessage());
    }

    @Test
    @DisplayName("a class the run time cannot load is reported, never dropped")
    void missingClass() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SyntheticParamCodec.decode("C:com.example.Missing", getClass().getClassLoader()));
        assertTrue(error.getMessage().contains("com.example.Missing"), error.getMessage());
    }

    @Test
    @DisplayName("what earlier versions wrote still reads")
    void earlierFormat() {
        var loader = getClass().getClassLoader();
        assertEquals("hello", SyntheticParamCodec.decode("S:hello", loader));
        assertEquals(42, SyntheticParamCodec.decode("I:42", loader));
        assertEquals(String.class, SyntheticParamCodec.decode("C:java.lang.String", loader));
        assertSame(Color.GREEN, SyntheticParamCodec.decode("E:" + Color.class.getName() + ":GREEN", loader));
    }
}
