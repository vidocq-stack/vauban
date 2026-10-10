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

        // The normal-scoped bean has only an injected (arg-bearing) constructor, yet it still gets a
        // SOURCE client proxy whose no-arg ctor calls the injected super ctor with default values —
        // so it is proxyable in-module (no opens/exports) without a no-arg constructor on the bean.
        var proxySource = result.genDir().resolve("app/GreetingService_ClientProxy.java");
        assertTrue(Files.exists(proxySource), "expected generated proxy source at " + proxySource);
        var proxySrc = Files.readString(proxySource);
        assertTrue(proxySrc.contains("super((app.Repo) null)"),
                "proxy ctor must call the injected super ctor with default values: " + proxySrc);
        assertTrue(src.contains("case \"app.GreetingService_ClientProxy\""),
                "createClientProxy must instantiate the proxy in-module: " + src);
        assertTrue(Files.exists(result.outputDir().resolve("app/GreetingService_ClientProxy.class")),
                "the generated proxy must compile to a .class");
    }

    @Test
    @DisplayName("an @Inject constructor wins over a public no-arg one, as the container picks it (grimm#15)")
    void injectConstructorWinsOverNoArgConstructor() throws Exception {
        // The container builds a bean through its @Inject constructor and asks the provider for
        // create(name, args). A provider that only knew the no-arg constructor answered null, and
        // the container fell back to reflection — which a named module refuses without `opens`.
        var result = compile("ModelCache", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class ModelCache {
                    private final Config config;
                    @jakarta.inject.Inject
                    public ModelCache(Config config) { this.config = config; }
                    /** For tests and use without CDI. */
                    public ModelCache() { this(new Config()); }
                }

                @jakarta.enterprise.context.ApplicationScoped
                class Config {
                }
                """);

        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        var src = Files.readString(result.genDir().resolve("app/_VaubanComponents.java"));
        assertTrue(src.contains("case \"app.ModelCache\" -> new app.ModelCache((app.Config) args[0]);"), src);
    }

    @Test
    @DisplayName("a nested bean is carried by the provider, keyed binary and built canonical")
    void nestedBeanIsCarriedByTheProvider() throws Exception {
        var result = compile("Outer", """
                package app;

                public class Outer {
                    @jakarta.enterprise.context.ApplicationScoped
                    public static class Inner {
                        public String enhancedField;
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
        assertTrue(Files.exists(genSource), "the package provider must be generated");
        var src = Files.readString(genSource);
        assertTrue(src.contains("case \"app.TopLevelBean\" -> new app.TopLevelBean();"), src);
        // The two names of a nested type are not interchangeable: the container looks a component
        // up by Class#getName (binary), and only the canonical name can be written in source.
        assertTrue(src.contains("case \"app.Outer$Inner\" -> new app.Outer.Inner();"),
                "a nested bean must be instantiated in-module, keyed by its binary name: " + src);
        assertTrue(src.contains("var b = (app.Outer.Inner) bean;"), src);
        assertTrue(src.contains("b.enhancedField = (java.lang.String) value; return true;"), src);
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

    @Test
    @DisplayName("intercepted methods with primitive-array params/return compile (no VerifyError)")
    void generatesValidGlueForPrimitiveArrayInterceptedMethods() throws Exception {
        var result = compile("ArrayService", """
                package app;

                @jakarta.interceptor.InterceptorBinding
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                @interface Audited {}

                @jakarta.enterprise.context.ApplicationScoped
                @Audited
                public class ArrayService {
                    // int[]/long[] are reference types: must load as references, never box/unbox as
                    // scalar int/long, and occupy one slot (regression for TypeRef array handling).
                    public int[] compute(int[] xs, long[] ys, String s) { return xs; }
                }
                """);

        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        var subSrc = Files.readString(result.genDir().resolve("app/ArrayService$$Intercepted.java"));
        assertTrue(subSrc.contains("public int[] compute(int[] p0, long[] p1, java.lang.String p2)"), subSrc);
        // params stored directly in Object[] (no Integer.valueOf on the array), unboxed by a cast.
        assertTrue(subSrc.contains("(int[]) $$params[0]"), subSrc);
        assertTrue(subSrc.contains("getDeclaredMethod(\"$$super$compute\", int[].class, long[].class, java.lang.String.class)"), subSrc);
    }

    @Test
    @DisplayName("the compiled provider declares exactly what its switches handle, a nested bean included")
    void compiledProviderCoverageMatchesItsSwitches() throws Exception {
        var result = compile("Greeter", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    @jakarta.inject.Inject jakarta.enterprise.inject.spi.BeanManager beanManager;

                    @jakarta.inject.Inject
                    void setUp(jakarta.enterprise.inject.spi.BeanManager beanManager) {}

                    @jakarta.enterprise.context.Dependent
                    public static class Inner {}
                }
                """);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {result.outputDir().toUri().toURL()},
                getClass().getClassLoader())) {
            var provider = (VaubanComponentProvider)
                    loader.loadClass("app._VaubanComponents").getDeclaredConstructor().newInstance();
            var coverage = provider.coverage();
            var greeter = loader.loadClass("app.Greeter").getDeclaredConstructor().newInstance();

            assertEquals(io.vidocq.vauban.api.GeneratedCoverage.Generator.APT, coverage.generator());
            assertTrue(coverage.instantiated().contains("app.Greeter"), coverage.toString());
            for (var name : List.of("app.Greeter", "app.Greeter$Inner", "app.Greeter.Inner")) {
                assertEquals(provider.create(name) != null, coverage.instantiated().contains(name), name);
            }
            assertEquals(provider.injectField(greeter, "app.Greeter", "beanManager", null),
                    coverage.injectedFields().contains("app.Greeter#beanManager"), coverage.toString());
            var setUp = "setUp(jakarta.enterprise.inject.spi.BeanManager)";
            assertEquals(provider.invoke(greeter, "app.Greeter", setUp, new Object[] {null})
                            != VaubanComponentProvider.NOT_INVOKED,
                    coverage.invokedMethods().contains("app.Greeter#" + setUp), coverage.toString());
            assertEquals(provider.createClientProxy("app.Greeter_ClientProxy", () -> null) != null,
                    coverage.clientProxies().contains("app.Greeter_ClientProxy"), coverage.toString());
        }
    }

    @Test
    @DisplayName("public mutable fields have in-module write cases before BCE adds @Inject")
    void publicFieldsCanBeInjectedAfterRuntimeEnhancement() throws Exception {
        var result = compile("EnhancedBean", """
                package app;

                @jakarta.enterprise.context.Dependent
                public class EnhancedBean {
                    public Object context;
                    public String unit;
                    public java.util.List<String> repositories;
                    @jakarta.inject.Inject public jakarta.enterprise.inject.spi.BeanManager alreadyInjected;
                    public static Object staticField;
                    public final Object finalField = new Object();
                    private Object privateField;
                    protected Object protectedField;
                    Object packageField;
                    public int primitiveField;
                    private static class Hidden {}
                    public Hidden hiddenType;
                }
                """);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {result.outputDir().toUri().toURL()},
                getClass().getClassLoader())) {
            var provider = (VaubanComponentProvider)
                    loader.loadClass("app._VaubanComponents").getDeclaredConstructor().newInstance();
            var bean = provider.create("app.EnhancedBean");
            var beanType = loader.loadClass("app.EnhancedBean");
            var context = new Object();
            var repositories = List.of("repo");
            assertEquals(java.util.Set.of("app.EnhancedBean#context", "app.EnhancedBean#unit",
                            "app.EnhancedBean#repositories", "app.EnhancedBean#alreadyInjected"),
                    provider.coverage().injectedFields());
            assertTrue(provider.injectField(bean, "app.EnhancedBean", "context", context));
            assertTrue(provider.injectField(bean, "app.EnhancedBean", "unit", "main"));
            assertTrue(provider.injectField(bean, "app.EnhancedBean", "repositories", repositories));
            assertTrue(provider.injectField(bean, "app.EnhancedBean", "alreadyInjected", null));
            assertTrue(beanType.getField("context").get(bean) == context);
            assertEquals("main", beanType.getField("unit").get(bean));
            assertTrue(beanType.getField("repositories").get(bean) == repositories);
            for (var field : List.of("staticField", "finalField", "privateField", "protectedField",
                    "packageField", "primitiveField", "hiddenType", "missing")) {
                assertFalse(provider.injectField(bean, "app.EnhancedBean", field, null), field);
            }
            assertFalse(provider.injectField(bean, "app.Unknown", "context", context));
        }
    }

    @Test
    @DisplayName("the generated provider writes a runtime-BCE candidate in a named module without opens")
    void publicFieldWriteOnModulePathWithoutOpens() throws Exception {
        var result = compile("ModuleBean", """
                package app;

                @jakarta.enterprise.context.Dependent
                public class ModuleBean {
                    public String unit;

                    public static boolean verify() {
                        var bean = new ModuleBean();
                        for (var provider : java.util.ServiceLoader.load(
                                io.vidocq.vauban.api.VaubanComponentProvider.class,
                                ModuleBean.class.getClassLoader())) {
                            if (provider.injectField(bean, "app.ModuleBean", "unit", "main")) {
                                return "main".equals(bean.unit);
                            }
                        }
                        return false;
                    }
                }
                """);
        assertTrue(result.success(), result.messages().toString());
        var descriptor = tempDir.resolve("module-info.java");
        Files.writeString(descriptor, """
                module app.enhanced {
                    requires io.vidocq.vauban.api;
                    requires jakarta.cdi;
                    uses io.vidocq.vauban.api.VaubanComponentProvider;
                    provides io.vidocq.vauban.api.VaubanComponentProvider with app._VaubanComponents;
                    exports app to io.vidocq.vauban.processor;
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var options = List.of("-proc:none", "--release", "25",
                    "--module-path", resolveCompilationClasspath(),
                    "--patch-module", "app.enhanced=" + result.outputDir(),
                    "-d", result.outputDir().toString());
            assertTrue(compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjects(descriptor)).call(), diagnostics.getDiagnostics().toString());
        }
        var finder = java.lang.module.ModuleFinder.of(result.outputDir());
        var configuration = ModuleLayer.boot().configuration()
                .resolve(finder, java.lang.module.ModuleFinder.of(), java.util.Set.of("app.enhanced"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, getClass().getClassLoader());
        var module = layer.findModule("app.enhanced").orElseThrow();
        assertFalse(module.isOpen("app"));
        assertFalse(module.isExported("app", VaubanComponentProvider.class.getModule()));
        var probe = layer.findLoader("app.enhanced").loadClass("app.ModuleBean");
        assertEquals(true, probe.getMethod("verify").invoke(null));
    }

    @Test
    @DisplayName("a public field typed from another module keeps the module's provides clause compilable in one javac pass")
    void publicFieldOfAnotherModuleTypeDoesNotCompleteTheCompiledModule() throws Exception {
        // Module directives of the module being compiled must not be read before the last round has
        // written _VaubanComponents: completing them resolves `provides` too early ("cannot find symbol").
        var root = Files.createDirectories(tempDir.resolve("module-src"));
        Files.writeString(root.resolve("module-info.java"), """
                module app.fields {
                    requires io.vidocq.vauban.api;
                    requires java.sql;
                    provides io.vidocq.vauban.api.VaubanComponentProvider with app._VaubanComponents;
                }
                """);
        var pkg = Files.createDirectories(root.resolve("app"));
        Files.writeString(pkg.resolve("Holder.java"), """
                package app;

                @jakarta.enterprise.context.Dependent
                public class Holder {
                    public java.sql.Connection connection;
                }
                """);
        var modulePath = new LinkedHashSet<String>();
        for (var clazz : List.of(VaubanComponentProvider.class, ApplicationScoped.class,
                jakarta.inject.Inject.class, jakarta.annotation.Priority.class,
                jakarta.interceptor.Interceptor.class, jakarta.enterprise.lang.model.AnnotationInfo.class)) {
            modulePath.add(Path.of(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        var outputDir = Files.createDirectories(tempDir.resolve("module-classes"));
        var genDir = Files.createDirectories(tempDir.resolve("module-gen"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var options = List.of("--release", "25", "-proc:full",
                    "--module-path", String.join(File.pathSeparator, modulePath),
                    "-d", outputDir.toString(), "-s", genDir.toString());
            var task = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjects(root.resolve("module-info.java"), pkg.resolve("Holder.java")));
            task.setProcessors(List.of(new VaubanProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        var src = Files.readString(genDir.resolve("app/_VaubanComponents.java"));
        assertTrue(src.contains("b.connection = (java.sql.Connection) value; return true;"), src);
        assertTrue(Files.exists(outputDir.resolve("app/_VaubanComponents.class")));
    }

    @Test
    @DisplayName("bean methods that declare checked exceptions compile, and the exception reaches the caller as is (vauban#145)")
    void beanMethodsDeclaringCheckedExceptionsCompile() throws Exception {
        var result = compile("Probe", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Probe {
                    @jakarta.inject.Inject
                    public Probe(jakarta.enterprise.inject.spi.BeanManager beanManager) throws java.io.IOException {}

                    protected Probe() throws java.io.IOException {}

                    @jakarta.inject.Inject
                    void init(jakarta.enterprise.inject.spi.BeanManager beanManager) throws java.io.IOException {}

                    void onStart(@jakarta.enterprise.event.Observes
                                 @jakarta.enterprise.context.Initialized(jakarta.enterprise.context.ApplicationScoped.class)
                                 Object event) throws Exception {
                        throw new java.io.IOException("from the observer");
                    }

                    @jakarta.enterprise.inject.Produces
                    String name() throws java.io.IOException {
                        return "probe";
                    }

                    @jakarta.enterprise.context.Dependent
                    public static class Failing {
                        public Failing() throws java.io.IOException {
                            throw new java.io.IOException("from the constructor");
                        }
                    }
                }
                """);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {result.outputDir().toUri().toURL()},
                getClass().getClassLoader())) {
            var provider = (VaubanComponentProvider)
                    loader.loadClass("app._VaubanComponents").getDeclaredConstructor().newInstance();
            var type = loader.loadClass("app.Probe");
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            var probe = constructor.newInstance();

            assertEquals("probe", provider.invoke(probe, "app.Probe", "name()", new Object[0]));
            var thrown = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> provider.invoke(probe, "app.Probe", "onStart(java.lang.Object)", new Object[] {"event"}));
            assertEquals("from the observer", thrown.getMessage());
            var fromConstructor = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> provider.create("app.Probe$Failing"));
            assertEquals("from the constructor", fromConstructor.getMessage());
        }
    }

    @Test
    @DisplayName("an intercepted bean whose constructors declare checked exceptions compiles (vauban#145)")
    void interceptedBeanWithThrowingConstructorsCompiles() throws Exception {
        var result = compile("GuardedService", """
                package app;

                @jakarta.interceptor.InterceptorBinding
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                @interface Guarded {}

                @jakarta.enterprise.context.ApplicationScoped
                @Guarded
                public class GuardedService {
                    protected GuardedService() throws java.io.IOException {}

                    @jakarta.inject.Inject
                    public GuardedService(jakarta.enterprise.inject.spi.BeanManager beanManager) throws java.io.IOException {}

                    public String run() throws java.io.IOException { return "ok"; }
                }
                """);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertTrue(Files.exists(result.outputDir().resolve("app/GuardedService$$Intercepted.class")),
                "the generated subclass must compile to a .class. Messages: " + result.messages());
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
