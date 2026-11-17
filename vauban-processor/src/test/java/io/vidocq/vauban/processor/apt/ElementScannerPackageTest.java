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
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor indexes a package as the bytecode scan reads its {@code package-info} class, so that discovery reads
 * the package's {@code @Vetoed} the same way on both sides (BUG-20261117-01).
 */
@DisplayName("The processor and the bytecode scan agree on a package-info")
class ElementScannerPackageTest {

    @TempDir
    Path workDir;

    /** Scans the package of the compiled class with the processor's own scanner. */
    @SupportedAnnotationTypes("*")
    static final class Capture extends AbstractProcessor {
        ClassInfo packageInfo;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            var elements = processingEnv.getElementUtils();
            var type = elements.getTypeElement("vi.quiet.Quiet");
            if (packageInfo == null && type != null) {
                packageInfo = new ElementScanner(elements, processingEnv.getTypeUtils())
                        .scan(elements.getPackageOf(type));
            }
            return false;
        }
    }

    @Test
    @DisplayName("a package becomes the synthetic interface package-info, with the package's annotations")
    void aPackageIsScannedAsItsPackageInfoClass() throws IOException {
        var capture = new Capture();
        var result = ProcessorCompiler.compile(workDir, Map.of(
                "vi/quiet/package-info.java", "@jakarta.enterprise.inject.Vetoed\npackage vi.quiet;\n",
                "vi/quiet/Quiet.java", "package vi.quiet;\npublic class Quiet {\n}\n"), List.of(), capture);
        assertTrue(result.success(), () -> "compilation failed: " + result.messages());

        ClassInfo processor = capture.packageInfo;
        ClassInfo bytecode = ClassFileScanner.scan(
                Files.readAllBytes(result.classes().resolve("vi/quiet/package-info.class")));

        assertNotNull(processor, "the processor saw the package");
        assertEquals(bytecode.name(), processor.name());
        assertEquals(bytecode.superName(), processor.superName());
        assertEquals(bytecode.interfaces(), processor.interfaces());
        assertEquals(bytecode.accessFlags(), processor.accessFlags());
        assertEquals(bytecode.kind(), processor.kind());
        assertEquals(bytecode.simpleName(), processor.simpleName());
        assertEquals(List.of("jakarta.enterprise.inject.Vetoed"), annotationNames(processor));
        assertEquals(annotationNames(bytecode), annotationNames(processor));
        assertTrue(processor.isInterface(), "no bean candidate");
    }

    private static List<String> annotationNames(ClassInfo classInfo) {
        return classInfo.annotations().stream().map(AnnotationInfo::name).map(Object::toString).toList();
    }
}
