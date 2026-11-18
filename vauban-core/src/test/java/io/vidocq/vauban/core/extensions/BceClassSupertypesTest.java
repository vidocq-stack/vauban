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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261117-22: {@code ClassInfo.superClass()} and {@code superInterfaces()} give a build compatible extension the
 * supertypes a class declares as it declares them, with their type arguments, in declaration order: a parameterized
 * type whose arguments may be classes, parameterized types, with wildcards inside them (JLS 8.1.4 allows none at the
 * top), arrays or type variables of the class, and a class only for a supertype the class names raw. They gave every
 * supertype raw. The answer is the same whichever way
 * the extension reaches the class: the declaration of a {@code ClassType} or of a {@code ParameterizedType}'s generic
 * class. An interface has no superclass (BUG-20261117-32).
 */
@DisplayName("BUG-20261117-22 - ClassInfo.superClass() and superInterfaces() keep their type arguments")
class BceClassSupertypesTest {

    private static final String PREFIX = BceClassSupertypesTest.class.getName() + "$";
    private static final String OBJECT = "superClass=CLASS java.lang.Object";
    private static final String NO_INTERFACE = "superInterfaces=[]";
    private static final String SUPPLYING = "Supplying: " + OBJECT
            + "; superInterfaces=[PARAMETERIZED_TYPE java.util.function.Supplier<T> of TYPE_VARIABLE]";

    /** The smallest repro: one parameterized superinterface. */
    public interface Probe extends Supplier<String> {
    }

    public static class Base<B> {
    }

    @Dependent
    public static class Extending extends Base<List<String>> {
    }

    public interface Crud<E, K> {
    }

    public interface Labelled<L> {
    }

    public static class Note {
    }

    /** Mansart's shape: two parameterized superinterfaces, in declaration order. */
    public interface Archive extends Crud<Note, Long>, Labelled<List<String>> {
    }

    @Dependent
    public static class Supplying<T> implements Supplier<T> {
        @Override
        public T get() {
            return null;
        }
    }

    public interface Holder<H> {
    }

    @Dependent
    public static class Shapes implements Holder<Map<? extends Number, ? super Integer>>, Labelled<int[][]> {
    }

    @Dependent
    @SuppressWarnings("rawtypes")
    public static class Raw extends ArrayList {
        private static final long serialVersionUID = 1L;
    }

    @Dependent
    public static class Plain {
    }

    public enum Level {
        LOW
    }

    private static final List<Class<?>> PROBES = List.of(Probe.class, Extending.class, Archive.class, Supplying.class,
            Shapes.class, Raw.class, Plain.class, Level.class);

    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static final List<Class<?>> PROBES_TO_SEE = new CopyOnWriteArrayList<>();

    /**
     * Records what {@code @Synthesis} sees of the supertypes of each class of {@link #PROBES_TO_SEE}, through
     * {@code Types}, then of {@code Supplying} through the generic class of a {@code Supplying<String>}.
     */
    public static class SupertypesExtension implements BuildCompatibleExtension {

        @Synthesis
        public void supertypes(Types types) {
            for (var probe : PROBES_TO_SEE) {
                SEEN.add(supertypesOf(types.ofClass(probe.getName()).declaration()));
            }
            if (PROBES_TO_SEE.contains(Supplying.class)) {
                var parameterized = types.parameterized(Supplying.class, String.class);
                SEEN.add("through genericClass() " + supertypesOf(parameterized.genericClass().declaration()));
            }
        }
    }

    @Test
    @DisplayName("@Synthesis sees each supertype a class declares, with its type arguments")
    void supertypesKeepTheirTypeArguments() {
        var expected = List.of(
                "Probe: superClass=null; superInterfaces=[PARAMETERIZED_TYPE java.util.function.Supplier"
                        + "<java.lang.String> of CLASS]",
                "Extending: superClass=PARAMETERIZED_TYPE Base<java.util.List<java.lang.String>>"
                        + " of PARAMETERIZED_TYPE; " + NO_INTERFACE,
                "Archive: superClass=null; superInterfaces=[PARAMETERIZED_TYPE Crud<Note, java.lang.Long>"
                        + " of CLASS, CLASS, PARAMETERIZED_TYPE Labelled<java.util.List<java.lang.String>>"
                        + " of PARAMETERIZED_TYPE]",
                SUPPLYING,
                "Shapes: " + OBJECT + "; superInterfaces=[PARAMETERIZED_TYPE Holder<java.util.Map<? extends"
                        + " java.lang.Number, ? super java.lang.Integer>> of PARAMETERIZED_TYPE, PARAMETERIZED_TYPE"
                        + " Labelled<int[][]> of ARRAY]",
                "Raw: superClass=CLASS java.util.ArrayList; " + NO_INTERFACE,
                "Plain: " + OBJECT + "; " + NO_INTERFACE,
                "Level: superClass=PARAMETERIZED_TYPE java.lang.Enum<Level> of CLASS; " + NO_INTERFACE,
                "through genericClass() " + SUPPLYING);

        assertEquals(expected, seenAtStart(PROBES));
    }

