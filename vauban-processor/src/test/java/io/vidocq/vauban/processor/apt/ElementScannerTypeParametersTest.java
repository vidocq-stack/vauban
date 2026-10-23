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
import io.vidocq.vauban.indexer.model.TypeInfo;
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
 * VAU-BCE-007 (vauban#207): the annotation processor records the type parameters a class declares as the bytecode
 * scan reads them from the class signature, since build compatible extensions read them through the same index at
 * build time and at run time. The bytecode scan of the very classes the processor scanned is the reference.
 */
@DisplayName("vauban#207: the processor and the bytecode scan agree on class type parameters")
class ElementScannerTypeParametersTest {

    private static final String SOURCE = """
            package app;

            import java.util.List;
            import java.util.Map;

            public class Generic<T, N extends Number & Comparable<N>,
                    W extends Map<? extends CharSequence, ? super Integer>,
                    E extends Map.Entry<int[][], List<?>>, C extends CharSequence, U extends T> {

                public class Inner<P extends N> {
                }
            }
            """;

    private static ClassInfo processorGeneric;
    private static ClassInfo bytecodeGeneric;
    private static ClassInfo processorInner;
    private static ClassInfo bytecodeInner;

    /** Scans the fixture classes with the processor's own scanner. */
    @SupportedAnnotationTypes("*")
    static final class Capture extends AbstractProcessor {
        ClassInfo generic;
        ClassInfo inner;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            var elements = processingEnv.getElementUtils();
            var scanner = new ElementScanner(elements, processingEnv.getTypeUtils());
            if (generic == null && elements.getTypeElement("app.Generic") != null) {
                generic = scanner.scan(elements.getTypeElement("app.Generic"));
                inner = scanner.scan(elements.getTypeElement("app.Generic.Inner"));
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

        assertNotNull(capture.generic, "the capturing processor must have scanned app.Generic");
        processorGeneric = capture.generic;
        processorInner = capture.inner;
        bytecodeGeneric = ClassFileScanner.scan(Files.readAllBytes(classes.resolve("app/Generic.class")));
        bytecodeInner = ClassFileScanner.scan(Files.readAllBytes(classes.resolve("app/Generic$Inner.class")));
    }

    @Test
    @DisplayName("guard: the bytecode scan records the type parameters")
    void bytecodeReferenceRecordsTypeParameters() {
        assertEquals(List.of("T", "N", "W", "E", "C", "U"),
                bytecodeGeneric.typeParameters().stream().map(TypeInfo.TypeVariable::name).toList());
    }

    @Test
    @DisplayName("type parameters with class, interface, recursive, wildcard, array and nested bounds")
    void typeParametersAndBounds() {
        assertEquals(bytecodeGeneric.typeParameters(), processorGeneric.typeParameters());
    }

    @Test
    @DisplayName("a bound naming a type parameter of the enclosing class")
    void boundNamingAnEnclosingTypeParameter() {
        assertEquals(bytecodeInner.typeParameters(), processorInner.typeParameters());
    }
}
