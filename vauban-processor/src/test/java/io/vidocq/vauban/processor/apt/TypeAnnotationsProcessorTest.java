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
package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-60: in the processor, the language model shows the annotations on the types a class uses, as it does at
 * start ({@code BceTypeAnnotationsTest} in {@code vauban-core}, same class, same answer), from the compiler's model:
 * for a class the compilation compiles, and for a class of the class path, which the compiler reads from its class
 * file. JDK-8225377, by which the compiler gave the types of such a class without their annotations, does not show
 * with the compiler of Java 25: the second test is the proof.
 */
@DisplayName("BUG-20261117-60 - in the processor, the language model shows the annotations on types")
class TypeAnnotationsProcessorTest {

    /** The class of {@code BceTypeAnnotationsTest}, in package {@code %1$s}, its annotations beside it. */
    private static final String ANNOTATED = """
            package %1$s;
            import java.util.List;
            import java.util.Map;
            @jakarta.enterprise.context.Dependent
            public class Annotated<@A T extends @B Number, U extends @C T> extends @D Base<@E String>
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
            """;

    private static final String SUPPORT = """
            package %1$s;
            public class Base<X> {
            }
            interface Contract<Y> {
            }
            class Outer {
                public class Inner {
                }
                public static class Nested {
                }
            }
            """;

    private static final String ANNOTATION = """
            package %1$s;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.%3$s)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
            public @interface %2$s {
            }
            """;

    /** What {@code BceTypeAnnotationsTest} sees at start. */
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

    /** Records, in {@code @Synthesis}, the types of the class {@link #probed} names. */
    public static class TypesRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();
        static volatile String probed;

