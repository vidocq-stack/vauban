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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261117-60: the language model shows the annotations on the types a class uses (JLS 9.7.4), at start: on the
 * types of its fields, parameters, return types and {@code throws} clauses, on its supertypes, on the bounds of its
 * type parameters and on its type parameters themselves, and inside them, on type arguments, wildcards, their bounds
 * and array components, at any depth. Only runtime-retained annotations show, as the language model asks. Every
 * {@code annotations()} of a type was empty.
 */
@DisplayName("BUG-20261117-60 - the language model shows the annotations on types")
class BceTypeAnnotationsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface A {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface B {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface C {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface D {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface E {
    }

    /** Kept in the class file, but invisible at run time: the language model does not show it. */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    public @interface Invisible {
    }

    public static class Base<X> {
    }

    public interface Contract<Y> {
    }

    public static class Outer {
        public class Inner {
        }

        public static class Nested {
        }
    }

    @Dependent
    public static class Annotated<@A T extends @B Number, U extends @C T> extends @D Base<@E String>
            implements @A Contract<@B List<@C ? extends @D Number>> {

        @A String plain;
        @B int primitive;
        @C String @A [] @B [] arrays;
        List<@A Map<@B String, @C T>> nested;
        @D U variable;
        List<@E ?> unbounded;
        List<? super @A Integer> lower;
        @Invisible String hidden;
        Outer.@A Inner inner;
        @B Outer.Inner enclosing;
        Outer.@C Nested member;

        public Annotated() {
        }

        public Annotated(@A String name) {
        }

        @A String method(@B List<@C String> first, @D int second) throws @E Exception {
            return null;
        }
    }

    /** The types of {@link Annotated}, as {@link TypeAnnotationsProbe#typesOf} writes them. */
    static final List<String> EXPECTED = List.of(
            "typeParameters [@A T extends @B Number, U extends @C T extends @B Number]",
            "superClass @D Base<@E String>",
            "superInterfaces [@A Contract<@B List<@C ? extends @D Number>>]",
            "arrays @C String @A [] @B []",
            "enclosing Inner",
            "hidden String",
            "inner @A Inner",
            "lower List<? super @A Integer>",
            "member @C Nested",
            "nested List<@A Map<@B String, @C T extends @B Number>>",
            "plain @A String",
            "primitive @B int",
            "unbounded List<@E ? extends Object>",
            "variable @D U extends @C T extends @B Number",
            "method @A String [@B List<@C String>, @D int] throws [@E Exception]",
            "constructor []",
            "constructor [@A String]");

    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    /** Records, in {@code @Synthesis}, what {@code describe} gives of the class it names. */
    public static class TypesExtension implements BuildCompatibleExtension {

        static volatile String probed;
        static volatile Function<jakarta.enterprise.lang.model.declarations.ClassInfo, List<String>> describe;

        @Synthesis
        public void types(Types types) {
            SEEN.addAll(describe.apply(types.ofClass(probed).declaration()));
        }
    }

    @Test
    @DisplayName("@Synthesis sees the annotations on every type a class uses")
    void theTypesOfAClassShowTheirAnnotations() {
        assertEquals(EXPECTED, seenAtStart(Annotated.class, TypeAnnotationsProbe::typesOf));
    }

    public static class Bounded<U extends @B Object, V extends @C CharSequence> {
        @A List<@B String @C []> annotated;
        List<String[]> plain;
    }

    /**
     * A bound the index leaves out, an annotated {@code Object}, shows where the language model shows it, and a bound
     * that is an interface keeps its annotation; an annotated type equals the same type without annotations, as it
     * does in the container's resolution.
     */
    @Test
    @DisplayName("an Object bound keeps its annotation, and annotations take no part in a type's equality")
    void anObjectBoundAndEquality() {
        assertEquals(List.of("[U extends @B Object, V extends @C CharSequence]", "equal true, same hash true"),
                seenAtStart(Bounded.class, type -> {
                    var annotated = type.fields().stream().filter(field -> field.name().equals("annotated"))
                            .findFirst().orElseThrow().type();
                    var plain = type.fields().stream().filter(field -> field.name().equals("plain"))
                            .findFirst().orElseThrow().type();
                    return List.of(TypeAnnotationsProbe.all(type.typeParameters()), "equal " + annotated.equals(plain)
                            + ", same hash " + (annotated.hashCode() == plain.hashCode()));
                }));
    }

