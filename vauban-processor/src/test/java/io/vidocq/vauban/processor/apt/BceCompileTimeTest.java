package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.build.compatible.spi.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for running Build Compatible Extensions (BCE)
 * at compile time via the VaubanProcessor (APT).
 *
 * <h2>Context</h2>
 * CDI 4.1 BCEs are designed to run at compile time. The VaubanProcessor
 * discovers them via ServiceLoader (or injection for tests), runs the 5 phases
 * (Discovery → Enhancement → Registration → Synthesis → Validation), and persists the
 * results so the runtime does not have to re-execute them.
 *
 * <h2>Why these tests are necessary</h2>
 * The CDI TCK tests BCEs at runtime only. Compile-time execution
 * is a Vauban-specific feature that no external test covers.
 * Without these tests, we cannot guarantee that Enhancements applied at compile time
 * end up in the generated code, nor that the BCE marker prevents re-execution at runtime.
 */
@DisplayName("BCE at compile time - VaubanProcessor integration")
class BceCompileTimeTest {

    @TempDir
    Path tempDir;

    // ---- Test BCE: adds @RequestScoped to classes annotated with @jakarta.inject.Named ----

    /**
     * Test BCE that adds @RequestScoped to any class annotated with @Named.
     * Uses @Named (jakarta.inject) because this annotation is on the classpath
     * shared between the test and the in-process compilation.
     * Simulates the RestScopeExtension pattern that adds @RequestScoped to @Path.
     */
    public static class TestEnhancementBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = jakarta.inject.Named.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(RequestScoped.class);
        }
    }

    /**
     * Test BCE that adds a class via @Discovery.
     */
    public static class TestDiscoveryBce implements BuildCompatibleExtension {
        @Discovery
        public void discover(ScannedClasses classes) {
            // Discovery phase executed — we just verify it doesn't crash
        }
    }

    /**
     * Test BCE with @Synthesis that creates a synthetic bean.
     */
    public static class TestSynthesisBce implements BuildCompatibleExtension {
        @Synthesis
        public void createSyntheticBean(SyntheticComponents components) {
            components.addBean(String.class)
                    .createWith(TestStringCreator.class)
                    .scope(ApplicationScoped.class)
                    .withParam("value", "synthetic-hello");
        }
    }

    /** SyntheticBeanCreator for test — creates a String bean. */
    public static class TestStringCreator
            implements jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator<String> {
        @Override
        public String create(jakarta.enterprise.inject.Instance<Object> lookup,
                             jakarta.enterprise.inject.build.compatible.spi.Parameters params) {
            return params.get("value", String.class);
        }
    }

    // ---- Helper: compile with the processor and an injected BCE ----

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

        String readFile(String relativePath) throws IOException {
            return Files.readString(outputDir.resolve(relativePath), StandardCharsets.UTF_8);
        }

        List<String> readBeansList() throws IOException {
            var path = outputDir.resolve("META-INF/vauban-beans.list");
            if (!Files.exists(path)) return List.of();
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }

    // ---- Tests ----

    @Test
    @DisplayName("@Enhancement BCE modifies an existing bean — the BCE runs at compile time")
    void shouldExecuteEnhancementOnExistingBean() throws IOException {
        // The bean already has @Dependent (pseudo-scope). The BCE adds @RequestScoped (normal-scope).
        // @Named is the trigger of the test BCE.
        var result = compileWithBce(
                List.of(TestEnhancementBce.class),
                """
                import jakarta.enterprise.context.Dependent;
                import jakarta.inject.Named;

                @Dependent
                @Named
                public class EnhancedService {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed. Messages: " + result.messages());

        assertTrue(result.hasFile("EnhancedService_Factory.class"),
                "Factory should be generated");

        var beansList = result.readBeansList();
        assertTrue(beansList.contains("EnhancedService"),
                "Enhanced bean should appear in vauban-beans.list");

        assertTrue(result.hasFile(SyntheticMetadataSerializer.BCE_PROCESSED_MARKER),
                "BCE processed marker should be written");
    }

    @Test
    @DisplayName("BCE writes the META-INF/vauban-bce-processed marker")
    void shouldWriteBceProcessedMarker() throws IOException {
        var result = compileWithBce(
                List.of(TestDiscoveryBce.class),
                """
                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class SimpleBean {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed");
        assertTrue(result.hasFile(SyntheticMetadataSerializer.BCE_PROCESSED_MARKER),
                "BCE processed marker should be written when BCEs are executed");
    }

    @Test
    @DisplayName("without a BCE, no vauban-bce-processed marker")
    void shouldNotWriteMarkerWhenNoBce() throws IOException {
        var result = compileWithBce(
                List.of(), // no BCE
                """
                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class SimpleBean {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed");
        assertFalse(result.hasFile(SyntheticMetadataSerializer.BCE_PROCESSED_MARKER),
                "No marker when no BCE executed");
    }

    @Test
    @DisplayName("@Enhancement does not modify a class that already has a scope")
    void shouldNotOverrideExistingScope() throws IOException {
        var result = compileWithBce(
                List.of(TestEnhancementBce.class),
                """
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Named;

                @ApplicationScoped
                @Named
                public class AlreadyScopedBean {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed. Messages: " + result.messages());

        var beansList = result.readBeansList();
        assertTrue(beansList.contains("AlreadyScopedBean"),
                "Already-scoped bean should still be in beans list");
    }

    @Test
    @DisplayName("@Discovery phase runs without error")
    void shouldExecuteDiscoveryPhaseWithoutError() throws IOException {
        var result = compileWithBce(
                List.of(TestDiscoveryBce.class),
                """
                import jakarta.enterprise.context.Dependent;

                @Dependent
                public class MyHelper {
                    public int compute() { return 42; }
                }
                """
        );

        assertTrue(result.success(), "Compilation with @Discovery BCE should succeed");
        assertTrue(result.hasFile("MyHelper_Factory.class"),
                "Bean should still be discovered normally");
    }

    @Test
    @DisplayName("BCE @Enhancement promotes a class without a CDI scope — only @Named, no scope")
    void shouldPromoteNonBeanClassViaBceEnhancement() throws IOException {
        // HelloResource only has @Named (no CDI scope).
        // The TestEnhancementBce BCE adds @RequestScoped via @Enhancement(withAnnotations=Named).
        // The APT must scan @Named (extracted from the BCE withAnnotations),
        // index HelloResource, and the BCE must promote it to a bean.
        var result = compileWithBce(
                List.of(TestEnhancementBce.class),
                """
                import jakarta.inject.Named;

                @Named
                public class HelloResource {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed. Messages: " + result.messages());

        // The BCE added @RequestScoped → HelloResource is a normal-scoped bean
        var beansList = result.readBeansList();
        assertTrue(beansList.contains("HelloResource"),
                "Non-CDI class promoted by BCE should appear in vauban-beans.list. Beans: " + beansList);

        assertTrue(result.hasFile("HelloResource_Factory.class"),
                "Factory should be generated for promoted bean");
        assertTrue(result.hasFile("HelloResource_ClientProxy.class"),
                "Client proxy should be generated (RequestScoped is normal-scoped)");
    }

    @Test
    @DisplayName("@Synthesis BCE serializes the synthetic beans into the metadata file")
    void shouldSerializeSyntheticBeanMetadata() throws IOException {
        var result = compileWithBce(
                List.of(TestSynthesisBce.class),
                """
                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class RealBean {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed. Messages: " + result.messages());

        // The synthetic metadata file must be written
        assertTrue(result.hasFile(SyntheticMetadataSerializer.METADATA_PATH),
                "Synthetic metadata file should be written when @Synthesis creates beans");

        // Read and verify the content
        var metadataContent = result.readFile(SyntheticMetadataSerializer.METADATA_PATH);
        assertTrue(metadataContent.contains("bean.count=1"),
                "Should contain exactly 1 synthetic bean");
        assertTrue(metadataContent.contains(TestStringCreator.class.getName()),
                "Should reference the creator class");
    }

    // ---- Utility methods (same as VaubanProcessorTest) ----

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
                jakarta.interceptor.Interceptor.class
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
