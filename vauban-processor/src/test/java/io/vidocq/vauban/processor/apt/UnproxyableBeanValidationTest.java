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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI 4.1 "Unproxyable bean types" must fail the build, at the compiler, with an
 * actionable message — and the {@code ProxyLink} marker constructor must lift the
 * restriction and be the constructor the generated source proxy chains to.
 *
 * <h2>Context (Vidocq/vauban#24)</h2>
 * The deployment validator's proxyability checks used {@code Class.forName} over classes
 * that do not exist yet at annotation-processing time, and silently skipped on
 * {@code ClassNotFoundException}: a normal-scoped bean whose only constructor is a
 * parameterized {@code @Inject} one compiled fine and blew up at proxy creation with an
 * NPE. These tests pin the compile-time behaviour on both sides of the fix.
 */
@DisplayName("Unproxyable normal-scoped beans — compile-time diagnostic and ProxyLink escape hatch")
class UnproxyableBeanValidationTest {

    @TempDir
    Path tempDir;

    private static final String CONFIG = """
            package io.repro.app;

            import jakarta.enterprise.context.ApplicationScoped;

            @ApplicationScoped
            public class Config {
                public String issuer() { return "https://issuer"; }
            }
            """;

    private static final String CONSUMER = """
            package io.repro.app;

            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Inject;

            @ApplicationScoped
            public class Consumer {
                @Inject
                Oidc oidc;

                public String hello() { return String.valueOf(oidc); }
            }
            """;

    @Test
    @DisplayName("a normal-scoped bean with only an @Inject constructor compiles with a warning — the plugin weaves the marker")
    void injectOnlyConstructorBeanWarnsAndDefersToWeaving() throws IOException {
        var result = compile(CONFIG, CONSUMER, """
                package io.repro.app;

                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class Oidc {
                    private final String issuer;

                    @Inject
                    public Oidc(Config config) {
                        this.issuer = config.issuer();
                    }

                    public String issuer() { return issuer; }
                }
                """);

        // The vauban-maven-plugin weaves the synthetic (ProxyLink) constructor at
        // process-classes (phase 2 of vauban#24), so this is not fatal at compile time —
        // but it must be VISIBLE: a deployment without the plugin fails at container start.
        assertTrue(result.success(),
                "a weavable bean must not fail the build: " + result.messages());
        assertTrue(result.messages().stream()
                        .anyMatch(m -> m.startsWith("WARNING") && m.contains("[Vauban]")
                                && m.contains("ProxyLink") && m.contains("process-classes")),
                "the deferral to weaving must be visible as a warning: " + result.messages());
    }

    @Test
    @DisplayName("the ProxyLink marker constructor lifts the restriction and the proxy targets it")
    void markerConstructorCompilesAndIsTargetedByTheProxy() throws IOException {
        var result = compile(CONFIG, CONSUMER, """
                package io.repro.app;

                import io.vidocq.vauban.api.ProxyLink;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class Oidc {
                    private final String issuer;

                    @Inject
                    public Oidc(Config config) {
                        this.issuer = config.issuer();
                    }

                    protected Oidc(ProxyLink link) {
                        this.issuer = null;
                    }

                    public String issuer() { return issuer; }
                }
                """);

        assertTrue(result.success(),
                "the marker constructor must make the bean proxyable: " + result.messages());
        String proxySource = result.generatedSource("io.repro.app.Oidc_ClientProxy");
        assertTrue(proxySource.contains("super((io.vidocq.vauban.api.ProxyLink) null)"),
                "the generated proxy must chain to the marker constructor, got:\n" + proxySource);
    }

    @Test
    @DisplayName("a non-private final method on an injected normal-scoped bean fails the build")
    void finalMethodBeanIsRejected() throws IOException {
        var result = compile(CONFIG, CONSUMER, """
                package io.repro.app;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class Oidc {
                    public final String issuer() { return "fixed"; }
                }
                """);

        assertFalse(result.success(),
                "a final method makes a normal-scoped bean unproxyable: " + result.messages());
        assertTrue(hasVaubanError(result, "final method"),
                "the error must name the final method: " + result.messages());
    }

    @Test
    @DisplayName("a plain no-arg-constructor bean keeps compiling")
    void plainBeanStillCompiles() throws IOException {
        var result = compile(CONFIG, CONSUMER, """
                package io.repro.app;

                import jakarta.enterprise.context.ApplicationScoped;

                @ApplicationScoped
                public class Oidc {
                    public String issuer() { return "static"; }
                }
                """);

        assertTrue(result.success(), "regression guard: " + result.messages());
    }

    // ---- Harness --------------------------------------------------------------------------------

    /** Compiles the sources with the Vauban processor; keeps diagnostics and generated sources. */
    private CompilationResult compile(String... sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        Path out = tempDir.resolve("classes");
        Path generated = tempDir.resolve("generated-sources");
        Files.createDirectories(out);
        Files.createDirectories(generated);

        var options = List.of(
                "-d", out.toString(),
                "-s", generated.toString(),
                "--release", "25",
                "-classpath", resolveCompilationClasspath(),
                "-proc:full");

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null,
                    writeSources(tempDir.resolve("src"), sources));
            task.setProcessors(List.of(new VaubanProcessor()));
            boolean success = task.call();

            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }
            return new CompilationResult(success, messages, generated);
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
                jakarta.interceptor.Interceptor.class,
                io.vidocq.vauban.api.ProxyLink.class)) {
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

    record CompilationResult(boolean success, List<String> messages, Path generatedDir) {

        /** Content of a generated source file, located by its fully-qualified class name. */
        String generatedSource(String fqn) throws IOException {
            Path file = generatedDir.resolve(fqn.replace('.', '/') + ".java");
            if (Files.exists(file)) {
                return Files.readString(file);
            }
            // Filer implementations may flatten the layout — search by simple name as a fallback.
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1) + ".java";
            try (Stream<Path> walk = Files.walk(generatedDir)) {
                var match = walk.filter(p -> p.getFileName().toString().equals(simpleName)).findFirst();
                if (match.isPresent()) {
                    return Files.readString(match.get());
                }
            }
            throw new AssertionError("generated source not found: " + fqn);
        }
    }
}
