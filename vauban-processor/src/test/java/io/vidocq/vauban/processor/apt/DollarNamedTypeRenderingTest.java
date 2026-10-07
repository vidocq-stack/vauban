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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor renders Java source naming a type whose own name contains {@code $} — a top-level
 * {@code app.Dollar$Service}, which is not a nested type {@code Service} of a class {@code Dollar}
 * — by its canonical name, never by turning each {@code $} of its binary name into {@code .}
 * (BUG-20261007-01): the intercepted subclass of such a bean, the client proxy a producer of such a
 * class or interface gets, and every method signature naming such a type.
 */
@DisplayName("Generated sources naming a type whose own name contains '$'")
class DollarNamedTypeRenderingTest {

    @TempDir
    Path tempDir;

    private static final String AUDITED = """
            package app;

            @jakarta.interceptor.InterceptorBinding
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD})
            public @interface Audited {}
            """;

    private static final String AUDIT_INTERCEPTOR = """
            package app;

            @Audited
            @jakarta.interceptor.Interceptor
            @jakarta.annotation.Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
            public class AuditInterceptor {
                @jakarta.interceptor.AroundInvoke
                public Object audit(jakarta.interceptor.InvocationContext ctx) throws Exception { return ctx.proceed(); }
            }
            """;

    private static final String NOTE = """
            package app;

            public class Dollar$Note {
                public String text() { return "note"; }
            }
            """;

    private static final String AUDITED_SERVICE = """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            @Audited
            public class Dollar$AuditedService {
                public String name() { return "audited"; }
            }
            """;

    private static final String SIGNATURE_SERVICE = """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            @Audited
            public class SignatureService {
                public Dollar$Note echo(Dollar$Note note) { return note; }
                public Dollar$Note[] all(Dollar$Note... notes) { return notes; }
            }
            """;

    private static final String PRODUCED = """
            package app;

            public class Dollar$Produced {
                public Dollar$Produced() {}
                public String hello() { return "produced"; }
            }
            """;

    private static final String PORT = """
            package app;

            public interface Dollar$Port {
                String port();
                Dollar$Note note(Dollar$Note note);
            }
            """;

    private static final String PRODUCERS = """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Producers {
                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.context.ApplicationScoped
                public Dollar$Produced produced() { return new Dollar$Produced(); }

                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.context.ApplicationScoped
                public Dollar$Port port() {
                    return new Dollar$Port() {
                        public String port() { return "port"; }
                        public Dollar$Note note(Dollar$Note note) { return note; }
                    };
                }
            }
            """;

    @Test
    @DisplayName("an intercepted bean whose name contains '$' gets a subclass that compiles")
    void interceptedDollarBean() throws Exception {
        var result = compile(Map.of("Audited", AUDITED, "AuditInterceptor", AUDIT_INTERCEPTOR,
                "Dollar$AuditedService", AUDITED_SERVICE));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertTrue(Files.exists(result.genDir().resolve("app/Dollar$AuditedService$$Intercepted.java")),
                "the subclass is rendered as source. Generated: " + emitted(result.genDir()));
        assertTrue(Files.exists(result.outputDir().resolve("app/Dollar$AuditedService$$Intercepted.class")),
                "and compiled. Emitted: " + emitted(result.outputDir()));
    }

    @Test
    @DisplayName("method signatures naming a type whose name contains '$' compile in the proxy and the subclass")
    void dollarTypeInSignatures() throws Exception {
        var result = compile(Map.of("Audited", AUDITED, "AuditInterceptor", AUDIT_INTERCEPTOR,
                "Dollar$Note", NOTE, "SignatureService", SIGNATURE_SERVICE));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertTrue(Files.exists(result.outputDir().resolve("app/SignatureService_ClientProxy.class"))
                        && Files.exists(result.outputDir().resolve("app/SignatureService$$Intercepted.class")),
                "the source proxy and subclass are compiled. Emitted: " + emitted(result.outputDir()));
    }

    @Test
    @DisplayName("a producer of a class whose name contains '$' gets a proxy that compiles")
    void producerOfDollarClass() throws Exception {
        var result = compile(Map.of("Dollar$Note", NOTE, "Dollar$Produced", PRODUCED, "Dollar$Port", PORT,
                "Producers", PRODUCERS));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertTrue(emitted(result.genDir()).stream()
                        .anyMatch(f -> f.startsWith("app/Dollar_Produced$$") && f.endsWith("_ClientProxy.java")),
                "the producer proxy is rendered as source. Generated: " + emitted(result.genDir()));
        assertTrue(emitted(result.genDir()).stream()
                        .anyMatch(f -> f.startsWith("app/Dollar_Port$$") && f.endsWith("_ClientProxy.java")),
                "the interface producer proxy is rendered as source. Generated: " + emitted(result.genDir()));
    }

