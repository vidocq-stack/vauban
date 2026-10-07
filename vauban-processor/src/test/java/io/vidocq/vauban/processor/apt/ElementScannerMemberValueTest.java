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

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
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
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The annotation processor and the run-time bytecode scan build the same index model, so they must
 * agree on it. Type names are where they can drift, in member values as in supertypes: a nested type
 * has a binary name ({@code app.Holder$Hue}) and a canonical one ({@code app.Holder.Hue}), and a class
 * literal can name a primitive or an array type. vauban#70 keys qualifier matching on these values and
 * on the defaults an annotation type declares, so the processor must produce what the class file
 * carries. The bytecode scan of the very classes the processor scanned is the reference.
 */
@DisplayName("vauban#70 safety net: the processor and the bytecode scan agree on annotation member values")
class ElementScannerMemberValueTest {

    private static final String SOURCE = """
            package app;

            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            public class Holder {
                public enum Hue { RED, BLUE }

                public static class Base {
                }

                public interface Marker {
                }

                @Retention(RetentionPolicy.RUNTIME)
                public @interface Inner {
                    String value();
                }

                @Retention(RetentionPolicy.RUNTIME)
                public @interface Probe {
                    String text();
                    Hue hue();
                    Inner inner();
                    Class<?> nested();
                    Class<?> primitive();
                    Class<?> array();
                    String note() default "n";
                    Hue fallback() default Hue.BLUE;
                    Inner wrapped() default @Inner("d");
                    Class<?> kind() default int[].class;
                    Hue[] hues() default {Hue.RED, Hue.BLUE};
                }

                @Probe(text = "t", hue = Hue.RED, inner = @Inner("x"), nested = Hue.class,
                        primitive = int.class, array = String[].class)
                public static class Target extends Base implements Marker {
                }
            }
            """;

    private static AnnotationInfo processorProbe;
    private static AnnotationInfo bytecodeProbe;
    private static ClassInfo processorProbeType;
    private static ClassInfo bytecodeProbeType;
    private static ClassInfo processorTarget;
    private static ClassInfo bytecodeTarget;

    /** Scans the annotated class and the annotation type with the processor's own scanner. */
    @SupportedAnnotationTypes("*")
    static final class Capture extends AbstractProcessor {
        ClassInfo target;
        ClassInfo probeType;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            var elements = processingEnv.getElementUtils();
            var scanner = new ElementScanner(elements, processingEnv.getTypeUtils());
            if (target == null && elements.getTypeElement("app.Holder.Target") != null) {
                target = scanner.scan(elements.getTypeElement("app.Holder.Target"));
                probeType = scanner.scan(elements.getTypeElement("app.Holder.Probe"));
            }
            return false;
        }
    }

    @BeforeAll
    static void compileAndScanBothWays(@TempDir Path tempDir) throws IOException {
        var sourceFile = Files.createDirectories(tempDir.resolve("src/app")).resolve("Holder.java");
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

        assertNotNull(capture.target, "the capturing processor must have scanned app.Holder.Target");
        processorTarget = capture.target;
        bytecodeTarget = ClassFileScanner.scan(Files.readAllBytes(classes.resolve("app/Holder$Target.class")));
        processorProbe = probe(processorTarget);
        bytecodeProbe = probe(bytecodeTarget);
        processorProbeType = capture.probeType;
        bytecodeProbeType = ClassFileScanner.scan(Files.readAllBytes(classes.resolve("app/Holder$Probe.class")));
    }

    private static AnnotationInfo probe(ClassInfo scanned) {
        return scanned.annotations().stream()
                .filter(annotation -> annotation.name().value().endsWith("Probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no @Probe among " + scanned.annotations()));
    }

    private static AnnotationValue defaultOf(ClassInfo type, String member) {
        return type.methods().stream()
                .filter(method -> method.name().equals(member))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no member " + member + " on " + type.name()))
                .defaultValue();
    }

    @Test
    @DisplayName("guard: the bytecode scan names nested types with their binary name")
    void bytecodeReferenceUsesBinaryNames() {
        assertEquals(DotName.of("app.Holder$Probe"), bytecodeProbe.name());
        assertEquals(new AnnotationValue.ClassVal(DotName.of("app.Holder$Hue")), bytecodeProbe.members().get("nested"));
    }

    @Test
    @DisplayName("String member")
    void stringMember() {
        assertEquals(bytecodeProbe.members().get("text"), processorProbe.members().get("text"));
    }

    @Test
    @DisplayName("annotation type name")
    void annotationTypeName() {
        assertEquals(bytecodeProbe.name(), processorProbe.name());
    }

    @Test
    @DisplayName("enum member of a nested enum type")
    void nestedEnumMember() {
        assertEquals(bytecodeProbe.members().get("hue"), processorProbe.members().get("hue"));
    }

    @Test
    @DisplayName("annotation member of a nested annotation type")
    void nestedAnnotationMember() {
        assertEquals(bytecodeProbe.members().get("inner"), processorProbe.members().get("inner"));
    }

    @Test
    @DisplayName("class literal of a nested type")
    void nestedClassLiteral() {
        assertEquals(bytecodeProbe.members().get("nested"), processorProbe.members().get("nested"));
    }

    @Test
    @DisplayName("class literal of a primitive type")
    void primitiveClassLiteral() {
        assertEquals(bytecodeProbe.members().get("primitive"), processorProbe.members().get("primitive"));
    }

    @Test
    @DisplayName("class literal of an array type")
    void arrayClassLiteral() {
        assertEquals(bytecodeProbe.members().get("array"), processorProbe.members().get("array"));
    }

    @Test
    @DisplayName("member defaults declared by the annotation type")
    void memberDefaults() {
        assertNotNull(defaultOf(bytecodeProbeType, "note"), "guard: the bytecode scan records defaults");
        for (var member : List.of("text", "note", "fallback", "wrapped", "kind", "hues")) {
            assertEquals(defaultOf(bytecodeProbeType, member), defaultOf(processorProbeType, member), member);
        }
    }

    @Test
    @DisplayName("nested superclass and interface, named like the index keys every class")
    void nestedSupertypes() {
        assertEquals(DotName.of("app.Holder$Base"), bytecodeTarget.superName(), "guard: binary name in the class file");
        assertEquals(bytecodeTarget.superName(), processorTarget.superName());
        assertEquals(bytecodeTarget.interfaces(), processorTarget.interfaces());
    }

    @Test
    @DisplayName("simple name of a nested class, without its enclosing class (BUG-20260914-11)")
    void nestedSimpleName() {
        assertEquals("Target", bytecodeTarget.simpleName());
        assertEquals("Target", processorTarget.simpleName());
    }
}
