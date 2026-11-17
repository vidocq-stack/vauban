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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-31: in the processor, a path from a class the compiler sees to a class it sees nowhere, neither in a
 * source nor in a class file, keeps the answer it gave before the language model described the classes outside the
 * index. {@code superClassDeclaration()} is {@code null}, {@code superInterfacesDeclarations()} leaves the interface
 * out, and an annotation's {@code declaration()} and an enum value's {@code asEnumClass()} throw the
 * {@code IllegalArgumentException} they threw: a consumer keeps its "I cannot see this class" path. A type's
 * {@code declaration()} gives the stub it gave.
 */
@DisplayName("BUG-20261117-31 - in the processor, a class the compiler sees nowhere keeps its old answers")
class UnseenClassProcessorTest {

    private static final String KEPT = "lib.Kept";
    private static final String GONE = "lib.gone.";

    /** A library whose package {@code lib.gone} is deleted once compiled: {@code Kept}'s class file stays. */
    private static final Map<String, String> LIBRARY = Map.of("lib/gone/Base.java", """
            package lib.gone;
            public class Base {
            }
            """, "lib/gone/Contract.java", """
            package lib.gone;
            public interface Contract {
            }
            """, "lib/gone/Marker.java", """
            package lib.gone;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface Marker {
            }
            """, "lib/gone/Grade.java", """
            package lib.gone;
            public enum Grade {
                LOW, HIGH
            }
            """, "lib/gone/Rated.java", """
            package lib.gone;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface Rated {
                Grade value();
            }
            """, "lib/Kept.java", """
            package lib;
            @lib.gone.Marker
            @lib.gone.Rated(lib.gone.Grade.HIGH)
            public class Kept extends lib.gone.Base implements lib.gone.Contract, java.io.Serializable {
            }
            """);

    /** Records, in {@code @Synthesis}, each path from {@code lib.Kept} to a class of {@code lib.gone}. */
    public static class UnseenRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void unseen(Types types) {
            var kept = types.ofClass(KEPT).declaration();
            SEEN.add("superClass().declaration() " + outcome(() -> kept.superClass().asClass().declaration()));
            SEEN.add("superClassDeclaration() " + kept.superClassDeclaration());
            SEEN.add(kept.superInterfacesDeclarations().stream().map(ClassInfo::name)
                    .collect(Collectors.joining(", ", "superInterfacesDeclarations() [", "]")));
            kept.annotations().forEach(annotation -> SEEN.add(annotation.name() + " declaration() "
                    + outcome(annotation::declaration)));
            kept.annotations(annotation -> annotation.name().endsWith("Rated")).forEach(rated -> SEEN.add(
                    "asEnumClass() " + outcome(() -> rated.value().asEnumClass())));
        }

        private static String outcome(Supplier<ClassInfo> path) {
            try {
                return "gives " + path.get().name();
            } catch (RuntimeException e) {
                return "throws " + e.getClass().getName() + ": " + e.getMessage();
            }
        }
    }

    @Test
    @DisplayName("superClassDeclaration() null, the interface left out, declaration() and asEnumClass() throw")
    void aClassTheCompilerSeesNowhereKeepsItsOldAnswers(@TempDir Path directory) throws IOException {
        var library = ProcessorCompiler.compile(directory.resolve("library"), LIBRARY, List.of(), List.of());
        assertTrue(library.success(), () -> "the library must compile: " + library.messages());
        try (Stream<Path> gone = Files.list(library.classes().resolve("lib/gone"))) {
            for (var classFile : gone.toList()) {
                Files.delete(classFile);
            }
        }
        UnseenRecorder.SEEN.clear();
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(UnseenRecorder.class));

        var application = ProcessorCompiler.compile(directory.resolve("application"), Map.of("app/Holder.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Holder {
                }
                """), List.of("-classpath", ProcessorCompiler.classPath() + File.pathSeparator + library.classes()),
                processor);

        assertTrue(application.success(), () -> "the application must compile: " + application.messages());
        var notFound = "throws java.lang.IllegalArgumentException: Class not found in index: " + GONE;
        assertEquals(List.of("superClass().declaration() gives " + GONE + "Base", "superClassDeclaration() null",
                "superInterfacesDeclarations() [java.io.Serializable]", GONE + "Marker declaration() " + notFound
                        + "Marker", GONE + "Rated declaration() " + notFound + "Rated",
                "asEnumClass() " + notFound + "Grade"), List.copyOf(UnseenRecorder.SEEN));
    }
}
