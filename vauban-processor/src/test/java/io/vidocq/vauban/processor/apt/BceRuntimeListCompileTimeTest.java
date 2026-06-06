package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.processor.VaubanProcessor;
import io.vidocq.vauban.processor.apt.testfixtures.PathLike;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED tests for bug #7 — APT part (compile-time).
 *
 * <h2>Contract under test</h2>
 * When a BCE applies <b>runtime-observable</b> modifications
 * (added annotations, qualifiers) to a class, the {@link VaubanProcessor}
 * must write an entry into {@code META-INF/vauban-bce-runtime.list}:
 *
 * <pre>
 * # Vauban BCE runtime replay list
 * &lt;BCE-FQN&gt;;&lt;target-class-FQN&gt;
 * </pre>
 *
 * <i>Read-only</i> BCEs (which add nothing) do not produce
 * a line in this file — no runtime overhead.
 *
 * <h2>Why these tests are necessary</h2>
 * The {@code vauban-bce-runtime.list} file is the pivot between the APT and the
 * runtime: without it, the {@code VaubanContainerBuilder} does not know which
 * BCEs to replay on which classes, and synthetic annotations
 * (e.g. {@code @RequestScoped} added by {@code CassiniScopeBCE} on a
 * {@code @Path} class) are never visible at runtime.
 *
 * @see io.vidocq.vauban.processor.apt.BceCompileTimeTest for the in-process
 *      compilation pattern.
 */
@DisplayName("BCE runtime list - compile-time writing by VaubanProcessor")
class BceRuntimeListCompileTimeTest {

    /**
     * Expected path of the new file produced by the APT.
     * The implementation MUST expose this path via a public constant
     * (e.g. {@code SyntheticMetadataSerializer.BCE_RUNTIME_LIST_PATH})
     * but as long as the production code is not written, the test
     * uses the literal directly so it is red at the functional-compilation
     * level and not at Java compilation.
     */
    private static final String RUNTIME_LIST_PATH = "META-INF/vauban-bce-runtime.list";

    @TempDir
    Path tempDir;

    // ---------- Annotation trigger ----------
    // See {@link io.vidocq.vauban.processor.apt.testfixtures.PathLike}.
    // Extracted to top-level so it can be referenced from the in-memory
    // compiled sources (the in-process compiler rejects nested
    // annotations declared in the test class itself).

    // ---------- Test BCEs ----------

