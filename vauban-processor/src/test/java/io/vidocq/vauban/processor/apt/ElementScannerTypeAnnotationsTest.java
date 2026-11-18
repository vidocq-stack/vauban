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

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-60: the processor reads the annotations on types from the compiler's model as the bytecode scan reads
 * them from the class file, so both build the same index: the same targets, indexes, bounds, paths and annotations,
 * in the same order, for the class, each field and each method. A constructor that has an implicit leading parameter
 * in the class file numbers its parameters in each index's own parameters.
 */
@DisplayName("BUG-20261117-60 - the processor and the bytecode scan agree on the annotations on types")
class ElementScannerTypeAnnotationsTest {

    private static final String SOURCE = """
            package app;
            import java.lang.annotation.*;
            import java.util.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface A {}
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE_USE, ElementType.CONSTRUCTOR}) @interface B {
                String value() default "";
            }
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface Valued {
                String name();
                long size() default 0;
                Level level() default Level.LOW;
                Class<?> type() default Object.class;
                int[] codes() default {};
                B nested() default @B;
            }
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface Primitives {
                boolean flag();
                byte tiny();
                short small();
                char letter();
                int number();
                float ratio();
                double precise();
                long[] longs();
            }
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE_USE) @interface Hidden {}
            @Target(ElementType.TYPE_USE) @interface Unretained {}
            interface Labelled<L> {}
            class Outer {
                class Inner {
                    Inner(@A String name) {
                    }
                }
                static class Nested {
                }
            }
            enum Level {
                @B LOW("low"), HIGH("high");
                Level(@B String label) {
                }
            }
            public class Fixture<@A T extends @B("t") Number & @A Comparable<@B T>, U extends @A Object,
                    V extends CharSequence> extends @B AbstractList<@A String> implements @A Labelled<@B T> {
                @A Map<@B String, @A List<? extends @B T @A []>> map;
                Outer.@A Inner inner;
                @B Outer.Inner enclosing;
                Outer.@B Nested nested;
                @Hidden @Unretained String unseen;
                @B Fixture() {
                }
                @A Fixture(int size) {
                }
                @Valued(name = "n", size = 2, level = Level.HIGH, type = int[].class, codes = {1, -2},
                        nested = @B("b")) @Hidden Fixture(long size, String label) {
                }
                @Valued(name = Fixture.NAME, codes = 3, type = String.class) Fixture(String label, long size,
                        int more) {
                }
                @Primitives(flag = true, tiny = (byte) -1, small = -(2), letter = 'x', number = 'a', ratio = -1.5f,
                        precise = +2, longs = {-1, 'b'}) Fixture(char a, char b, char c, char d) {
                }
                @Valued(name = NAME + "!") Fixture(double a, double b, double c, double d, double e) {
                }
                static final String NAME = "named";
                <@A W extends @B Exception, X extends @A Object> @A String method(@A Fixture<T, U, V> this,
                        @B List<@A ? super W> first, @A int second) throws @B W, @A IllegalStateException {
                    return null;
                }
                @Override public String get(int index) {
                    return null;
                }
                @Override public int size() {
                    return 0;
                }
            }
            """;

    private static final List<String> CLASSES = List.of("Fixture", "Outer.Inner", "Level");

    /** A method by its name and its number of parameters. */
    private static final Function<MethodInfo, String> SIGNATURE = method -> method.name() + "/"
            + method.parameters().size();

    /** {@code @A Fixture(int size)}: {@code @A} applies to a type use only. */
    private static final String TYPE_USE_ONLY_CONSTRUCTOR = "<init>/1";

    /** {@code @Valued(name = NAME + "!") Fixture(double, ...)}: what stays of BUG-20261118-06. */
    private static final String COMPUTED_MEMBER_CONSTRUCTOR = "<init>/5";

    private static final Map<String, ClassInfo> PROCESSOR = new HashMap<>();
    private static final Map<String, ClassInfo> BYTECODE = new HashMap<>();