        @Synthesis
        public void types(Types types) {
            SEEN.addAll(typesOf(types.ofClass(probed).declaration()));
        }
    }

    @Test
    @DisplayName("a class the compilation compiles: the types show their annotations")
    void aCompiledClassShowsItsTypeAnnotations(@TempDir Path directory) throws IOException {
        assertEquals(EXPECTED, seen(directory, "app", sources("app"), List.of()));
    }

    @Test
    @DisplayName("a class of the class path: the types show their annotations (JDK-8225377 does not show)")
    void aClassPathClassShowsItsTypeAnnotations(@TempDir Path directory) throws IOException {
        var library = ProcessorCompiler.compile(directory.resolve("library"), sources("lib"), List.of(),
                List.of());
        assertTrue(library.success(), () -> "the library must compile: " + library.messages());

        assertEquals(EXPECTED, seen(directory, "lib", Map.of("app/Holder.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Holder {
                }
                """), List.of("-classpath", ProcessorCompiler.classPath() + File.pathSeparator + library.classes())));
    }

    /**
     * A method's type parameter that shadows one of its class: a type the method uses names the method's, with the
     * bounds and the annotations the method declares, not the class's.
     */
    @Test
    @DisplayName("a method's type parameter shadows the class's, with its own bounds and their annotations")
    void aMethodTypeParameterShadowsTheClasss(@TempDir Path directory) throws IOException {
        var sources = sources("app");
        sources.put("app/Shadow.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Shadow<@A T extends @B Number> {
                    <@C T extends @D CharSequence> @E T method(T value) {
                        return value;
                    }
                }
                """);
        var seen = seen(directory, sources, List.of(), "app.Shadow");

        assertEquals(List.of("method @E T extends @D CharSequence [T extends @D CharSequence] throws []"),
                seen.stream().filter(line -> line.startsWith("method ")).toList());
        assertEquals(List.of("methodTypeParameters [@C T extends @D CharSequence]"),
                seen.stream().filter(line -> line.startsWith("methodTypeParameters ")).toList());
    }

    private static List<String> seen(Path directory, String probedPackage, Map<String, String> sources,
            List<String> options) throws IOException {
        return seen(directory, sources, options, probedPackage + ".Annotated");
    }

    private static List<String> seen(Path directory, Map<String, String> sources, List<String> options,
            String probed) throws IOException {
        TypesRecorder.SEEN.clear();
        TypesRecorder.probed = probed;
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(TypesRecorder.class));
        var result = ProcessorCompiler.compile(directory.resolve("application"), sources, options, processor);
        assertTrue(result.success(), () -> "the application must compile: " + result.messages());
        return List.copyOf(TypesRecorder.SEEN);
    }

    private static Map<String, String> sources(String packageName) {
        var sources = new HashMap<String, String>();
        sources.put(packageName + "/Annotated.java", ANNOTATED.formatted(packageName));
        sources.put(packageName + "/Base.java", SUPPORT.formatted(packageName));
        for (var name : List.of("A", "B", "C", "D", "E")) {
            sources.put(packageName + "/" + name + ".java", ANNOTATION.formatted(packageName, name, "RUNTIME"));
        }
        sources.put(packageName + "/Invisible.java", ANNOTATION.formatted(packageName, "Invisible", "CLASS"));
        return sources;
    }

    /** The types of {@code type}, written as {@code TypeAnnotationsProbe} in {@code vauban-core} writes them. */
    static List<String> typesOf(ClassInfo type) {
        var lines = new ArrayList<String>();
        lines.add("typeParameters " + all(type.typeParameters()));
        lines.add("superClass " + of(type.superClass()));
        lines.add("superInterfaces " + all(type.superInterfaces()));
        type.fields().stream().sorted(Comparator.comparing(FieldInfo::name))
                .forEach(field -> lines.add(field.name() + " " + of(field.type())));
        type.methods().stream().filter(method -> method.declaringClass().name().equals(type.name()))
                .sorted(Comparator.comparing(MethodInfo::name))
                .forEach(method -> lines.add(method.name() + " " + of(method.returnType()) + " "
                        + all(method.parameters().stream().map(ParameterInfo::type).toList()) + " throws "
                        + all(method.throwsTypes())));
        type.methods().stream().filter(method -> method.declaringClass().name().equals(type.name()))
                .filter(method -> !method.typeParameters().isEmpty())
                .forEach(method -> lines.add("methodTypeParameters " + all(method.typeParameters())));
        type.constructors().forEach(constructor -> lines.add("constructor "
                + all(constructor.parameters().stream().map(ParameterInfo::type).toList())));
        return lines;
    }

    private static String all(List<? extends Type> types) {
        return types.stream().map(TypeAnnotationsProcessorTest::of).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String of(Type type) {
        if (type == null) {
            return "null";
        }
        var annotations = type.annotations().stream().map(AnnotationInfo::name)
                .map(name -> "@" + simple(name) + " ").collect(Collectors.joining());
        return switch (type.kind()) {
            case ARRAY -> array(type);
            case CLASS -> annotations + simple(type.asClass().declaration().name());
            case PARAMETERIZED_TYPE -> annotations + simple(type.asParameterizedType().genericClass().declaration()
                    .name()) + all(type.asParameterizedType().typeArguments()).replace('[', '<').replace(']', '>');
            case TYPE_VARIABLE -> annotations + type.asTypeVariable().name() + type.asTypeVariable().bounds()
                    .stream().map(TypeAnnotationsProcessorTest::of).collect(Collectors.joining(" & ",
                            type.asTypeVariable().bounds().isEmpty() ? "" : " extends ", ""));
            case WILDCARD_TYPE -> annotations + "?" + wildcard(type);
            case PRIMITIVE -> annotations + type.asPrimitive().name();
            case VOID -> annotations + "void";
        };
    }

    private static String array(Type type) {
        var dimensions = new StringBuilder();
        var component = type;
        while (component.isArray()) {
            dimensions.append(' ').append(component.annotations().stream().map(AnnotationInfo::name)
                    .map(name -> "@" + simple(name) + " ").collect(Collectors.joining())).append("[]");
            component = component.asArray().componentType();
        }
        return of(component) + dimensions;
    }

    private static String wildcard(Type type) {
        var wildcard = type.asWildcardType();
        if (wildcard.upperBound() != null) {
            return " extends " + of(wildcard.upperBound());
        }
        return wildcard.lowerBound() != null ? " super " + of(wildcard.lowerBound()) : "";
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
