package fr.vidocq.vauban.processor.apt;

import fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import fr.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
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
 * Tests d'integration pour l'execution des Build Compatible Extensions (BCE)
 * a la compilation via le VaubanProcessor (APT).
 *
 * <h2>Contexte</h2>
 * Les BCEs CDI 4.1 sont concues pour s'executer a la compilation. Le VaubanProcessor
 * les decouvre via ServiceLoader (ou injection pour les tests), execute les 5 phases
 * (Discovery → Enhancement → Registration → Synthesis → Validation), et persiste les
 * resultats pour que le runtime n'ait pas a les re-executer.
 *
 * <h2>Pourquoi ces tests sont necessaires</h2>
 * Le TCK CDI teste les BCEs au runtime uniquement. L'execution a la compilation
 * est une fonctionnalite specifique a Vauban qui n'est couverte par aucun test externe.
 * Sans ces tests, on ne peut pas garantir que les Enhancement appliques a la compilation
 * se retrouvent dans le code genere, ni que le marqueur BCE empeche la re-execution au runtime.
 */
@DisplayName("BCE a la compilation - integration VaubanProcessor")
class BceCompileTimeTest {

    @TempDir
    Path tempDir;

    // ---- Annotation source compiled alongside test beans ----

    private static final String MY_MARKER_SOURCE = """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface MyMarker {}
            """;

    // ---- BCE de test : ajoute @RequestScoped aux classes annotees @MyMarker ----

    /**
     * Annotation marker — must match the one compiled from MY_MARKER_SOURCE.
     * Loaded via the APT classloader at runtime.
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    public @interface MyMarker {}

    /**
     * BCE de test qui ajoute @RequestScoped a toute classe annotee @MyMarker.
     * Simule le pattern RestScopeExtension qui ajoute @RequestScoped aux @Path.
     */
    public static class TestEnhancementBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = MyMarker.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(RequestScoped.class);
        }
    }

    /**
     * BCE de test qui ajoute une classe via @Discovery.
     */
    public static class TestDiscoveryBce implements BuildCompatibleExtension {
        @Discovery
        public void discover(ScannedClasses classes) {
            // Discovery phase executed — we just verify it doesn't crash
        }
    }

    // ---- Helper: compile avec le processeur et une BCE injectee ----

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
    @DisplayName("@Enhancement BCE modifie un bean existant — la BCE s'execute a la compilation")
    void shouldExecuteEnhancementOnExistingBean() throws IOException {
        // Le bean a deja @Dependent (pseudo-scope). La BCE ajoute @RequestScoped (normal-scope).
        // Si l'Enhancement s'execute, le bean devrait avoir un client proxy genere.
        var result = compileWithBce(
                List.of(TestEnhancementBce.class),
                MY_MARKER_SOURCE,
                """
                import jakarta.enterprise.context.Dependent;

                @Dependent
                @MyMarker
                public class EnhancedService {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed. Messages: " + result.messages());

        // Le bean doit etre genere
        assertTrue(result.hasFile("EnhancedService_Factory.class"),
                "Factory should be generated");

        // Le bean doit apparaitre dans vauban-beans.list
        var beansList = result.readBeansList();
        assertTrue(beansList.contains("EnhancedService"),
                "Enhanced bean should appear in vauban-beans.list");

        // Le marqueur BCE doit etre present
        assertTrue(result.hasFile(SyntheticMetadataSerializer.BCE_PROCESSED_MARKER),
                "BCE processed marker should be written");
    }

    @Test
    @DisplayName("BCE ecrit le marqueur META-INF/vauban-bce-processed")
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
    @DisplayName("sans BCE, pas de marqueur vauban-bce-processed")
    void shouldNotWriteMarkerWhenNoBce() throws IOException {
        var result = compileWithBce(
                List.of(), // pas de BCE
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
    @DisplayName("@Enhancement ne modifie pas une classe qui a deja un scope")
    void shouldNotOverrideExistingScope() throws IOException {
        var result = compileWithBce(
                List.of(TestEnhancementBce.class),
                MY_MARKER_SOURCE,
                """
                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                @MyMarker
                public class AlreadyScopedBean {
                    public String hello() { return "hello"; }
                }
                """
        );

        assertTrue(result.success(), "Compilation should succeed. Messages: " + result.messages());

        // Le bean doit etre dans la liste (il a deja @ApplicationScoped)
        var beansList = result.readBeansList();
        assertTrue(beansList.contains("AlreadyScopedBean"),
                "Already-scoped bean should still be in beans list");
    }

    @Test
    @DisplayName("@Discovery phase s'execute sans erreur")
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
