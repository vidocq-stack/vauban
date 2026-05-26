package io.vidocq.vauban.maven.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModuleAnalyzer - JPMS compatibility analysis")
class ModuleAnalyzerTest {

    // Find a JAR on the test classpath
    private Path findJarOf(Class<?> clazz) throws URISyntaxException {
        var url = clazz.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(url.toURI());
    }

    @Nested
    @DisplayName("JAR analysis")
    class JarAnalysis {

        @Test
        @DisplayName("detects an explicit module (JUnit API)")
        void shouldDetectExplicitModule() throws Exception {
            var junitApiPath = findJarOf(org.junit.jupiter.api.Test.class);
            // JUnit might be a dir in test classpath, skip if so
            if (!junitApiPath.toString().endsWith(".jar")) return;

            var result = ModuleAnalyzer.analyze(junitApiPath);
            assertEquals(ModuleType.EXPLICIT, result.type());
            assertNotNull(result.moduleName());
            assertFalse(result.packages().isEmpty());
        }

        @Test
        @DisplayName("analyzes a JAR and returns packages")
        void shouldReturnPackages() throws Exception {
            var path = findJarOf(org.junit.jupiter.api.Test.class);
            if (!path.toString().endsWith(".jar")) return;

            var result = ModuleAnalyzer.analyze(path);
            assertFalse(result.packages().isEmpty());
        }
    }

    @Nested
    @DisplayName("automatic module name derivation")
    class AutomaticModuleName {

        @Test
        @DisplayName("derives the name from the filename")
        void shouldDeriveFromFilename() {
            assertEquals("commons.lang3", ModuleAnalyzer.deriveAutomaticModuleName("commons-lang3-3.14.0.jar"));
            assertEquals("guava", ModuleAnalyzer.deriveAutomaticModuleName("guava-33.0.0.jar"));
            assertEquals("my.library", ModuleAnalyzer.deriveAutomaticModuleName("my-library-1.0-SNAPSHOT.jar"));
        }

        @Test
        @DisplayName("handles edge cases")
        void shouldHandleEdgeCases() {
            assertEquals("simple", ModuleAnalyzer.deriveAutomaticModuleName("simple.jar"));
            assertEquals("my.lib", ModuleAnalyzer.deriveAutomaticModuleName("my_lib.jar"));
        }
    }

    @Nested
    @DisplayName("split package detection")
    class SplitPackageDetection {

        @Test
        @DisplayName("detects packages present in multiple JARs")
        void shouldDetectSplitPackages() {
            var r1 = new ModuleAnalysisResult(
                Path.of("a.jar"), "a.jar", ModuleType.AUTOMATIC, "a",
                java.util.Set.of("com.example.shared", "com.example.a"), List.of());
            var r2 = new ModuleAnalysisResult(
                Path.of("b.jar"), "b.jar", ModuleType.AUTOMATIC, "b",
                java.util.Set.of("com.example.shared", "com.example.b"), List.of());

            var splits = ModuleAnalyzer.detectSplitPackages(List.of(r1, r2));
            assertEquals(1, splits.size());
            assertEquals("com.example.shared", splits.getFirst().packageName());
            assertEquals(List.of("a.jar", "b.jar"), splits.getFirst().jarFiles());
        }

        @Test
        @DisplayName("no split packages when packages are distinct")
        void shouldNotDetectWhenDistinct() {
            var r1 = new ModuleAnalysisResult(
                Path.of("a.jar"), "a.jar", ModuleType.EXPLICIT, "a",
                java.util.Set.of("com.example.a"), List.of());
            var r2 = new ModuleAnalysisResult(
                Path.of("b.jar"), "b.jar", ModuleType.EXPLICIT, "b",
                java.util.Set.of("com.example.b"), List.of());

            assertTrue(ModuleAnalyzer.detectSplitPackages(List.of(r1, r2)).isEmpty());
        }
    }

    @Nested
    @DisplayName("report")
    class Report {

        @Test
        @DisplayName("generates a readable report")
        void shouldGenerateReadableReport() {
            var r1 = new ModuleAnalysisResult(
                Path.of("cdi-api.jar"), "cdi-api.jar", ModuleType.EXPLICIT, "jakarta.cdi",
                java.util.Set.of("jakarta.enterprise.context"), List.of());
            var r2 = new ModuleAnalysisResult(
                Path.of("old-lib.jar"), "old-lib.jar", ModuleType.UNNAMED, "old.lib",
                java.util.Set.of("com.old"), List.of("No module-info.class and no Automatic-Module-Name"));

            var report = ModuleReport.generate(List.of(r1, r2), List.of());
            assertTrue(report.contains("[OK]"));
            assertTrue(report.contains("[ERROR]"));
            assertTrue(report.contains("jakarta.cdi"));
            assertTrue(report.contains("2 JARs analyzed"));
        }
    }
}
