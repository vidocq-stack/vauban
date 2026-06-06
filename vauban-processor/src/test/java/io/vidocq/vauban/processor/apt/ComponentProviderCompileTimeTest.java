package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.core.VaubanComponentProvider;
import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the APT-generated {@code _VaubanComponents} provider: a packaged,
 * no-arg managed bean must yield a provider that instantiates it in-module, the generated
 * source must compile, and a class-path service file must be registered.
 */
@DisplayName("Component provider - APT generation")
class ComponentProviderCompileTimeTest {

    private static final String SERVICE_PATH =
            "META-INF/services/io.vidocq.vauban.core.VaubanComponentProvider";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("generates a compiling _VaubanComponents + service file for a packaged no-arg bean")
    void generatesProviderForPackagedNoArgBean() throws Exception {
        var result = compile("""
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class HelloResource {
                    public String hi() { return "hi"; }
                }
                """);

        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        var genSource = result.genDir().resolve("app/_VaubanComponents.java");
        assertTrue(Files.exists(genSource), "expected generated provider source at " + genSource);
        var src = Files.readString(genSource);
        assertTrue(src.contains("package app;"), src);
        assertTrue(src.contains("case \"app.HelloResource\" -> new app.HelloResource();"), src);

        assertTrue(Files.exists(result.outputDir().resolve("app/_VaubanComponents.class")),
                "the generated provider must compile to a .class");

        var svc = result.outputDir().resolve(SERVICE_PATH);
        assertTrue(Files.exists(svc), "expected class-path service registration at " + svc);
        assertEquals("app._VaubanComponents", Files.readString(svc).strip());
    }

    // ---- minimal in-process compilation harness (with -s for generated sources) ----

    private CompilationResult compile(String source) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var sourceDir = Files.createDirectories(tempDir.resolve("src"));
        var dir = Files.createDirectories(sourceDir.resolve("app"));
        var file = dir.resolve("HelloResource.java");
        Files.writeString(file, source);
        var sourceFile = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return Files.readString(file);
            }
        };

        var outputDir = Files.createDirectories(tempDir.resolve("classes"));
        var genDir = Files.createDirectories(tempDir.resolve("gen"));

        var options = List.of(
                "-d", outputDir.toString(),
                "-s", genDir.toString(),
                "--release", "25",
                "-classpath", resolveCompilationClasspath(),
                "-proc:full");

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null, List.of(sourceFile));
            task.setProcessors(List.of(new VaubanProcessor()));
            var success = task.call();

            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }
            return new CompilationResult(success, outputDir, genDir, messages);
        }
    }

    record CompilationResult(boolean success, Path outputDir, Path genDir, List<String> messages) {}

    private static String resolveCompilationClasspath() {
        var paths = new LinkedHashSet<String>();
        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            paths.addAll(List.of(cp.split(File.pathSeparator)));
        }
        for (var clazz : List.of(ApplicationScoped.class, VaubanComponentProvider.class,
                jakarta.inject.Inject.class, VaubanProcessor.class)) {
            try {
                var loc = clazz.getProtectionDomain().getCodeSource().getLocation();
                if (loc != null) paths.add(Path.of(loc.toURI()).toString());
            } catch (Exception ignored) {
                // best-effort classpath assembly
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(rm ->
                rm.reference().location().ifPresent(uri -> {
                    if ("file".equals(uri.getScheme())) {
                        try {
                            paths.add(Path.of(uri).toString());
                        } catch (Exception ignored) {
                            // skip non-file module locations
                        }
                    }
                }));
        return String.join(File.pathSeparator, paths);
    }
}