    public interface Plainly {
    }

    public @interface Tag {
    }

    /**
     * An interface has no superclass in the language model, though its class file names {@code java.lang.Object}
     * (JVMS 4.1): {@code superClass()} is {@code null}, as the processor gives it from the compiler's model, and an
     * annotation type is an interface too (BUG-20261117-32).
     */
    @Test
    @DisplayName("an interface and an annotation type have no superclass")
    void anInterfaceHasNoSuperclass() {
        assertEquals(List.of("Plainly: superClass=null; " + NO_INTERFACE,
                        "Tag: superClass=null; superInterfaces=[CLASS java.lang.annotation.Annotation]"),
                seenAtStart(List.of(Plainly.class, Tag.class)));
    }

    static final List<String> BOUNDS = new CopyOnWriteArrayList<>();

    /** The bounds of {@code Supplying}'s {@code T}, as a type parameter and as its superinterface's argument. */
    public static class BoundsExtension implements BuildCompatibleExtension {

        @Synthesis
        public void bounds(Types types) {
            var supplying = types.ofClass(Supplying.class.getName()).declaration();
            BOUNDS.add("typeParameters() " + boundsOf(supplying.typeParameters().getFirst()));
            BOUNDS.add("superInterfaces() " + boundsOf(supplying.superInterfaces().getFirst().asParameterizedType()
                    .typeArguments().getFirst()));
        }

        private static List<String> boundsOf(Type variable) {
            return variable.asTypeVariable().bounds().stream().map(BceClassSupertypesTest::nameOf).toList();
        }
    }

    /**
     * {@code TypeVariable.bounds()} is never empty: a type variable the class declares without a bound has
     * {@code java.lang.Object}, as {@code typeParameters()} gives it, and so it has as the argument of a supertype
     * (BUG-20261117-33).
     */
    @Test
    @DisplayName("a type variable of a supertype has the bound the class declares")
    void aTypeVariableOfASupertypeHasItsBound() {
        BOUNDS.clear();
        try (var _ = SeContainerInitializer.newInstance().addBeanClasses(Supplying.class, BoundsExtension.class)
                .initialize()) {
            assertEquals(List.of("typeParameters() [java.lang.Object]", "superInterfaces() [java.lang.Object]"),
                    List.copyOf(BOUNDS));
        }
    }

    /** What {@link SupertypesExtension} records of {@code probes} when the container runs it at start. */
    private static List<String> seenAtStart(List<Class<?>> probes) {
        SEEN.clear();
        PROBES_TO_SEE.clear();
        PROBES_TO_SEE.addAll(probes);
        var classes = new ArrayList<Class<?>>(probes);
        classes.add(SupertypesExtension.class);
        try (var _ = SeContainerInitializer.newInstance().addBeanClasses(classes.toArray(Class<?>[]::new))
                .initialize()) {
            return List.copyOf(SEEN);
        }
    }

    /** The supertypes of {@code type}: the superclass and the superinterfaces, each with its kind. */
    static String supertypesOf(ClassInfo type) {
        var superClass = type.superClass();
        return named(type.name()) + ": superClass=" + (superClass == null ? "null" : described(superClass))
                + "; superInterfaces=" + type.superInterfaces().stream().map(BceClassSupertypesTest::described)
                        .collect(Collectors.joining(", ", "[", "]"));
    }

    /** A type with its kind, and for a parameterized type the kinds of its arguments. */
    private static String described(Type type) {
        var described = type.kind() + " " + nameOf(type);
        return type.isParameterizedType() ? described + " of " + type.asParameterizedType().typeArguments().stream()
                .map(argument -> argument.kind().name()).collect(Collectors.joining(", ")) : described;
    }

    private static String nameOf(Type type) {
        return switch (type.kind()) {
            case CLASS -> named(type.asClass().declaration().name());
            case PARAMETERIZED_TYPE -> named(type.asParameterizedType().genericClass().declaration().name())
                    + type.asParameterizedType().typeArguments().stream().map(BceClassSupertypesTest::nameOf)
                            .collect(Collectors.joining(", ", "<", ">"));
            case ARRAY -> nameOf(type.asArray().componentType()) + "[]";
            case PRIMITIVE -> type.asPrimitive().name();
            case TYPE_VARIABLE -> type.asTypeVariable().name();
            case WILDCARD_TYPE -> wildcard(type);
            default -> type.toString();
        };
    }

    private static String wildcard(Type type) {
        var wildcard = type.asWildcardType();
        if (wildcard.upperBound() != null) {
            return "? extends " + nameOf(wildcard.upperBound());
        }
        return wildcard.lowerBound() != null ? "? super " + nameOf(wildcard.lowerBound()) : "?";
    }

    /** A class of this test by its simple name, any other by its name. */
    private static String named(String name) {
        return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
    }
}
