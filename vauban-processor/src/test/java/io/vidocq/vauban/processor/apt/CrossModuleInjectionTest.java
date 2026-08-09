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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Injecting a bean that lives in a <strong>dependency</strong> must compile.
 *
 * <h2>Context</h2>
 * The processor's index only ever contained the types of the current compilation: beans coming
 * from a dependency were invisible to it, so a plain {@code @Inject Greeter} across a module
 * boundary failed the build with {@code [Vauban] Unsatisfied dependency} — while the very same
 * injection point resolved perfectly at runtime, where the container merges the
 * {@code vauban-beans.list} of every archive. A false negative, reported as Vidocq/vauban#23.
 *
 * <p>It was viral: every cross-module injection point had to be wrapped in {@code Instance<T>},
 * losing the compile-time checking that is the headline benefit of build-time CDI — and that
 * workaround is not even available for a third-party module's beans, which are not ours to
 * restructure.
 *
 * <h2>After the patch</h2>
 * Types the current compilation does not declare are resolved lazily through {@code Elements},
 * which sees the whole compile classpath. What remains unresolved after that (a producer declared
 * in another module, say) is reported as a warning rather than an error: the runtime container
 * re-validates on start and is the authority for anything outside this compilation.
 */
@DisplayName("Cross-module injection — a bean from a dependency resolves at compile time")
class CrossModuleInjectionTest {

    @TempDir
    Path tempDir;

    private static final String GREETER = """
            package io.repro.core;

            import jakarta.enterprise.context.ApplicationScoped;

            @ApplicationScoped
            public class Greeter {
                public String greet() { return "hello from core"; }
            }
            """;

    @Test
    @DisplayName("@Inject of an @ApplicationScoped bean compiled in a dependency succeeds")
    void injectingABeanFromADependencyCompiles() throws IOException {
        Path core = compileDependency(GREETER);

        var result = compileApp(core, """
                package io.repro.app;

                import io.repro.core.Greeter;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class HelloResource {
                    @Inject
                    Greeter greeter;

                    public String hello() { return greeter.greet(); }
                }
                """);

        assertTrue(result.success(),
                "a bean from a dependency must satisfy the injection point. Messages: " + result.messages());
        assertFalse(hasVaubanError(result, "Unsatisfied dependency"),
                "no unsatisfied-dependency error expected: " + result.messages());
    }

    @Test
    @DisplayName("a genuinely missing bean is still a compile error")
    void unresolvableInjectionPointIsStillAnError() throws IOException {
        Path core = compileDependency(GREETER);

        var result = compileApp(core, """
                package io.repro.app;

                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class HelloResource {
                    @Inject
                    java.util.concurrent.ExecutorService missing;

                    public String hello() { return String.valueOf(missing); }
                }
                """);

        assertFalse(result.success(),
                "a type that is no bean anywhere must keep failing the build: " + result.messages());
        assertTrue(hasVaubanError(result, "Unsatisfied dependency"),
                "the in-module check must stay strict: " + result.messages());
    }

    @Test
    @DisplayName("an interface whose implementation lives in another module is deferred, not rejected")
    void abstractTypeFromADependencyIsDeferredToTheContainer() throws IOException {
        Path core = compileDependency("""
                package io.repro.core;

                public interface TokenService {
                    String issue();
                }
                """);

        var result = compileApp(core, """
                package io.repro.app;

                import io.repro.core.TokenService;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class HelloResource {
                    @Inject
                    TokenService tokens;

                    public String hello() { return tokens.issue(); }
                }
                """);

        // This compilation cannot see who implements the interface; the container can, and
        // re-validates on start. Failing the build here would be the same false negative.
        assertTrue(result.success(),
                "an unimplemented-here interface must not fail the build: " + result.messages());
        assertTrue(result.messages().stream()
                        .anyMatch(m -> m.startsWith("WARNING") && m.contains("[Vauban]")
                                && m.contains("deferred to the runtime container")),
                "the deferral must be visible as a warning: " + result.messages());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** Compiles the «dependency module» on its own, without the processor, and returns its classes dir. */
    private Path compileDependency(String... sources) throws IOException {
        Path out = tempDir.resolve("core-classes");
        Files.createDirectories(out);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var options = List.of(
                "-d", out.toString(),
                "--release", "25",
                "-classpath", resolveCompilationClasspath(),
                "-proc:none");

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null,
                    writeSources(tempDir.resolve("core-src"), sources));
            assertTrue(task.call(), "the dependency module must compile: " + diagnostics.getDiagnostics());
        }
        return out;
    }

    /** Compiles the «application module» with the processor, the dependency on its classpath. */
    private CompilationResult compileApp(Path dependencyClasses, String... sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        Path out = tempDir.resolve("app-classes");
        Files.createDirectories(out);

        var options = List.of(
                "-d", out.toString(),
                "--release", "25",
                "-classpath", dependencyClasses + File.pathSeparator + resolveCompilationClasspath(),
                "-proc:full");

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null,
                    writeSources(tempDir.resolve("app-src"), sources));
            task.setProcessors(List.of(new VaubanProcessor()));
            boolean success = task.call();

            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }
            return new CompilationResult(success, messages);
        }
    }

    private static boolean hasVaubanError(CompilationResult result, String fragment) {
        return result.messages().stream()
                .anyMatch(m -> m.startsWith("ERROR") && m.contains("[Vauban]") && m.contains(fragment));
    }

    private List<JavaFileObject> writeSources(Path root, String... sources) throws IOException {
        var files = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            String pkg = extractPackageName(source);
            Path dir = pkg == null ? root : root.resolve(pkg.replace('.', '/'));
            Files.createDirectories(dir);
            Path file = dir.resolve(extractClassName(source) + ".java");
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

    private static String extractClassName(String source) {
        var matcher = java.util.regex.Pattern
                .compile("(?:class|interface|record|enum)\\s+(\\w+)").matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("no type declaration in source");
        return matcher.group(1);
    }

    private static String extractPackageName(String source) {
        var matcher = java.util.regex.Pattern.compile("package\\s+([\\w.]+);").matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The jakarta + vauban artefacts the compiled sources need. Surefire runs this suite on the
     * module path, so {@code java.class.path} alone does not carry them — the boot layer does.
     */
    private static String resolveCompilationClasspath() {
        var paths = new java.util.LinkedHashSet<String>();

        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            java.util.Collections.addAll(paths, cp.split(File.pathSeparator));
        }

        for (var clazz : List.of(jakarta.enterprise.context.ApplicationScoped.class,
                jakarta.inject.Inject.class,
                jakarta.interceptor.Interceptor.class)) {
            try {
                var location = clazz.getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    paths.add(Path.of(location.toURI()).toString());
                }
            } catch (Exception ignored) {
                // A class loaded from the platform layer has no file location — nothing to add.
            }
        }

        ModuleLayer.boot().configuration().modules().forEach(resolved ->
                resolved.reference().location().ifPresent(uri -> {
                    try {
                        if ("file".equals(uri.getScheme())) {
                            paths.add(Path.of(uri).toString());
                        }
                    } catch (Exception ignored) {
                        // Non-file module reference (jrt:) — not a classpath entry.
                    }
                }));

        return String.join(File.pathSeparator, paths);
    }

    record CompilationResult(boolean success, List<String> messages) {}
}
