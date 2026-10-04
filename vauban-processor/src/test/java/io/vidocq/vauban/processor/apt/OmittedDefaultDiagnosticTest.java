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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the processor reports an inherited default method a rendered client proxy leaves out
 * (BUG-20261004-02): the default {@code label(T)} of {@code lib.Labeled}, bound by
 * {@code lib.HiddenBase} to the package-private {@code lib.Hidden}, has the member signature
 * {@code label(lib.Hidden)}, which no other package can write. A bean's proxy is rendered in the
 * bean's package and the warning sits on the bean; a producer's proxy is rendered in the
 * producer's package (#42), so the warning sits on the producer method, and names the produced
 * type as such — the produced type is usually a class of a dependency, with no source to point at.
 */
@DisplayName("Omitted default method — the warning names and points at what the user wrote")
class OmittedDefaultDiagnosticTest {

    @TempDir
    Path tempDir;

    private static final String LIB_LABELED = """
            package lib;
            public interface Labeled<T> {
                default String label(T value) { return "label " + value; }
            }
            """;

    private static final String LIB_HIDDEN = """
            package lib;
            class Hidden {
            }
            """;

    private static final String LIB_HIDDEN_BASE = """
            package lib;
            public class HiddenBase implements Labeled<Hidden> {
            }
            """;

    private static final String LIB_PRODUCED = """
            package lib;
            public class Produced extends HiddenBase {
                public String own() { return "own"; }
            }
            """;

    private static final String APP_PRODUCER = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Produces;
            @Dependent
            public class Producer {
                @Produces @ApplicationScoped
                public lib.Produced produced() { return new lib.Produced(); }
            }
            """;

    private static final String APP_SCOPED_BEAN = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class ScopedBean extends lib.HiddenBase {
                public String own() { return "own"; }
            }
            """;

    @Test
    @DisplayName("on the bean for a bean's proxy, on the producer method for a producer's proxy")
    void warningsPointAtTheBeanAndTheProducerMethod() throws Exception {
        var lib = compileDependency(LIB_LABELED, LIB_HIDDEN, LIB_HIDDEN_BASE, LIB_PRODUCED);
        var warnings = compileAppWarnings(lib, APP_PRODUCER, APP_SCOPED_BEAN);

        var bean = only(warnings, "app.ScopedBean:");
        assertNotNull(bean.getSource(), "the bean's warning must point at its source");
        assertTrue(bean.getSource().getName().endsWith("ScopedBean.java"), bean.getSource().getName());
        var beanMessage = bean.getMessage(null);
        assertTrue(beanMessage.contains("the client proxy does not forward")
                && beanMessage.contains("its signature as a member of the bean"), beanMessage);

        var producer = only(warnings, "lib.Produced:");
        assertNotNull(producer.getSource(), "the producer's warning must point at a source, got: "
                + producer.getMessage(null));
        assertTrue(producer.getSource().getName().endsWith("Producer.java"), producer.getSource().getName());
        assertEquals(lineOf(APP_PRODUCER, "public lib.Produced produced()"), producer.getLineNumber(),
                "the warning must sit on the producer method");
        var producerMessage = producer.getMessage(null);
        assertTrue(producerMessage.contains("the producer's client proxy does not forward")
                && producerMessage.contains("its signature as a member of the produced type")
                && !producerMessage.contains("member of the bean"), producerMessage);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static Diagnostic<? extends JavaFileObject> only(List<Diagnostic<? extends JavaFileObject>> warnings,
            String prefix) {
        var matching = warnings.stream().filter(d -> d.getMessage(null).contains("[Vauban] " + prefix)).toList();
        assertEquals(1, matching.size(), "one warning for " + prefix + ", got: "
                + warnings.stream().map(d -> d.getMessage(null)).toList());
        return matching.getFirst();
    }

    private static long lineOf(String source, String text) {
        var lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(text)) return i + 1;
        }
        throw new AssertionError(text + " not found");
    }

    private Path compileDependency(String... sources) throws IOException {
        Path out = tempDir.resolve("lib-classes");
        Files.createDirectories(out);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var options = List.of("-d", out.toString(), "--release", "25",
                "-classpath", compilationClasspath(), "-proc:none");
        try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null,
                    writeSources(tempDir.resolve("lib-src"), sources));
            assertTrue(task.call(), "the dependency must compile: " + diagnostics.getDiagnostics());
        }
        return out;
    }

    /** Compiles the application with the processor; it must compile, and its warnings are returned. */
    private List<Diagnostic<? extends JavaFileObject>> compileAppWarnings(Path dependencyClasses, String... sources)
            throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path out = tempDir.resolve("app-classes");
        Files.createDirectories(out);
        var options = List.of("-d", out.toString(), "--release", "25",
                "-classpath", dependencyClasses + File.pathSeparator + compilationClasspath(),
                "-proc:full");
        try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null,
                    writeSources(tempDir.resolve("app-src"), sources));
            task.setProcessors(List.of(new VaubanProcessor()));
            assertTrue(task.call(), "the application must compile: " + diagnostics.getDiagnostics());
        }
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .toList();
    }

    private List<JavaFileObject> writeSources(Path root, String... sources) throws IOException {
        var files = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            var pkg = group(Pattern.compile("package\\s+([\\w.]+)\\s*;"), source);
            var name = group(Pattern.compile("(?:class|interface|record|enum)\\s+(\\w+)"), source);
            Path dir = pkg == null ? root : root.resolve(pkg.replace('.', '/'));
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".java");
            Files.writeString(file, source);
            files.add(new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                    return Files.readString(file);
                }
            });
        }
        return files;
    }

    /**
     * Under surefire the dependencies sit on the module path, not on {@code java.class.path}:
     * hand javac the class path plus every {@code file:} location of the boot layer.
     */
    private static String compilationClasspath() {
        var paths = new java.util.LinkedHashSet<String>();
        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            java.util.Collections.addAll(paths, cp.split(File.pathSeparator));
        }
        ModuleLayer.boot().configuration().modules().forEach(resolved ->
                resolved.reference().location()
                        .filter(uri -> "file".equals(uri.getScheme()))
                        .ifPresent(uri -> paths.add(Path.of(uri).toString())));
        return String.join(File.pathSeparator, paths);
    }

    private static String group(Pattern pattern, String source) {
        var m = pattern.matcher(source);
        return m.find() ? m.group(1) : null;
    }
}
