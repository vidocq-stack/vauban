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
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor validates the deployment at compile time. A qualifier declared in the module being
 * compiled must take part in that validation, as it does at run time: here {@code @Channel("card")}
 * selects one of two {@code Payment} beans. A test disabled with a BUG id reproduces a defect logged in
 * {@code BUG.md}.
 */
@DisplayName("vauban#70 safety net: compile-time validation knows the module's own qualifiers")
class SameModuleQualifierValidationTest {

    private static final String SOURCE = """
            package app;

            import jakarta.enterprise.context.Dependent;
            import jakarta.inject.Inject;
            import jakarta.inject.Qualifier;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            @Qualifier
            @Retention(RetentionPolicy.RUNTIME)
            @interface Channel {
                String value();
            }

            interface Payment {
            }

            @Channel("card") @Dependent class CardPayment implements Payment {
            }

            @Channel("wire") @Dependent class WirePayment implements Payment {
            }

            @Dependent
            public class Checkout {
                @Inject @Channel("card") Payment card;
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("control: the fixture compiles once compile-time validation is off")
    void fixtureCompilesWithoutValidation() throws IOException {
        var result = compile("-Avauban.validation=false");
        assertTrue(result.success(), "the fixture itself must compile: " + result.messages());
    }

    @Test
    @DisplayName("a qualifier declared in the compiled module disambiguates an injection point")
    @Disabled("BUG-20260914-13: compile-time validation does not know qualifiers declared in the compiled module")
    void sameModuleQualifier() throws IOException {
        var result = compile();
        assertTrue(result.success(), "@Channel(\"card\") selects CardPayment alone: " + result.messages());
    }

    // ---- minimal in-process compilation harness ----

    private CompilationResult compile(String... extraOptions) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var file = Files.createDirectories(tempDir.resolve("src/app")).resolve("Checkout.java");
        Files.writeString(file, SOURCE);
        var sourceFile = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return Files.readString(file);
            }
        };

        var options = new ArrayList<>(List.of(
                "-d", Files.createDirectories(tempDir.resolve("classes")).toString(),
                "-s", Files.createDirectories(tempDir.resolve("gen")).toString(),
                "--release", "25",
                "-classpath", compilationClasspath(),
                "-proc:full"));
        options.addAll(List.of(extraOptions));

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null, List.of(sourceFile));
            task.setProcessors(List.of(new VaubanProcessor()));
            var success = task.call();
            var messages = new ArrayList<String>();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                messages.add(diagnostic.getKind() + ": " + diagnostic.getMessage(null));
            }
            return new CompilationResult(success, messages);
        }
    }

    record CompilationResult(boolean success, List<String> messages) {
    }

    private static String compilationClasspath() {
        var paths = new LinkedHashSet<String>();
        var classpath = System.getProperty("java.class.path");
        if (classpath != null && !classpath.isBlank()) {
            paths.addAll(List.of(classpath.split(File.pathSeparator)));
        }
        for (var type : List.of(Dependent.class, jakarta.inject.Inject.class, VaubanProcessor.class)) {
            try {
                var location = type.getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    paths.add(Path.of(location.toURI()).toString());
                }
            } catch (Exception ignored) {
                // best-effort classpath assembly
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(module ->
                module.reference().location().ifPresent(uri -> {
                    if ("file".equals(uri.getScheme())) {
                        paths.add(Path.of(uri).toString());
                    }
                }));
        return String.join(File.pathSeparator, paths);
    }
}
