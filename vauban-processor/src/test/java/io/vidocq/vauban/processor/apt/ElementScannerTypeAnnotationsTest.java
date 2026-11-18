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
import org.junit.jupiter.api.Disabled;
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
            var scanner = new ElementScanner(elements, processingEnv.getTypeUtils());
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
        bytecode.remove(TYPE_USE_ONLY_CONSTRUCTOR);
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
    @Disabled("BUG-20261118-06: a type-use-only annotation on a constructor is missing in the processor")
    @DisplayName("a constructor's type-use-only annotation is on its return type")
    void aTypeUseOnlyAnnotationOnAConstructor() {
        assertEquals(byMember(BYTECODE.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations)
                        .get(TYPE_USE_ONLY_CONSTRUCTOR),
                byMember(PROCESSOR.get("Fixture").methods(), SIGNATURE, MethodInfo::typeAnnotations)
                        .get(TYPE_USE_ONLY_CONSTRUCTOR));
    }

    @Test
    @DisplayName("a constructor numbers its parameters in the parameters each index records")
    void constructorParameters() {
        assertEquals("[PARAMETER 0 0 [] A{}]", described(method(PROCESSOR.get("Outer.Inner"), "<init>")));
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
