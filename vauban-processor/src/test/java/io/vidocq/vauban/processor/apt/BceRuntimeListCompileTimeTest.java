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
 * Tests ROUGES pour le bug #7 — partie APT (compile-time).
 *
 * <h2>Contrat teste</h2>
 * Quand une BCE applique des modifications <b>observables au runtime</b>
 * (annotations ajoutees, qualifiers) a une classe, le {@link VaubanProcessor}
 * doit ecrire une entree dans {@code META-INF/vauban-bce-runtime.list} :
 *
 * <pre>
 * # Vauban BCE runtime replay list
 * &lt;BCE-FQN&gt;;&lt;target-class-FQN&gt;
 * </pre>
 *
 * Les BCEs en <i>lecture seule</i> (qui n'ajoutent rien) ne produisent pas
 * de ligne dans ce fichier — pas d'overhead runtime.
 *
 * <h2>Pourquoi ces tests sont necessaires</h2>
 * Le fichier {@code vauban-bce-runtime.list} est le pivot entre l'APT et le
 * runtime : sans lui, le {@code VaubanContainerBuilder} ne sait pas quelles
 * BCEs rejouer sur quelles classes, et les annotations synthetiques
 * (ex: {@code @RequestScoped} ajoute par {@code CassiniScopeBCE} sur une
 * classe {@code @Path}) ne sont jamais visibles au runtime.
 *
 * @see io.vidocq.vauban.processor.apt.BceCompileTimeTest pour le pattern
 *      de compilation in-process.
 */
@DisplayName("BCE runtime list - ecriture compile-time par VaubanProcessor")
class BceRuntimeListCompileTimeTest {

    /**
     * Chemin attendu du nouveau fichier produit par l'APT.
     * L'implementation DOIT exposer ce chemin via une constante publique
     * (ex: {@code SyntheticMetadataSerializer.BCE_RUNTIME_LIST_PATH})
     * mais tant que le code de production n'est pas ecrit, le test
     * utilise directement le litteral pour etre rouge a la compilation
     * fonctionnelle et non a la compilation Java.
     */
    private static final String RUNTIME_LIST_PATH = "META-INF/vauban-bce-runtime.list";

    @TempDir
    Path tempDir;

    // ---------- Annotation trigger ----------
    // Voir {@link io.vidocq.vauban.processor.apt.testfixtures.PathLike}.
    // Extraite en top-level pour etre referencable depuis les sources
    // compilees in-memory (le compilateur in-process refuse les nested
    // annotations declarees dans la classe de test elle-meme).

    // ---------- BCEs de test ----------

    /**
     * BCE qui ajoute {@code @RequestScoped} a toute classe portant
     * l'annotation trigger {@link PathLike}. Simule {@code CassiniScopeBCE}.
     * <b>Modifie</b> la classe → doit produire une ligne dans
     * {@code vauban-bce-runtime.list}.
     */
    public static class WritingScopeBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = PathLike.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(RequestScoped.class);
        }
    }

    /**
     * BCE qui ne <b>modifie rien</b> : elle lit juste {@code clazz.info()}.
     * Doit ne rien ecrire dans {@code vauban-bce-runtime.list}.
     */
    public static class ReadOnlyBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = PathLike.class)
        public void inspect(ClassConfig clazz) {
            // lecture seule : on ne touche PAS a ClassConfig
            clazz.info();
        }
    }

    // ======================================================================
    // Tests
    // ======================================================================

    @Nested
    @DisplayName("Test 1 — BCE qui modifie la classe → ecriture dans la liste")
    class WritingBce {

        @Test
        @DisplayName("produit META-INF/vauban-bce-runtime.list avec '<BCE>;<target>'")
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

            // Le fichier DOIT exister apres passage du processor
            assertTrue(result.hasFile(RUNTIME_LIST_PATH),
                    "Expected " + RUNTIME_LIST_PATH
                            + " to be generated by VaubanProcessor when BCE modifies a class");

            var entries = result.readRuntimeList();
            var expected = WritingScopeBce.class.getName() + ";TestResource";
            assertTrue(entries.contains(expected),
                    "Expected runtime list to contain \"" + expected + "\". Actual: " + entries);
        }

        @Test
        @DisplayName("ignore les lignes commentaires (#)")
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

            // Le fichier DOIT contenir au moins un header commentaire
            var raw = Files.readString(result.outputDir().resolve(RUNTIME_LIST_PATH),
                    StandardCharsets.UTF_8);
            assertTrue(raw.lines().anyMatch(l -> l.trim().startsWith("#")),
                    "Runtime list should contain a comment header (generated by Vauban)");

            // readRuntimeList filtre deja les # → ne doit contenir QUE la ligne utile
            var entries = result.readRuntimeList();
            for (var e : entries) {
                assertFalse(e.startsWith("#"),
                        "readRuntimeList should filter out comment lines, but got: " + e);
            }
        }
    }

    @Nested
    @DisplayName("Test 5 — BCE en lecture seule n'ecrit rien")
    class ReadOnly {

        @Test
        @DisplayName("BCE read-only ne produit aucune ligne active dans vauban-bce-runtime.list")
        void shouldNotWriteAnyRuntimeEntryForReadOnlyBce() throws IOException {
            // On compile AVEC UNE BCE MODIFIANTE en plus pour garantir que le
            // fichier existe, puis on verifie que la BCE read-only n'y apparait pas.
            // C'est plus fort qu'un "if exists" qui laisserait passer une
            // implementation naive qui ne genere jamais le fichier.
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

            // La BCE modifiante doit avoir declenche la creation du fichier
            assertTrue(result.hasFile(RUNTIME_LIST_PATH),
                    "Runtime list should exist: WritingScopeBce modifies classes and must register them");

            var entries = result.readRuntimeList();

            // WritingScopeBce doit ecrire des lignes (pour WritingTarget ET ReadOnlyResource,
            // toutes deux ciblees par withAnnotations=PathLike)
            var writingFqn = WritingScopeBce.class.getName();
            assertTrue(entries.stream().anyMatch(e -> e.startsWith(writingFqn + ";")),
                    "Writing BCE should produce at least one entry. Got: " + entries);

            // ReadOnlyBce ne doit avoir AUCUNE ligne, meme pour la classe qu'elle a vue
            var readOnlyFqn = ReadOnlyBce.class.getName();
            assertTrue(entries.stream().noneMatch(e -> e.startsWith(readOnlyFqn + ";")),
                    "Read-only BCE " + readOnlyFqn
                            + " should NOT appear in runtime list. Found entries: " + entries);
        }
    }

    // ======================================================================
    // Helpers (repris du pattern BceCompileTimeTest)
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
                BceRuntimeListCompileTimeTest.class, // pour tirer target/test-classes
                PathLike.class                       // garantir testfixtures sur classpath
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
