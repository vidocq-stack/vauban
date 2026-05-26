package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanProcessor - APT processor")
class VaubanProcessorTest {

    @TempDir
    Path tempDir;

    private boolean compileWithProcessor(String... sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        // Write source files to temp dir
        var sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);

        var sourceFiles = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            // Extract class name from source (find "class X" or "interface X")
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

        // Build classpath from module locations (JPMS puts jars on module path, not classpath)
        var classpath = resolveCompilationClasspath();

        var fullOptions = new ArrayList<String>();
        fullOptions.add("-d");
        fullOptions.add(outputDir.toString());
        fullOptions.add("--release");
        fullOptions.add("25");
        fullOptions.add("-classpath");
        fullOptions.add(classpath);
        // Disable annotation processing of the processor itself
        fullOptions.add("-proc:full");

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, fullOptions, null, sourceFiles);
            task.setProcessors(List.of(new VaubanProcessor()));

            var success = task.call();

            // Print diagnostics for debugging
            for (var d : diagnostics.getDiagnostics()) {
                System.out.println(d.getKind() + ": " + d.getMessage(null));
            }

            return success;
        }
    }

    private String extractClassName(String source) {
        // Simple extraction: find "class X" or "interface X"
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

    /**
     * Build a compilation classpath by resolving jar/directory locations from loaded classes.
     * In a JPMS environment, System.getProperty("java.class.path") may be empty,
     * so we resolve locations from key classes that must be available during compilation.
     */
    private static String resolveCompilationClasspath() {
        // Classes whose locations we need on the compilation classpath
        var markerClasses = List.of(
            ApplicationScoped.class,                                      // CDI API
            jakarta.inject.Inject.class,                                  // Jakarta Inject API
            jakarta.interceptor.Interceptor.class                         // Jakarta Interceptors API
        );

        var paths = new LinkedHashSet<String>();

        // Add java.class.path entries (may contain surefire booter etc.)
        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            for (var entry : cp.split(File.pathSeparator)) {
                paths.add(entry);
            }
        }

        // Resolve locations from marker classes via their ProtectionDomain
        for (var clazz : markerClasses) {
            try {
                var location = clazz.getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    paths.add(Path.of(location.toURI()).toString());
                }
            } catch (Exception ignored) {
                // Some classes may not have a code source
            }
        }

        // Also resolve from the boot module layer (covers all module path entries)
        ModuleLayer.boot().configuration().modules().forEach(resolvedModule -> {
            resolvedModule.reference().location().ifPresent(uri -> {
                try {
                    if ("file".equals(uri.getScheme())) {
                        paths.add(Path.of(uri).toString());
                    } else if ("jrt".equals(uri.getScheme())) {
                        // Skip JRT (runtime modules) - they're already available
                    } else {
                        paths.add(Path.of(uri).toString());
                    }
                } catch (Exception ignored) {
                }
            });
        });

        return String.join(File.pathSeparator, paths);
    }

    @Test
    @DisplayName("compiles an @ApplicationScoped bean and generates a factory")
    void shouldGenerateFactoryForApplicationScopedBean() throws IOException {
        var success = compileWithProcessor("""
            import jakarta.enterprise.context.ApplicationScoped;

            @ApplicationScoped
            public class MyService {
                public String hello() { return "hello"; }
            }
            """);

        assertTrue(success, "Compilation should succeed");

        // Check that the factory class file was generated
        var factoryFile = tempDir.resolve("classes/MyService_Factory.class");
        assertTrue(Files.exists(factoryFile), "Factory class should be generated");
    }

    @Test
    @DisplayName("compiles an @ApplicationScoped bean and generates a proxy")
    void shouldGenerateProxyForNormalScopedBean() throws IOException {
        var success = compileWithProcessor("""
            import jakarta.enterprise.context.ApplicationScoped;

            @ApplicationScoped
            public class MyService {
                public String hello() { return "hello"; }
            }
            """);

        assertTrue(success, "Compilation should succeed");

        var proxyFile = tempDir.resolve("classes/MyService_ClientProxy.class");
        assertTrue(Files.exists(proxyFile), "Client proxy should be generated for @ApplicationScoped bean");
    }

    @Test
    @DisplayName("does NOT generate a proxy for a @Dependent bean")
    void shouldNotGenerateProxyForDependentBean() throws IOException {
        var success = compileWithProcessor("""
            import jakarta.enterprise.context.Dependent;

            @Dependent
            public class MyHelper {
                public int compute() { return 42; }
            }
            """);

        assertTrue(success, "Compilation should succeed");

        var factoryFile = tempDir.resolve("classes/MyHelper_Factory.class");
        assertTrue(Files.exists(factoryFile), "Factory should be generated");

        var proxyFile = tempDir.resolve("classes/MyHelper_ClientProxy.class");
        assertFalse(Files.exists(proxyFile), "No proxy for @Dependent bean");
    }
}
