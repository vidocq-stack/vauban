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
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class the build generates for an invoker calls the method by name, the arguments a primitive parameter takes by
 * widening widened first, and the metadata names it with what each looked-up parameter is (BUG-20261118-37).
 */
@DisplayName("BUG-20261118-37 - the generated invoker class")
class GeneratedInvokerTest {

    /** The methods under test. */
    @SuppressWarnings({"unused", "java:S1172"}) // the parameters are the shapes under test
    public static class Target {
        public String widened(short s, int i, long l, float f, double d) {
            return s + " " + i + " " + l + " " + f + " " + d;
        }

        public String letter(char c, byte b, boolean flag) {
            return c + " " + b + " " + flag;
        }

        public static float half(float value) {
            return value / 2;
        }

        public String lookedUp(List<String> texts, @Named("x") String named) {
            return texts + named;
        }

        /** {@link #lookedUp} again, for a test of its own: its generated class has a name of its own. */
        public String lookedUpAgain(List<String> texts, @Named("x") String named) {
            return texts + named;
        }

        private String hidden() {
            return "hidden";
        }
    }

    /** A class that is not public, whose method is not public either. */
    static class Base {
        String local() {
            return "local";
        }
    }

    /** A bean class of the package of {@link Base}, which inherits its method. */
    public static class Child extends Base {
    }

    @Test
    @DisplayName("a primitive parameter takes its own wrapper, and the narrower ones by widening")
    void primitiveArgumentsAreWidened() throws Exception {
        var widened = generated("widened");
        var letter = generated("letter");
        var half = generated("half");
        var target = new Target();
        assertAll(
                () -> assertEquals("1 2 3 4.0 5.0", widened.invoke(target, new Object[] {(short) 1, 2, 3L, 4f, 5d})),
                () -> assertEquals("1 2 3 4.0 5.0", widened.invoke(target,
                        new Object[] {(byte) 1, (short) 2, 3, 4L, 5f}), "each widened by one step"),
                () -> assertEquals("1 97 97 97.0 97.0", widened.invoke(target, new Object[] {(byte) 1, 'a', 'a', 'a',
                        'a'}), "a char to int, long, float and double"),
                () -> assertThrows(ClassCastException.class, () -> widened.invoke(target,
                        new Object[] {'a', 2, 3L, 4f, 5d}), "a char is no short"),
                () -> assertThrows(ClassCastException.class, () -> widened.invoke(target,
                        new Object[] {(short) 1, 2L, 3L, 4f, 5d}), "a long is no int"),
                () -> assertEquals("a 1 true", letter.invoke(target, new Object[] {'a', (byte) 1, true})),
                () -> assertThrows(ClassCastException.class, () -> letter.invoke(target,
                        new Object[] {(byte) 1, (byte) 1, true}), "a byte is no char"),
                () -> assertThrows(NullPointerException.class, () -> letter.invoke(target,
                        new Object[] {null, (byte) 1, true}), "null for a primitive"),
                () -> assertEquals(1.5f, half.invoke(null, new Object[] {3})),
                () -> assertNull(widened.method(), "no method resolved"));
    }

    /**
     * A {@code G:} record with a generated class and an empty type frame for a looked-up parameter, which the writer
     * leaves empty when the notation cannot write the type: the start reads the type from the method, which it
     * resolves for that, and still calls through the generated class. It failed with a {@code NullPointerException}
     * (BUG-20261118-55, review round 5).
     */
    @Test
    @DisplayName("an empty type frame beside a generated class takes the type of the method")
    void anEmptyTypeFrameBesideAGeneratedClassTakesTheMethodsType() throws Exception {
        var method = method("lookedUpAgain");
        var described = new InvokerTarget.Described(InvokerTarget.of(Target.class.getName(), method), false,
                Set.of(0, 1), InvokerShape.of(classInfo(), method));
        var bean = new VaubanSyntheticBeanBuilder<>(String.class);
        bean.withParam("invoker", described);
        var classes = InvokerClasses.of(List.of(bean), List.of(), Target.class.getName()::equals);
        var name = classes.classes().keySet().iterator().next();
        MethodHandles.lookup().defineClass(classes.classes().get(name));
        var encoded = classes.<String, RuntimeException>writing(() -> SyntheticParamCodec.encode(described));

        var read = (VaubanInvoker) SyntheticParamCodec.decode(withoutTheFirstType(encoded),
                getClass().getClassLoader());
        assertEquals(name, read.generatedClass().getName(), "still through the generated class");
        var texts = assertInstanceOfParameterized(read.lookupPoint(0).type());
        assertEquals(List.class, texts.getRawType());
        assertEquals(String.class, texts.getActualTypeArguments()[0]);
        assertEquals(List.of("x"), read.lookupPoint(1).annotations().stream()
                .map(annotation -> ((Named) annotation).value()).toList());
    }