    private static final String GRADE = """
            package app;

            public enum Dollar$Grade { LOW, HIGH }
            """;

    private static final String RANK = """
            package app;

            @jakarta.inject.Qualifier
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.FIELD,
                    java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PARAMETER})
            public @interface Dollar$Rank {
                Dollar$Grade grade() default Dollar$Grade.LOW;
                Class<?> kind() default Dollar$Note.class;
                Dollar$Grade[] grades() default {Dollar$Grade.LOW};
                Class<?>[] kinds() default {Dollar$Note.class};
            }
            """;

    private static final String RANKED_SERVICE = """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            @Dollar$Rank(grade = Dollar$Grade.HIGH)
            public class RankedService {
                public String rank() { return "high"; }
            }
            """;

    private static final String HOLDER = """
            package app;

            public class Holder {
                public static class Value {
                    public Value() {}
                    public String value() { return "value"; }
                }

                public interface Port {
                    String port();
                }
            }
            """;

    private static final String NESTED_PRODUCERS = """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class NestedProducers {
                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.context.ApplicationScoped
                public Holder.Value value() { return new Holder.Value(); }

                @jakarta.enterprise.inject.Produces
                @jakarta.enterprise.context.ApplicationScoped
                public Holder.Port port() { return () -> "port"; }
            }
            """;

    @Test
    @DisplayName("an annotation type, a class value and an enum value whose names contain '$' are written in source")
    void annotationArtefactsOfDollarTypes() throws Exception {
        var result = compile(Map.of("Dollar$Note", NOTE, "Dollar$Grade", GRADE, "Dollar$Rank", RANK,
                "RankedService", RANKED_SERVICE));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        var provider = Files.readString(result.genDir().resolve("app/_VaubanComponents.java"));
        assertTrue(provider.contains("implements app.Dollar$Rank"),
                "the annotation literal implements the annotation type by its canonical name:\n" + provider);
    }

    @Test
    @DisplayName("a producer of a nested class or interface gets a proxy rendered at build time")
    void producerOfNestedTypes() throws Exception {
        var result = compile(Map.of("Holder", HOLDER, "NestedProducers", NESTED_PRODUCERS));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertTrue(emitted(result.genDir()).stream()
                        .anyMatch(f -> f.startsWith("app/Holder_Value$$") && f.endsWith("_ClientProxy.java")),
                "the producer proxy of the nested class is rendered as source. Generated: " + emitted(result.genDir()));
        assertTrue(emitted(result.genDir()).stream()
                        .anyMatch(f -> f.startsWith("app/Holder_Port$$") && f.endsWith("_ClientProxy.java")),
                "the producer proxy of the nested interface is rendered as source. Generated: "
                        + emitted(result.genDir()));
    }

    // ---- minimal in-process compilation harness (with -s for generated sources) ----

    private CompilationResult compile(Map<String, String> sourcesBySimpleName) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var dir = Files.createDirectories(tempDir.resolve("src").resolve("app"));
        var files = new ArrayList<JavaFileObject>();
        for (var e : new LinkedHashMap<>(sourcesBySimpleName).entrySet()) {
            var file = dir.resolve(e.getKey() + ".java");
            Files.writeString(file, e.getValue());
            files.add(new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                    return Files.readString(file);
                }
            });
        }

        var outputDir = Files.createDirectories(tempDir.resolve("classes"));
        var genDir = Files.createDirectories(tempDir.resolve("gen"));

        var options = List.of(
                "-d", outputDir.toString(),
                "-s", genDir.toString(),
                "--release", "25",
                "-classpath", resolveCompilationClasspath(),
                "-proc:full");

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null, files);
            task.setProcessors(List.of(new VaubanProcessor()));
            var success = task.call();

            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                    messages.add(d.getKind() + ": " + d.getSource() + ":" + d.getLineNumber() + " "
                            + d.getMessage(null));
                }
            }
            return new CompilationResult(success, outputDir, genDir, messages);
        }
    }

    record CompilationResult(boolean success, Path outputDir, Path genDir, List<String> messages) {}

    /** Every file under {@code root}, relative — used to make a failed assertion diagnosable. */
    private static List<String> emitted(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).sorted().toList();
        }
    }

    private static String resolveCompilationClasspath() {
        var paths = new LinkedHashSet<String>();
        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            paths.addAll(List.of(cp.split(File.pathSeparator)));
        }
        for (var clazz : List.of(jakarta.enterprise.context.ApplicationScoped.class, jakarta.inject.Inject.class,
                jakarta.interceptor.Interceptor.class, jakarta.annotation.Priority.class,
                io.vidocq.vauban.api.ProxyLink.class, VaubanProcessor.class)) {
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