    public static class Receiving<R> {
        @A Receiving() {
        }

        void method(@A Receiving<@B R> this) {
        }

        <@C V extends @D CharSequence> void generic() {
        }

        <X extends Exception> void failing() throws @E X {
        }
    }

    @Test
    @DisplayName("a receiver type shows its annotations")
    void aReceiverTypeShowsItsAnnotations() {
        assertEquals(List.of("@A Receiving<@B R extends Object>"), seenAtStart(Receiving.class, type -> type.methods()
                .stream().filter(method -> method.name().equals("method"))
                .map(method -> TypeAnnotationsProbe.of(method.receiverType())).toList()));
    }

    @Test
    @DisplayName("a method's type parameters show their annotations")
    void aMethodsTypeParametersShowTheirAnnotations() {
        assertEquals(List.of("[@C V extends @D CharSequence]"), seenAtStart(Receiving.class, type -> type.methods()
                .stream().filter(method -> method.name().equals("generic"))
                .map(method -> TypeAnnotationsProbe.all(method.typeParameters())).toList()));
    }

    @Test
    @DisplayName("a type variable a method throws shows its annotations")
    void aThrownTypeVariableShowsItsAnnotations() {
        assertEquals(List.of("[@E X extends Exception]"), seenAtStart(Receiving.class, type -> type.methods()
                .stream().filter(method -> method.name().equals("failing"))
                .map(method -> TypeAnnotationsProbe.all(method.throwsTypes())).toList()));
    }

    @Test
    @DisplayName("a constructor's return type, its class, shows the annotations on what it builds")
    void aConstructorsReturnTypeShowsItsAnnotations() {
        assertEquals(List.of("@A Receiving"), seenAtStart(Receiving.class, type -> type.constructors().stream()
                .map(constructor -> TypeAnnotationsProbe.of(constructor.returnType())).toList()));
    }

    /** A class whose method declares a type parameter of the class's name. */
    public static class Shadow<@A T extends @B Number> {
        <@C T extends @D CharSequence> @E T method(T value) {
            return value;
        }
    }

    /**
     * A method's type parameter that shadows one of its class: a type the method uses names the method's, with the
     * bounds and the annotations the method declares, as in the processor
     * ({@code TypeAnnotationsProcessorTest#aMethodTypeParameterShadowsTheClasss}).
     */
    @Test
    @DisplayName("a method's type parameter shadows the class's, with its own bounds and their annotations")
    void aMethodTypeParameterShadowsTheClasss() {
        assertEquals(List.of("@E T extends @D CharSequence", "[T extends @D CharSequence]",
                "[@C T extends @D CharSequence]"), seenAtStart(Shadow.class, type -> type.methods().stream()
                .filter(method -> method.name().equals("method"))
                .flatMap(method -> java.util.stream.Stream.of(TypeAnnotationsProbe.of(method.returnType()),
                        TypeAnnotationsProbe.all(method.parameters().stream()
                                .map(jakarta.enterprise.lang.model.declarations.ParameterInfo::type).toList()),
                        TypeAnnotationsProbe.all(method.typeParameters())))
                .toList()));
    }

    private static List<String> seenAtStart(Class<?> probe,
            Function<jakarta.enterprise.lang.model.declarations.ClassInfo, List<String>> describe) {
        SEEN.clear();
        TypesExtension.probed = probe.getName();
        TypesExtension.describe = describe;
        try (var _ = SeContainerInitializer.newInstance().addBeanClasses(probe, TypesExtension.class)
                .initialize()) {
            return List.copyOf(SEEN);
        }
    }
}
