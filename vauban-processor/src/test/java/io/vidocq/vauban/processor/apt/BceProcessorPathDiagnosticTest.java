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
 * A Build Compatible Extension shipped in a <strong>dependency</strong> jar is invisible to the
 * annotation processor when Maven's {@code <annotationProcessorPaths>} narrows javac's
 * {@code -processorpath} — the APT's {@code ServiceLoader} only sees the processor path. The BCE
 * silently never runs, and whatever beans it would have contributed are missing, surfacing much
 * later as a plain {@code Unsatisfied dependency} error with no hint of the cause
 * (Vidocq/vauban#29, reported through a Mansart support case: Vidocq/mansart#9).
 *
 * <p>The processor cannot <em>load</em> those extensions — javac gives it no classloader over the
 * compile path — but it can <em>detect</em> them: the service declarations are visible as
 * {@code META-INF/services} resources of the compile classpath and as {@code provides} directives
 * of the observable modules. This suite pins the two diagnostics built on that detection:
 * an upfront warning naming the unloadable extensions, and a hint appended to
 * unsatisfied-dependency errors while such extensions exist.
 */
@DisplayName("BCE on the compile path but not on the processor path — diagnostics")
class BceProcessorPathDiagnosticTest {

    private static final String BCE_SERVICE =
            "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    @TempDir
    Path tempDir;

    private static final String FAKE_BCE = """
            package io.repro.core;

            import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

            public class FakeBce implements BuildCompatibleExtension {
            }
            """;

    /** Concrete final type: the BCE would contribute its bean, so no deferral applies. */
    private static final String REPO_RUNTIME = """
            package io.repro.core;

            public final class RepoRuntime {
                public String ping() { return "pong"; }
            }
            """;

    @Test
    @DisplayName("an unloadable BCE from the compile path is reported as a warning")
    void unloadableBceIsWarned() throws IOException {
        Path core = compileDependency(FAKE_BCE);
        declareBceService(core, "io.repro.core.FakeBce");

        var result = compileApp(core, """
                package io.repro.app;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class Standalone {
                    public String ok() { return "ok"; }
                }
                """);

        assertTrue(result.success(),
                "a healthy compilation must still succeed: " + result.messages());
        assertTrue(result.messages().stream()
                        .anyMatch(m -> m.startsWith("WARNING") && m.contains("[Vauban]")
                                && m.contains("io.repro.core.FakeBce")
                                && m.contains("annotationProcessorPaths")),
                "the unloadable BCE must be named in a warning suggesting "
                        + "<annotationProcessorPaths>: " + result.messages());
    }

    @Test
    @DisplayName("an unsatisfied-dependency error carries the unloadable-BCE hint")
    void unsatisfiedDependencyErrorCarriesTheHint() throws IOException {
        Path core = compileDependency(FAKE_BCE, REPO_RUNTIME);
        declareBceService(core, "io.repro.core.FakeBce");

        var result = compileApp(core, """
                package io.repro.app;

                import io.repro.core.RepoRuntime;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class NeedsRuntime {
                    @Inject
                    RepoRuntime runtime;

                    public String hello() { return runtime.ping(); }
                }
                """);

        assertFalse(result.success(),
                "the unsatisfied concrete dependency must still fail the build: " + result.messages());
        assertTrue(result.messages().stream()
                        .anyMatch(m -> m.startsWith("ERROR") && m.contains("[Vauban]")
                                && m.contains("Unsatisfied dependency")
                                && m.contains("io.repro.core.FakeBce")),
                "the error must hint at the unloadable BCE that may contribute the bean: "
                        + result.messages());
    }

    @Test
    @DisplayName("no BCE anywhere: no warning (zero noise)")
    void noBceMeansNoWarning() throws IOException {
        Path core = compileDependency(REPO_RUNTIME);

        var result = compileApp(core, """
                package io.repro.app;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class Standalone {
                    public String ok() { return "ok"; }
                }
                """);

        assertTrue(result.success(), "compilation must succeed: " + result.messages());
        assertFalse(result.messages().stream()
                        .anyMatch(m -> m.startsWith("WARNING") && m.contains("[Vauban]")
                                && m.contains("annotationProcessorPaths")),
                "no processor-path warning expected when no BCE is around: " + result.messages());
    }

    /**
     * A module that provides its own extension, as foy-cdi-vauban provides {@code FoyWebExtension}: Maven copies the
     * resources before it compiles, and puts the output directory on the class path, so the module's own service
     * file is there, at the main compilation and at the test compilation alike. The extension runs at the build of
     * the applications, not at its own: the warning, whose advice cannot be followed, is not for it
     * (BUG-20261115-05). An extension of another module still is.
     */
    @Test
    @DisplayName("a module's own BCE, its service file on the class path output, is not reported, at the main or the "
            + "test compilation; another module's is")
    void aModulesOwnBceIsNotReported() throws Exception {
        var other = compileModule("other", List.of(), "-proc:none", """
                module repro.other {
                    requires jakarta.cdi;
                    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
                            with io.repro.other.OtherExtension;
                }
                """, """
                package io.repro.other;

                public class OtherExtension
                        implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {
                }
                """);
        assertTrue(other.success(), "the other module must compile: " + other.messages());
        declareBceService(classesOf("ext"), "io.repro.ext.OwnExtension\nio.repro.ext.OwnExtension$Nested");

        var result = compileModule("ext", List.of(classesOf("other")), "-proc:full", """
                module repro.ext {
                    requires jakarta.cdi;
                    requires io.vidocq.vauban.api;
                    requires repro.other;
                    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
                            with io.repro.ext.OwnExtension, io.repro.ext.OwnExtension.Nested;
                    provides io.vidocq.vauban.api.VaubanComponentProvider with io.repro.ext._VaubanComponents;
                }
                """, """
                package io.repro.ext;

                public class OwnExtension
                        implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {

                    public static class Nested
                            implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {
                    }
                }
                """, """
                package io.repro.ext;

                @jakarta.enterprise.context.ApplicationScoped
                public class Holder {
                    public String ok() { return "ok"; }
                }
                """);

        assertTrue(result.success(), "the module must compile: " + result.messages());
        assertReportsOnlyTheOtherModulesExtension(result);

        // The module's tests, in Maven's layout: test-classes next to classes, which is on the class path with its
        // service file; the other module on the module path.
        Path testClasses = Files.createDirectories(tempDir.resolve("ext").resolve("test-classes"));
        var modulePath = new ArrayList<>(apiModulePath());
        modulePath.add(classesOf("other").toString());
        var tests = compileWithVauban(writeSources(tempDir.resolve("ext-test-src"), """
                package io.repro.ext;

                @jakarta.enterprise.context.ApplicationScoped
                public class HolderClient {
                    @jakarta.inject.Inject Holder holder;
                }
                """), "-d", testClasses.toString(), "--release", "25", "-proc:full",
                "--module-path", String.join(File.pathSeparator, modulePath), "--add-modules", "ALL-MODULE-PATH",
                "-classpath", testClasses + File.pathSeparator + classesOf("ext"));

        assertTrue(tests.success(), "the tests must compile: " + tests.messages());
        assertReportsOnlyTheOtherModulesExtension(tests);
    }

    /** {@code io.repro.other}'s extension is reported as missing from the processor path, {@code io.repro.ext}'s not. */
    private static void assertReportsOnlyTheOtherModulesExtension(CompilationResult result) {
        var unloadable = result.messages().stream()
                .filter(m -> m.startsWith("WARNING") && m.contains("NOT on the annotation-processor path"))
                .toList();
        assertTrue(unloadable.stream().anyMatch(m -> m.contains("io.repro.other.OtherExtension")),
                "another module's extension the processor path lacks is still reported: " + result.messages());
        assertFalse(unloadable.stream().anyMatch(m -> m.contains("io.repro.ext.OwnExtension")),
                "the module's own extensions, a member class among them, are not reported: " + unloadable);
    }

    // ---- Helpers (same harness as CrossModuleInjectionTest) ------------------------------------

    /**
     * Compiles the named module of {@code sources} into {@link #classesOf}, with Vauban's processor unless
     * {@code proc} is {@code -proc:none}: the APIs and {@code modulePath} on the module path, the output directory on
     * the class path, as Maven lays a module's build out.
     */
    private CompilationResult compileModule(String name, List<Path> modulePath, String proc, String... sources)
            throws Exception {
        var paths = new ArrayList<>(apiModulePath());
        modulePath.forEach(path -> paths.add(path.toString()));
        Path out = Files.createDirectories(classesOf(name));
        return compileWithVauban(writeSources(tempDir.resolve(name + "-src"), sources), "-d", out.toString(),
                "--release", "25", proc, "--module-path", String.join(File.pathSeparator, paths),
                "-classpath", out.toString());
    }

    /** The main output of module {@code name}, Maven's {@code classes}. */
    private Path classesOf(String name) {
        return tempDir.resolve(name).resolve("classes");
    }

    /** The APIs the modules compile against, Vauban's and CDI's, as module path entries. */
    private static List<String> apiModulePath() throws java.net.URISyntaxException {
        var paths = new ArrayList<String>();
        for (var clazz : List.of(io.vidocq.vauban.api.VaubanComponentProvider.class,
                jakarta.enterprise.context.ApplicationScoped.class, jakarta.inject.Inject.class,
                jakarta.annotation.Priority.class, jakarta.interceptor.Interceptor.class,
                jakarta.enterprise.lang.model.AnnotationInfo.class)) {
            paths.add(Path.of(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return paths;
    }

    /** Writes the {@code META-INF/services} declaration into the dependency's classes dir. */
    private static void declareBceService(Path classesDir, String implementationFqn)
            throws IOException {
        Path services = classesDir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(BCE_SERVICE), implementationFqn + "\n");
    }

    /** Compiles the «dependency module» on its own, without the processor. */
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
        Path out = tempDir.resolve("app-classes");
        Files.createDirectories(out);

        return compileWithVauban(writeSources(tempDir.resolve("app-src"), sources),
                "-d", out.toString(),
                "--release", "25",
                "-classpath", dependencyClasses + File.pathSeparator + resolveCompilationClasspath(),
                "-proc:full");
    }

    /** Compiles {@code sources} with {@code options} and Vauban's processor; its diagnostics, kind first. */
    private static CompilationResult compileWithVauban(List<JavaFileObject> sources, String... options)
            throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, List.of(options), null, sources);
            task.setProcessors(List.of(new VaubanProcessor()));
            boolean success = task.call();

            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }
            return new CompilationResult(success, messages);
        }
    }

    private record CompilationResult(boolean success, List<String> messages) {
    }

    private List<JavaFileObject> writeSources(Path root, String... sources) throws IOException {
        var files = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            String pkg = extractPackageName(source);
            Path dir = pkg == null ? root : root.resolve(pkg.replace('.', '/'));
            Files.createDirectories(dir);
            String type = source.startsWith("module ") ? "module-info" : extractClassName(source);
            Path file = dir.resolve(type + ".java");
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
                jakarta.interceptor.Interceptor.class,
                jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension.class)) {
            try {
                var location = clazz.getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    paths.add(Path.of(location.toURI()).toString());
                }
            } catch (Exception _) {
                // boot-layer class without a code source — fine
            }
        }

        ModuleLayer.boot().configuration().modules().forEach(resolved ->
                resolved.reference().location().ifPresent(uri -> {
                    try {
                        if ("file".equals(uri.getScheme())) {
                            paths.add(Path.of(uri).toString());
                        }
                    } catch (Exception _) {
                        // Non-file module reference (jrt:) — not a classpath entry.
                    }
                }));

        return String.join(File.pathSeparator, paths);
    }
}
