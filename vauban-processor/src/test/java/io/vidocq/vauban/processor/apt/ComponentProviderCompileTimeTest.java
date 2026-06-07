package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.api.VaubanComponentProvider;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the APT-generated {@code _VaubanComponents} provider: a packaged,
 * no-arg managed bean must yield a provider that instantiates it in-module, the generated
 * source must compile, and a class-path service file must be registered.
 */
@DisplayName("Component provider - APT generation")
class ComponentProviderCompileTimeTest {

    private static final String SERVICE_PATH =
            "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("generates a compiling _VaubanComponents + service file for a packaged no-arg bean")
    void generatesProviderForPackagedNoArgBean() throws Exception {
        var result = compile("HelloResource", """
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

    @Test
    @DisplayName("generates a compiling args-overload casting resolved deps for an @Inject-ctor bean")
    void generatesArgAwareProviderForInjectConstructorBean() throws Exception {
        var result = compile("GreetingService", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class GreetingService {
                    private final Repo repo;
                    @jakarta.inject.Inject
                    public GreetingService(Repo repo) { this.repo = repo; }
                    public String greet() { return repo.name(); }
                }

                @jakarta.enterprise.context.ApplicationScoped
                class Repo {
                    String name() { return "repo"; }
                }
                """);

        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        var genSource = result.genDir().resolve("app/_VaubanComponents.java");
        assertTrue(Files.exists(genSource), "expected generated provider source at " + genSource);
        var src = Files.readString(genSource);
        assertTrue(src.contains("public Object create(String className, Object[] args)"), src);
        assertTrue(src.contains(
                "case \"app.GreetingService\" -> new app.GreetingService((app.Repo) args[0]);"), src);

        assertTrue(Files.exists(result.outputDir().resolve("app/_VaubanComponents.class")),
                "the generated provider (with the args overload) must compile to a .class");
    }

    @Test
    @DisplayName("a nested bean is skipped (no bogus package, build still compiles)")
    void nestedBeanIsSkipped() throws Exception {
        var result = compile("Outer", """
                package app;

                public class Outer {
                    @jakarta.enterprise.context.ApplicationScoped
                    public static class Inner {
                        public String v() { return "inner"; }
                    }
                }

                @jakarta.enterprise.context.ApplicationScoped
                class TopLevelBean {
                    public String v() { return "top"; }
                }
                """);

        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        var genSource = result.genDir().resolve("app/_VaubanComponents.java");
        if (Files.exists(genSource)) {
            var src = Files.readString(genSource);
            assertTrue(src.contains("case \"app.TopLevelBean\" -> new app.TopLevelBean();"), src);
            assertFalse(src.contains("Outer.Inner"),
                    "nested bean must not be referenced by the generated provider: " + src);
        }
        // The key assertion is that no bogus `app.Outer` package provider was emitted and the
        // round-2 compilation of the generated provider succeeded.
        assertFalse(Files.exists(result.genDir().resolve("app/Outer/_VaubanComponents.java")),
                "no provider must be generated into a class-as-package directory");
    }

    @Test
    @DisplayName("pre-generates a <bean>$$Intercepted source subclass the provider can reference")
    void generatesInterceptedSubclassForBoundBean() throws Exception {
        var result = compile("AuditedService", """
                package app;

                @jakarta.interceptor.InterceptorBinding
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                @interface Audited {}

                @jakarta.enterprise.context.ApplicationScoped
                @Audited
                public class AuditedService {
                    public String run() { return "ok"; }
                }
                """);

        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        // The APT now emits the subclass as readable Java SOURCE (not bytecode); javac compiles it
        // in a later round. That dissolves the "generated source cannot see generated bytecode" wall.
        var genSub = result.genDir().resolve("app/AuditedService$$Intercepted.java");
        assertTrue(Files.exists(genSub),
                "expected APT-generated SOURCE app/AuditedService$$Intercepted.java. Messages: "
                        + result.messages());
        var subSrc = Files.readString(genSub);
        assertTrue(subSrc.contains("extends app.AuditedService"), subSrc);
        assertTrue(subSrc.contains("public java.lang.String $$super$run() throws Exception"), subSrc);
        assertTrue(subSrc.contains("private static Object $$ti$run("), subSrc);
        assertTrue(subSrc.contains("AuditedService$$Intercepted::$$ti$run"), subSrc);

        // It must compile (round 2) to a .class, so the runtime never defines it reflectively
        // (which would need `opens … to io.vidocq.vauban.core` on the module path).
        assertTrue(Files.exists(result.outputDir().resolve("app/AuditedService$$Intercepted.class")),
                "the generated subclass must compile to a .class. Messages: " + result.messages());

        // The wall is gone: the sibling _VaubanComponents SOURCE references the (also-source)
        // subclass by name and instantiates it in-module — no bytecode provider needed.
        var provSrc = Files.readString(result.genDir().resolve("app/_VaubanComponents.java"));
        assertTrue(provSrc.contains("new app.AuditedService$$Intercepted()"),
                "the _VaubanComponents provider must instantiate the intercepted subclass in-module: "
                        + provSrc);
    }

    @Test
    @DisplayName("overloaded intercepted methods get distinct $$ti$ glue names (compiles)")
    void generatesDistinctGlueForOverloadedInterceptedMethods() throws Exception {
        var result = compile("OverloadedService", """
                package app;

                @jakarta.interceptor.InterceptorBinding
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                @interface Audited {}

                @jakarta.enterprise.context.ApplicationScoped
                @Audited
                public class OverloadedService {
                    public String run() { return "0"; }
                    public String run(int n) { return "" + n; }
                    public String run(String s) { return s; }
                }
                """);

        // The $$ti$ glue erases to (Object, Object[]) Object, so without per-overload disambiguation
        // the three run(...) methods would emit three duplicate $$ti$run — invalid source/class.
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        var subSrc = Files.readString(
                result.genDir().resolve("app/OverloadedService$$Intercepted.java"));
        assertTrue(subSrc.contains("$$ti$run$0"), subSrc);
        assertTrue(subSrc.contains("$$ti$run$1"), subSrc);
        assertTrue(subSrc.contains("$$ti$run$2"), subSrc);
    }

    // ---- minimal in-process compilation harness (with -s for generated sources) ----

    private CompilationResult compile(String simpleName, String source) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var sourceDir = Files.createDirectories(tempDir.resolve("src"));
        var dir = Files.createDirectories(sourceDir.resolve("app"));
        var file = dir.resolve(simpleName + ".java");
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