    /**
     * BCE that adds {@code @RequestScoped} to any class carrying
     * the trigger annotation {@link PathLike}. Simulates {@code CassiniScopeBCE}.
     * <b>Modifies</b> the class → must produce a line in
     * {@code vauban-bce-runtime.list}.
     */
    public static class WritingScopeBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = PathLike.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(RequestScoped.class);
        }
    }

    /**
     * BCE that <b>modifies nothing</b>: it just reads {@code clazz.info()}.
     * Must write nothing into {@code vauban-bce-runtime.list}.
     */
    public static class ReadOnlyBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = PathLike.class)
        public void inspect(ClassConfig clazz) {
            // read-only: we do NOT touch ClassConfig
            clazz.info();
        }
    }

    // ======================================================================
    // Tests
    // ======================================================================

    @Nested
    @DisplayName("Test 1 — BCE that modifies the class → write into the list")
    class WritingBce {

        @Test
        @DisplayName("produces META-INF/vauban-bce-runtime.list with '<BCE>;<target>'")
        void shouldWriteRuntimeListEntryWhenBceAddsAnnotation() throws IOException {
            var result = compileWithBce(
                    List.of(WritingScopeBce.class),
                    """
                    import io.vidocq.vauban.processor.apt.testfixtures.PathLike;

                    @PathLike("/test")
                    public class TestResource {
                        public String hello() { return "hello"; }
                    }
                    """
            );

            assertTrue(result.success(),
                    "Compilation should succeed. Messages: " + result.messages());

            // The file MUST exist after the processor has run
            assertTrue(result.hasFile(RUNTIME_LIST_PATH),
                    "Expected " + RUNTIME_LIST_PATH
                            + " to be generated by VaubanProcessor when BCE modifies a class");

            var entries = result.readRuntimeList();
            var expected = WritingScopeBce.class.getName() + ";TestResource";
            assertTrue(entries.contains(expected),
                    "Expected runtime list to contain \"" + expected + "\". Actual: " + entries);
        }

        @Test
        @DisplayName("ignores comment lines (#)")
        void shouldIgnoreCommentsInRuntimeList() throws IOException {
            var result = compileWithBce(
                    List.of(WritingScopeBce.class),
                    """
                    import io.vidocq.vauban.processor.apt.testfixtures.PathLike;

                    @PathLike("/test")
                    public class TestResource2 {
                        public String hello() { return "hello"; }
                    }
                    """
            );

            assertTrue(result.success(), "Compilation should succeed");
            assertTrue(result.hasFile(RUNTIME_LIST_PATH));

            // The file MUST contain at least one comment header
            var raw = Files.readString(result.outputDir().resolve(RUNTIME_LIST_PATH),
                    StandardCharsets.UTF_8);
            assertTrue(raw.lines().anyMatch(l -> l.trim().startsWith("#")),
                    "Runtime list should contain a comment header (generated by Vauban)");

            // readRuntimeList already filters out # → must contain ONLY the useful line
            var entries = result.readRuntimeList();
            for (var e : entries) {
                assertFalse(e.startsWith("#"),
                        "readRuntimeList should filter out comment lines, but got: " + e);
            }
        }
    }

    @Nested
    @DisplayName("Test 5 — read-only BCE writes nothing")
    class ReadOnly {

        @Test
        @DisplayName("read-only BCE produces no active line in vauban-bce-runtime.list")
        void shouldNotWriteAnyRuntimeEntryForReadOnlyBce() throws IOException {
            // We compile WITH AN ADDITIONAL MODIFYING BCE to guarantee that the
            // file exists, then we verify that the read-only BCE does not appear in it.
            // This is stronger than an "if exists" check that would let a naive
            // implementation that never generates the file pass.
            var result = compileWithBce(
                    List.of(WritingScopeBce.class, ReadOnlyBce.class),
                    """
                    import io.vidocq.vauban.processor.apt.testfixtures.PathLike;

                    @PathLike("/writing")
                    public class WritingTarget {
                        public String hello() { return "hello"; }
                    }
                    """,
                    """
                    import io.vidocq.vauban.processor.apt.testfixtures.PathLike;

                    @PathLike("/readonly")
                    public class ReadOnlyResource {
                        public String hello() { return "hello"; }
                    }
                    """
            );

            assertTrue(result.success(),
                    "Compilation should succeed. Messages: " + result.messages());

            // The modifying BCE must have triggered the creation of the file
            assertTrue(result.hasFile(RUNTIME_LIST_PATH),
                    "Runtime list should exist: WritingScopeBce modifies classes and must register them");

            var entries = result.readRuntimeList();

            // WritingScopeBce must write lines (for both WritingTarget AND ReadOnlyResource,
            // both targeted by withAnnotations=PathLike)
            var writingFqn = WritingScopeBce.class.getName();
            assertTrue(entries.stream().anyMatch(e -> e.startsWith(writingFqn + ";")),
                    "Writing BCE should produce at least one entry. Got: " + entries);

            // ReadOnlyBce must have NO line, even for the class it saw
            var readOnlyFqn = ReadOnlyBce.class.getName();
            assertTrue(entries.stream().noneMatch(e -> e.startsWith(readOnlyFqn + ";")),
                    "Read-only BCE " + readOnlyFqn
                            + " should NOT appear in runtime list. Found entries: " + entries);
        }
    }

    @Nested
    @DisplayName("Test 6 — frozen @Enhancement patch (vauban-enhancements.properties)")
    class EnhancementPatch {

        private static final String PATCH_PATH = "META-INF/vauban-enhancements.properties";

        @Test
        @DisplayName("writes '<target>=<added annotation FQN>' so the runtime applies it without the BCE")
        void shouldWriteEnhancementPatch() throws IOException {
            var result = compileWithBce(
                    List.of(WritingScopeBce.class),
                    """
                    import io.vidocq.vauban.processor.apt.testfixtures.PathLike;

                    @PathLike("/patch")
                    public class PatchResource {
                        public String hello() { return "hello"; }
                    }
                    """
            );

            assertTrue(result.success(),
                    "Compilation should succeed. Messages: " + result.messages());
            assertTrue(result.hasFile(PATCH_PATH),
                    "Expected " + PATCH_PATH + " to be generated when a BCE adds an annotation");

            var raw = Files.readString(result.outputDir().resolve(PATCH_PATH), StandardCharsets.UTF_8);
            assertTrue(raw.contains("PatchResource=jakarta.enterprise.context.RequestScoped"),
                    "Patch should map the target to the added annotation FQN. Actual:\n" + raw);
        }

        @Test
        @DisplayName("read-only BCE contributes no patch entry")
        void readOnlyBceProducesNoPatchEntry() throws IOException {
            var result = compileWithBce(
                    List.of(ReadOnlyBce.class),
                    """
                    import io.vidocq.vauban.processor.apt.testfixtures.PathLike;

                    @PathLike("/ro")
                    public class RoOnlyResource {
                        public String hello() { return "hello"; }
                    }
                    """
            );

            assertTrue(result.success(), "Compilation should succeed");
            if (result.hasFile(PATCH_PATH)) {
                var raw = Files.readString(result.outputDir().resolve(PATCH_PATH), StandardCharsets.UTF_8);
                assertFalse(raw.contains("RoOnlyResource="),
                        "Read-only BCE must not contribute a patch entry. Actual:\n" + raw);
            }
        }
    }

    // ======================================================================
    // Helpers (reused from the BceCompileTimeTest pattern)
    // ======================================================================

    private CompilationResult compileWithBce(List<Class<?>> bceClasses, String... sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);

        var sourceFiles = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            var className = extractClassName(source);
            var packageName = extractPackageName(source);
            var dir = sourceDir;
            if (packageName != null) {
                dir = sourceDir.resolve(packageName.replace('.', '/'));
                Files.createDirectories(dir);
            }
            var file = dir.resolve(className + ".java");
            Files.writeString(file, source);
            sourceFiles.add(new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                    return Files.readString(file);
                }
            });
        }

        var outputDir = tempDir.resolve("classes");
        Files.createDirectories(outputDir);

        var classpath = resolveCompilationClasspath();

        var options = List.of(
                "-d", outputDir.toString(),
                "--release", "25",
                "-classpath", classpath,
                "-proc:full"
        );

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var processor = new VaubanProcessor();
            processor.overrideBceClasses = bceClasses;

            var task = compiler.getTask(null, fileManager, diagnostics, options, null, sourceFiles);
            task.setProcessors(List.of(processor));

            var success = task.call();

            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }

            return new CompilationResult(success, outputDir, messages);
        }
    }

    record CompilationResult(boolean success, Path outputDir, List<String> messages) {
        boolean hasFile(String relativePath) {
            return Files.exists(outputDir.resolve(relativePath));
        }

        List<String> readRuntimeList() throws IOException {
            var path = outputDir.resolve(RUNTIME_LIST_PATH);
            if (!Files.exists(path)) return List.of();
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }

    private String extractClassName(String source) {
        var pattern = java.util.regex.Pattern.compile("(?:class|interface|enum|record)\\s+(\\w+)");
        var matcher = pattern.matcher(source);
        if (matcher.find()) return matcher.group(1);
        throw new IllegalArgumentException("Cannot extract class name from source");
    }

    private String extractPackageName(String source) {
        var pattern = java.util.regex.Pattern.compile("package\\s+([\\w.]+)\\s*;");
        var matcher = pattern.matcher(source);
        if (matcher.find()) return matcher.group(1);
        return null;
    }

    private static String resolveCompilationClasspath() {
        var markerClasses = List.of(
                ApplicationScoped.class,
                jakarta.inject.Inject.class,
                jakarta.interceptor.Interceptor.class,
                BceRuntimeListCompileTimeTest.class, // to pull in target/test-classes
                PathLike.class                       // ensure testfixtures is on the classpath
        );

        var paths = new LinkedHashSet<String>();

        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            for (var entry : cp.split(File.pathSeparator)) {
                paths.add(entry);
            }
        }

        for (var clazz : markerClasses) {
            try {
                var location = clazz.getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    paths.add(Path.of(location.toURI()).toString());
                }
            } catch (Exception ignored) {}
        }

        ModuleLayer.boot().configuration().modules().forEach(resolvedModule ->
                resolvedModule.reference().location().ifPresent(uri -> {
                    try {
                        if ("file".equals(uri.getScheme())) {
                            paths.add(Path.of(uri).toString());
                        }
                    } catch (Exception ignored) {}
                }));

        return String.join(File.pathSeparator, paths);
    }
}