    /** {@code encoded}, a {@code G:} record, with the type frame of its first looked-up parameter emptied. */
    private static String withoutTheFirstType(String encoded) {
        var top = frames(encoded.substring(2));
        var points = frames(top.get(2));
        points.set(1, "");
        var out = new StringBuilder("G:").append(framed(top.get(0))).append(framed(top.get(1)));
        out.append(framed(String.join("", points.stream().map(GeneratedInvokerTest::framed).toList())));
        top.subList(3, top.size()).forEach(rest -> out.append(framed(rest)));
        return out.toString();
    }

    /** The frames {@code value} holds, each written as its length, a colon and itself. */
    private static List<String> frames(String value) {
        var frames = new java.util.ArrayList<String>();
        for (int at = 0; at < value.length(); ) {
            var colon = value.indexOf(':', at);
            var length = Integer.parseInt(value.substring(at, colon));
            frames.add(value.substring(colon + 1, colon + 1 + length));
            at = colon + 1 + length;
        }
        return frames;
    }

    private static String framed(String value) {
        return value.length() + ":" + value;
    }

    @Test
    @DisplayName("the metadata names the generated class and each looked-up parameter, and the start reads them")
    void theMetadataNamesTheGeneratedClass() throws Exception {
        var method = method("lookedUp");
        var described = new InvokerTarget.Described(InvokerTarget.of(Target.class.getName(), method), false,
                Set.of(0, 1), InvokerShape.of(classInfo(), method));
        var bean = new VaubanSyntheticBeanBuilder<>(String.class);
        bean.withParam("invoker", described).withParam("invokers", new InvokerInfo[] {described});
        var classes = InvokerClasses.of(List.of(bean), List.of(), Target.class.getName()::equals);
        assertEquals(1, classes.classes().size(), "one class for the same method");
        var name = classes.classes().keySet().iterator().next();
        MethodHandles.lookup().defineClass(classes.classes().get(name));

        var encoded = classes.<String, RuntimeException>writing(() -> SyntheticParamCodec.encode(described));
        assertTrue(encoded.startsWith("G:"), encoded);
        assertTrue(SyntheticParamCodec.encode(described).startsWith("V:"), "outside the writing of the metadata");
        var read = (VaubanInvoker) SyntheticParamCodec.decode(encoded, getClass().getClassLoader());
        assertEquals(name, read.generatedClass().getName());
        assertEquals(Set.of(0, 1), read.argumentLookups());
        var texts = assertInstanceOfParameterized(read.lookupPoint(0).type());
        assertEquals(List.class, texts.getRawType());
        assertEquals(String.class, texts.getActualTypeArguments()[0]);
        assertEquals(List.of("x"), read.lookupPoint(1).annotations().stream()
                .map(annotation -> ((Named) annotation).value()).toList());
    }

    @Test
    @DisplayName("an invoker of a private method, or of a class the build does not write, keeps its names")
    void noClassForAPrivateMethodOrAClassOfAnotherBuild() throws IOException {
        var hidden = method("hidden");
        var shape = InvokerShape.of(classInfo(), hidden);
        assertFalse(shape.direct(), "a private method");
        var described = new InvokerTarget.Described(InvokerTarget.of(Target.class.getName(), hidden), false, Set.of(),
                shape);
        var widened = method("widened");
        var other = new InvokerTarget.Described(InvokerTarget.of(Target.class.getName(), widened), false, Set.of(),
                InvokerShape.of(classInfo(), widened));
        var bean = new VaubanSyntheticBeanBuilder<>(String.class);
        bean.withParam("hidden", described).withParam("other", other).withParam("text", "a");
        assertEquals(Map.of(), InvokerClasses.of(List.of(bean), List.of(), _ -> false).classes());
        assertEquals(Map.of(), InvokerClasses.of(List.of(), List.of(), _ -> true).classes());
        assertEquals(1, InvokerClasses.of(List.of(bean), List.of(), _ -> true).classes().size());
    }