    /** Scans the fixture classes with the processor's own scanner. */
    @SupportedAnnotationTypes("*")
    static final class Capture extends AbstractProcessor {
        final Map<String, ClassInfo> scanned = new HashMap<>();

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            var elements = processingEnv.getElementUtils();
            var scanner = new ElementScanner(processingEnv);
            if (scanned.isEmpty() && elements.getTypeElement("app.Fixture") != null) {
                CLASSES.forEach(name -> scanned.put(name, scanner.scan(elements.getTypeElement("app." + name))));
            }
            return false;
        }
    }

    @BeforeAll
    static void compileAndScanBothWays(@TempDir Path tempDir) throws IOException {
        var sourceFile = Files.createDirectories(tempDir.resolve("src/app")).resolve("Fixture.java");
        Files.writeString(sourceFile, SOURCE);
        var classes = Files.createDirectories(tempDir.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var capture = new Capture();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-d", classes.toString(), "--release", "25", "-proc:full"), null,
                    fileManager.getJavaFileObjects(sourceFile));
            task.setProcessors(List.of(capture));
            assertTrue(task.call(), "the fixture must compile: " + diagnostics.getDiagnostics());
        }
        PROCESSOR.putAll(capture.scanned);
        for (var name : CLASSES) {
            BYTECODE.put(name, ClassFileScanner.scan(Files.readAllBytes(
                    classes.resolve("app/" + name.replace('.', '$') + ".class"))));
        }
    }

    @Test
    @DisplayName("guard: the bytecode scan reads annotations on the class, its fields and its methods")
    void theBytecodeScanReadsThem() {
        var fixture = BYTECODE.get("Fixture");
        assertEquals(List.of(9, 1, 0, 1, 0), List.of(fixture.typeAnnotations().size(),
                field(fixture, "inner").size(), field(fixture, "enclosing").size(), field(fixture, "nested").size(),
                field(fixture, "unseen").size()));
    }

    @Test
    @DisplayName("the class: its supertypes, type parameters and bounds, an annotated Object bound included")
    void theClass() {
        assertEquals(described(BYTECODE.get("Fixture").typeAnnotations()),
                described(PROCESSOR.get("Fixture").typeAnnotations()));
    }

    @Test
    @DisplayName("the fields: paths into arrays, wildcards and type arguments, nested types, runtime ones only")
    void theFields() {
        assertEquals(byMember(BYTECODE.get("Fixture").fields(), FieldInfo::name, FieldInfo::typeAnnotations),
                byMember(PROCESSOR.get("Fixture").fields(), FieldInfo::name, FieldInfo::typeAnnotations));
    }

    @Test
    @DisplayName("the methods: return, receiver, parameters, throws, type parameters, a constructor's return")
    void theMethods() {
        var bytecode = byMember(BYTECODE.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        bytecode.remove(COMPUTED_MEMBER_CONSTRUCTOR);
        assertEquals(bytecode, byMember(PROCESSOR.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations));
        assertEquals("[RETURN 0 0 [] A{}, RECEIVER 0 0 [] A{}, PARAMETER 0 0 [] B{},"
                + " PARAMETER 0 0 [TYPE_ARGUMENT 0] A{}, PARAMETER 1 0 [] A{}, THROWS 0 0 [] B{}, THROWS 1 0 [] A{},"
                + " TYPE_PARAMETER 0 0 [] A{},"
                + " TYPE_PARAMETER_BOUND 0 0 [] B{}, TYPE_PARAMETER_BOUND 1 0 [] A{}]",
                bytecode.get("method/2"));
    }

    /**
     * An annotation that applies to a type use only, on a constructor, is on what the constructor builds: the class
     * file records it on the constructor's return type. The compiler's model has it neither among the constructor's
     * annotations nor on its {@code void} return type, so the processor does not see it (BUG-20261118-06).
     */
    @Test
    @DisplayName("a constructor's type-use-only annotation is on its return type")
    void aTypeUseOnlyAnnotationOnAConstructor() {
        var bytecode = byMember(BYTECODE.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        var processor = byMember(PROCESSOR.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        assertEquals(bytecode.get(TYPE_USE_ONLY_CONSTRUCTOR), processor.get(TYPE_USE_ONLY_CONSTRUCTOR));
        assertEquals("[RETURN 0 0 [] A{}]", bytecode.get(TYPE_USE_ONLY_CONSTRUCTOR));
    }

    /**
     * A type-use-only annotation with members on a constructor: a string, a long written as an {@code int}, an enum
     * constant, a class literal, an array written whole or as one element, a nested annotation, a constant named by
     * its field; a class-retained one is left out, both ways (BUG-20261118-06).
     */
    @Test
    @DisplayName("a constructor's type-use-only annotation keeps its members")
    void aTypeUseOnlyAnnotationWithMembersOnAConstructor() {
        var bytecode = byMember(BYTECODE.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        var processor = byMember(PROCESSOR.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        assertEquals(bytecode.get("<init>/2"), processor.get("<init>/2"));
        assertEquals(bytecode.get("<init>/3"), processor.get("<init>/3"));
        assertTrue(bytecode.get("<init>/2").startsWith("[RETURN 0 0 [] Valued{"), bytecode.get("<init>/2"));
    }

    /**
     * A type-use-only annotation on a constructor holding each primitive, written with a cast, a sign, parentheses
     * and a character where a number is expected, as the compiler converts them (BUG-20261118-06).
     */
    @Test
    @DisplayName("a constructor's type-use-only annotation holds each primitive as the class file does")
    void aTypeUseOnlyAnnotationWithPrimitivesOnAConstructor() {
        var bytecode = byMember(BYTECODE.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        var processor = byMember(PROCESSOR.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        assertEquals(bytecode.get("<init>/4"), processor.get("<init>/4"));
        assertTrue(bytecode.get("<init>/4").startsWith("[RETURN 0 0 [] Primitives{"));
    }

    /**
     * What stays: a member written as a constant expression of several operands, one a constant named by its field,
     * is not read from the source, and the processor leaves out the annotation that holds it, which the class file
     * records (BUG-20261118-06). Literals alone the compiler joins as it parses them.
     */
    @Test
    @DisplayName("a constructor's type-use-only annotation with a computed member is left out in the processor")
    void aComputedMemberIsLeftOut() {
        var bytecode = byMember(BYTECODE.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        var processor = byMember(PROCESSOR.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations);
        assertEquals("[RETURN 0 0 [] Valued{name=StringVal[value=named!]}]", bytecode.get(COMPUTED_MEMBER_CONSTRUCTOR));
        assertNull(processor.get(COMPUTED_MEMBER_CONSTRUCTOR));
    }

    /**
     * BUG-20261118-33: an inner class's constructor has the outer instance first in both indexes, which javac marks
     * mandated and the language model counts as implicitly declared, named {@code arg0} where the class file gives
     * no name; a type annotation numbers it.
     */
    @Test
    @DisplayName("a constructor numbers its parameters in the parameters each index records")
    void constructorParameters() {
        assertEquals("[PARAMETER 1 0 [] A{}]", described(method(PROCESSOR.get("Outer.Inner"), "<init>")));
        assertEquals("[PARAMETER 1 0 [] A{}]", described(method(BYTECODE.get("Outer.Inner"), "<init>")));
        assertEquals("[PARAMETER 0 0 [] B{}]", described(method(PROCESSOR.get("Level"), "<init>")));
        assertEquals("[PARAMETER 0 0 [] B{}]", described(method(BYTECODE.get("Level"), "<init>")));
    }

    @Test
    @DisplayName("an enum's constructor has the parameters its source declares in both indexes (BUG-20261117-58)")
    void enumConstructorParameters() {
        assertEquals(parameters(PROCESSOR.get("Level")), parameters(BYTECODE.get("Level")));
        assertEquals("[ClassType[name=java.lang.String]]", parameters(BYTECODE.get("Level")));
    }

    @Test
    @DisplayName("an inner class's constructor has the outer instance first in both indexes (BUG-20261118-33)")
    void innerConstructorParameters() {
        assertEquals(parameters(BYTECODE.get("Outer.Inner")), parameters(PROCESSOR.get("Outer.Inner")));
        assertEquals("[ClassType[name=app.Outer], ClassType[name=java.lang.String]]",
                parameters(PROCESSOR.get("Outer.Inner")));
        assertEquals("arg0", PROCESSOR.get("Outer.Inner").methods().stream().filter(MethodInfo::isConstructor)
                .findFirst().orElseThrow().parameters().getFirst().name());
    }

    /**
     * BUG-20261117-59: an enum's constants are among its fields in both indexes, with their annotations and their
     * flags ({@code public static final}, {@code ACC_ENUM}), before the fields the enum declares.
     */
    @Test
    @DisplayName("an enum's constants are fields in both indexes (BUG-20261117-59)")
    void enumConstantsAreFields() {
        assertEquals(fields(BYTECODE.get("Level")), fields(PROCESSOR.get("Level")));
        assertEquals("[LOW 16409 [B], HIGH 16409 []]", fields(PROCESSOR.get("Level")));
    }

    /** The fields of a class, but the array of its constants javac adds. */
    private static String fields(ClassInfo type) {
        return type.fields().stream().filter(field -> !field.name().equals("$VALUES"))
                .map(field -> field.name() + " " + field.accessFlags() + " " + field.annotations().stream()
                        .map(annotation -> annotation.name().simpleName()).toList())
                .toList().toString();
    }

    private static String parameters(ClassInfo type) {
        return type.methods().stream().filter(MethodInfo::isConstructor).findFirst().orElseThrow().parameters()
                .stream().map(parameter -> parameter.type().toString()).toList().toString();
    }

    private static List<TypeAnnotationInfo> field(ClassInfo type, String name) {
        return type.fields().stream().filter(field -> field.name().equals(name)).findFirst()
                .map(FieldInfo::typeAnnotations).orElseThrow();
    }

    private static List<TypeAnnotationInfo> method(ClassInfo type, String name) {
        return type.methods().stream().filter(method -> method.name().equals(name)).findFirst()
                .map(MethodInfo::typeAnnotations).orElseThrow();
    }

    /** Each member's type annotations by its name; the bytecode scan's synthetic bridges have none to compare. */
    private static <M> Map<String, String> byMember(List<M> members, Function<M, String> name,
            Function<M, List<TypeAnnotationInfo>> annotations) {
        return members.stream().filter(member -> !annotations.apply(member).isEmpty())
                .collect(Collectors.toMap(name, member -> described(annotations.apply(member)), (first, _) -> first,
                        HashMap::new));
    }

    private static String described(List<TypeAnnotationInfo> annotations) {
        return annotations.stream().map(annotation -> annotation.target() + " " + annotation.index() + " "
                + annotation.boundIndex() + " " + annotation.path().stream()
                        .map(step -> step.kind() == TypeAnnotationInfo.Step.Kind.TYPE_ARGUMENT
                                ? "TYPE_ARGUMENT " + step.typeArgumentIndex() : step.kind().name())
                        .collect(Collectors.joining(", ", "[", "]")) + " "
                + annotation.annotation().name().simpleName() + annotation.annotation().members())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
