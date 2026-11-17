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
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-BCE-007 (vauban#207): the annotation processor records the type parameters a class declares as the bytecode
 * scan reads them from the class signature, since build compatible extensions read them through the same index at
 * build time and at run time. So does it the supertypes as the class declares them, with their type arguments
 * (BUG-20261117-22). The bytecode scan of the very classes the processor scanned is the reference.
 */
@DisplayName("vauban#207: the processor and the bytecode scan agree on class type parameters and generic supertypes")
class ElementScannerTypeParametersTest {

    private static final String SOURCE = """
            package app;

            import java.util.List;
            import java.util.Map;

            public class Generic<T, N extends Number & Comparable<N>,
                    W extends Map<? extends CharSequence, ? super Integer>,
                    E extends Map.Entry<int[][], List<?>>, C extends CharSequence, U extends T> {

                public class Inner<P extends N> implements Labelled<N> {
                }

                public static class Base<B> {
                }

                public static class Extending extends Base<List<String>> {
                }

                public interface Pair<K, V> {
                }

                public interface Labelled<L> {
                }

                public interface Archive extends Pair<String, Long>, Labelled<List<String>> {
                }

                public abstract static class Ranked<R extends Number & Comparable<R>> implements Comparable<R> {
                }

                public abstract static class Shapes
                        implements Labelled<Map<? extends Number, ? super Integer>>, Pair<String[], int[][]> {
                }

                @SuppressWarnings("rawtypes")
                public static class Raw extends java.util.ArrayList {
                }

                public enum Level {
                    LOW
                }

                public abstract static class Wild implements Labelled<List<?>>,
                        Pair<List<? extends Object>, List<? super Integer>> {
                    public List<?> unbounded;
                    public List<? extends Object> extendsObject;

                    public abstract List<? extends Object> echo(List<?> list);
                }
            }
            """;

    private static final String WILD = "Generic.Wild";
    /** The classes the processor and the bytecode scan both read, by their canonical names under {@code app}. */
    private static final List<String> CLASSES = List.of("Generic", "Generic.Inner", "Generic.Extending",
            "Generic.Archive", "Generic.Ranked", "Generic.Shapes", "Generic.Raw", "Generic.Level", WILD);

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
            if (scanned.isEmpty() && elements.getTypeElement("app.Generic") != null) {
                CLASSES.forEach(name -> scanned.put(name, scanner.scan(elements.getTypeElement("app." + name))));
            }
            return false;
        }
    }

    @BeforeAll
    static void compileAndScanBothWays(@TempDir Path tempDir) throws IOException {
        var sourceFile = Files.createDirectories(tempDir.resolve("src/app")).resolve("Generic.java");
        Files.writeString(sourceFile, SOURCE);
        var classes = Files.createDirectories(tempDir.resolve("classes"));

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var capture = new Capture();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var source = new SimpleJavaFileObject(sourceFile.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                    return Files.readString(sourceFile);
                }
            };
            var task = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-d", classes.toString(), "--release", "25", "-proc:full"), null, List.of(source));
            task.setProcessors(List.of(capture));
            assertTrue(task.call(), "the fixture must compile: " + diagnostics.getDiagnostics());
        }

        assertEquals(CLASSES.size(), capture.scanned.size(), "the capturing processor must have scanned the fixture");
        PROCESSOR.putAll(capture.scanned);
        for (var name : CLASSES) {
            BYTECODE.put(name, ClassFileScanner.scan(Files.readAllBytes(
                    classes.resolve("app/" + name.replace('.', '$') + ".class"))));
        }
    }

    @Test
    @DisplayName("guard: the bytecode scan records the type parameters")
    void bytecodeReferenceRecordsTypeParameters() {
        assertEquals(List.of("T", "N", "W", "E", "C", "U"),
                BYTECODE.get("Generic").typeParameters().stream().map(TypeInfo.TypeVariable::name).toList());
    }

    @Test
    @DisplayName("type parameters with class, interface, recursive, wildcard, array and nested bounds")
    void typeParametersAndBounds() {
        assertEquals(BYTECODE.get("Generic").typeParameters(), PROCESSOR.get("Generic").typeParameters());
    }

    @Test
    @DisplayName("a bound naming a type parameter of the enclosing class")
    void boundNamingAnEnclosingTypeParameter() {
        assertNotNull(PROCESSOR.get("Generic.Inner"));
        assertEquals(BYTECODE.get("Generic.Inner").typeParameters(), PROCESSOR.get("Generic.Inner").typeParameters());
    }

    @Test
    @DisplayName("guard: the bytecode scan records the type arguments of the supertypes (BUG-20261117-22)")
    void bytecodeReferenceRecordsGenericSupertypes() {
        assertAll(() -> assertInstanceOf(TypeInfo.ParameterizedType.class,
                        BYTECODE.get("Generic.Extending").genericSuperclass()),
                () -> assertInstanceOf(TypeInfo.ParameterizedType.class,
                        BYTECODE.get("Generic.Archive").genericInterfaces().get(1)),
                () -> assertInstanceOf(TypeInfo.ClassType.class, BYTECODE.get("Generic.Raw").genericSuperclass()));
    }

    /**
     * The supertypes as each class declares them: a superclass with arguments, two interfaces in declaration order, a
     * type variable of the class with its bounds and one of the enclosing class without them, wildcards inside an
     * argument, nested and array arguments, a raw superclass and an enum's {@code Enum<E>}. An interface's superclass
     * is left out: the compiler gives an interface none and its class file names {@code Object}, which the language
     * model hides (BUG-20261117-32).
     */
    @Test
    @DisplayName("the supertypes as each class declares them, with their type arguments (BUG-20261117-22)")
    void genericSupertypes() {
        assertAll(CLASSES.stream().<Executable>map(name -> () -> {
            var processor = PROCESSOR.get(name);
            var bytecode = BYTECODE.get(name);
            assertEquals(bytecode.genericInterfaces(), processor.genericInterfaces(), name);
            if (!bytecode.isInterface()) {
                assertEquals(bytecode.genericSuperclass(), processor.genericSuperclass(), name);
            }
        }));
    }

    /**
     * {@code ?} and {@code ? extends Object} are one wildcard, which the index records without bounds, as the
     * bytecode scan and reflection read both: in a supertype's arguments and in the types of the fields, parameters
     * and return types alike. The processor recorded {@code Object} as the upper bound of the second
     * (BUG-20261117-34).
     */
    @Test
    @DisplayName("? extends Object is the unbounded wildcard, in the supertypes and the members (BUG-20261117-34)")
    void extendsObjectIsTheUnboundedWildcard() {
        var processor = PROCESSOR.get(WILD);
        var bytecode = BYTECODE.get(WILD);
        var listOfAny = new TypeInfo.ParameterizedType(DotName.of("java.util.List"),
                List.of(new TypeInfo.WildcardType(null, null)));
        assertAll(() -> assertEquals(listOfAny, fieldType(bytecode, "extendsObject"), "guard: the bytecode scan"),
                () -> assertEquals(bytecode.genericInterfaces(), processor.genericInterfaces()),
                () -> assertEquals(fieldType(bytecode, "unbounded"), fieldType(processor, "unbounded")),
                () -> assertEquals(fieldType(bytecode, "extendsObject"), fieldType(processor, "extendsObject")),
                () -> assertEquals(echo(bytecode).returnType(), echo(processor).returnType()),
                () -> assertEquals(echo(bytecode).parameters().getFirst().type(),
                        echo(processor).parameters().getFirst().type()));
    }

    private static TypeInfo fieldType(ClassInfo type, String name) {
        return type.fields().stream().filter(field -> field.name().equals(name)).findFirst().orElseThrow().type();
    }

    private static MethodInfo echo(ClassInfo type) {
        return type.methods().stream().filter(method -> method.name().equals("echo")).findFirst().orElseThrow();
    }
}