    /**
     * A method a generated class reaches as a member of its package needs one loader for both classes: the class is
     * generated only when the build writes them both, not for a split package of another archive.
     */
    @Test
    @DisplayName("a method reached through the package needs both classes written by the build")
    void aMethodReachedThroughThePackageNeedsBothClassesWrittenByTheBuild() throws IOException {
        var child = classInfo(Child.class, Base.class);
        var local = classInfo(Base.class, Child.class).methods().stream()
                .filter(method -> method.name().equals("local")).findFirst().orElseThrow();
        var shape = InvokerShape.of(child, local);
        assertTrue(shape.direct(), "the same package name");
        assertEquals(Set.of(Base.class.getName()), shape.packageBound());
        var described = new InvokerTarget.Described(InvokerTarget.of(Child.class.getName(), local), false, Set.of(),
                shape);
        var bean = new VaubanSyntheticBeanBuilder<>(String.class);
        bean.withParam("local", described);
        assertEquals(Map.of(), InvokerClasses.of(List.of(bean), List.of(), Child.class.getName()::equals).classes(),
                "a superclass of another archive");
        assertEquals(1, InvokerClasses.of(List.of(bean), List.of(), _ -> true).classes().size());
    }

    /**
     * With no lookup for the package of the generated class, the invoker calls the method as an invoker built at start;
     * a class it cannot make fails the call, naming it.
     */
    @Test
    @DisplayName("with no lookup the invoker calls the method itself, and a class it cannot make fails the call")
    void withNoLookupTheInvokerCallsTheMethodItself() throws Exception {
        var method = method("widened");
        var target = InvokerTarget.of(Target.class.getName(), method);
        // A class of a package java.base neither exports nor grants a lookup for, as no provider would.
        var unreachable = Class.forName("jdk.internal.misc.VM");
        var fallback = new VaubanInvoker(target, Target.class, Target.class, false, false, Map.of(), unreachable);
        assertEquals("1 2 3 4.0 5.0", fallback.invoke(new Target(), new Object[] {(short) 1, 2, 3L, 4f, 5d}));

        var broken = new VaubanInvoker(target, Target.class, Target.class, false, false, Map.of(), Integer.class);
        var arguments = new Object[] {(short) 1, 2, 3L, 4f, 5d};
        var instance = new Target();
        var failure = assertThrows(IllegalStateException.class, () -> broken.invoke(instance, arguments));
        assertEquals("cannot create java.lang.Integer", failure.getMessage());
    }

    @Test
    @DisplayName("a kind of parameter a newer build wrote names the version skew")
    void aKindANewerBuildWroteNamesTheVersionSkew() {
        var loader = getClass().getClassLoader();
        var newer = assertThrows(IllegalArgumentException.class, () -> SyntheticParamCodec.decode("Z:1:a", loader));
        assertTrue(newer.getMessage().contains("a newer Vauban build wrote the record"), newer::getMessage);
        var garbled = assertThrows(IllegalArgumentException.class, () -> SyntheticParamCodec.decode("z:1:a", loader));
        assertEquals("not a synthetic parameter: z:1:a", garbled.getMessage());
    }

    private static ParameterizedType assertInstanceOfParameterized(java.lang.reflect.Type type) {
        assertTrue(type instanceof ParameterizedType, String.valueOf(type));
        return (ParameterizedType) type;
    }

    /** An invoker of {@code name} that calls it through the class written for it, defined here. */
    private static VaubanInvoker generated(String name) throws IOException, IllegalAccessException {
        var method = method(name);
        var target = InvokerTarget.of(Target.class.getName(), method);
        var shape = InvokerShape.of(classInfo(), method);
        assertTrue(shape.direct(), name);
        var bytes = InvokerClassWriter.write(InvokerClassWriter.className(target), target, shape);
        var generated = MethodHandles.lookup().defineClass(bytes);
        return new VaubanInvoker(target, Target.class, Target.class, shape.isStatic(), false, Map.of(), generated);
    }

    private static MethodInfo method(String name) throws IOException {
        return classInfo().methods().stream().filter(method -> method.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private static ClassInfo classInfo() throws IOException {
        return classInfo(Target.class);
    }

    /** {@code type} described from an index of it and of {@code others}. */
    private static ClassInfo classInfo(Class<?> type, Class<?>... others) throws IOException {
        var index = new IndexBuilder();
        var scanned = scan(type);
        index.add(scanned);
        for (var other : others) {
            index.add(scan(other));
        }
        return new VaubanClassInfo(scanned, new IndexLookup(index.build()));
    }

    private static io.vidocq.vauban.indexer.model.ClassInfo scan(Class<?> type) throws IOException {
        var resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            return ClassFileScanner.scan(java.util.Objects.requireNonNull(in).readAllBytes());
        }
    }
}
