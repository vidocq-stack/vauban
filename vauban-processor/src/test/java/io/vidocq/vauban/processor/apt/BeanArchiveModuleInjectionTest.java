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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection point satisfied by a bean of a <strong>bean archive module</strong> the application depends on
 * validates at build time.
 *
 * <p>Since Vidocq/vauban#23 the processor resolves a dependency type that is itself a bean. An interface whose
 * producer or implementation is another class of that module was still deferred to the container with a warning:
 * an application injecting {@code JsonWebToken}, produced by cervantes-cdi-vauban, warned at every build although
 * the bean is there. A module on the module path that {@code provides VaubanComponentProvider} is a bean archive;
 * the processor now indexes the packages of its providers, through {@code Elements}, and resolves the deferred
 * types against the beans found there.</p>
 */
@DisplayName("Bean archive modules — a producer of another module satisfies the injection point at build")
class BeanArchiveModuleInjectionTest {

    @TempDir
    Path tempDir;

    private static final Map<String, String> CORE = Map.of(
            "module-info.java", """
                    module repro.core {
                        requires io.vidocq.vauban.api;
                        requires jakarta.cdi;
                        exports io.repro.core;
                        provides io.vidocq.vauban.api.VaubanComponentProvider with io.repro.core.internal._VaubanComponents;
                    }
                    """,
            "io/repro/core/TokenService.java", """
                    package io.repro.core;

                    public interface TokenService {
                        String issue();
                    }
                    """,
            "io/repro/core/Unprovided.java", """
                    package io.repro.core;

                    public interface Unprovided {
                    }
                    """,
            "io/repro/core/internal/TokenProducer.java", """
                    package io.repro.core.internal;

                    import io.repro.core.TokenService;
                    import jakarta.enterprise.context.ApplicationScoped;
                    import jakarta.enterprise.context.Dependent;
                    import jakarta.enterprise.inject.Produces;

                    @ApplicationScoped
                    public class TokenProducer {
                        @Produces
                        @Dependent
                        TokenService token() {
                            return () -> "token";
                        }
                    }
                    """);

    @Test
    @DisplayName("an interface produced by another module's bean resolves, without the deferral warning")
    void anInterfaceProducedInABeanArchiveModuleResolves() throws Exception {
        Path core = compileModule("core", CORE, List.of());

        var result = compileApp(core, """
                package io.repro.app;

                import io.repro.core.TokenService;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class HelloResource {
                    @Inject
                    TokenService tokens;

                    public String hello() { return tokens.issue(); }
                }
                """);

        assertTrue(result.success(), result.messages().toString());
        assertFalse(result.defers(),
                "the producer of repro.core satisfies the injection point: " + result.messages());
        String beansList = Files.readString(result.classes().resolve("META-INF/vauban-beans.list"));
        assertFalse(beansList.contains("TokenProducer"),
                "the other module's bean stays its module's, not listed by the application: " + beansList);
        assertFalse(Files.exists(result.generated().resolve("io/repro/core")),
                "nothing generated in the other module's packages (split package)");
    }

    @Test
    @DisplayName("an interface nobody produces is still deferred with a warning")
    void anInterfaceNobodyProducesIsStillDeferred() throws Exception {
        Path core = compileModule("core", CORE, List.of());

        var result = compileApp(core, """
                package io.repro.app;

                import io.repro.core.Unprovided;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;

                @ApplicationScoped
                public class HelloResource {
                    @Inject
                    Unprovided missing;

                    public String hello() { return String.valueOf(missing); }
                }
                """);

        assertTrue(result.defers("Unprovided"),
                "no bean archive provides it: the warning stays: " + result.messages());
    }

    // ---- A bean module the application does not read (BUG-20261115-04) -------------------------

    /** The API module: the interface the application injects, as {@code jakarta.servlet} holds HttpServletRequest. */
    private static final Map<String, String> API = Map.of(
            "module-info.java", """
                    module repro.api {
                        exports io.repro.api;
                    }
                    """,
            "io/repro/api/Exchange.java", """
                    package io.repro.api;

                    public interface Exchange {
                        String path();
                    }
                    """);

    /**
     * A bean module producing {@code Exchange}, as {@code io.vidocq.foy.cdi.vauban} produces HttpServletRequest:
     * the application does not read it, and its producer class is package-private.
     */
    private static Map<String, String> producerModule(String module, String pkg) {
        return Map.of(
                "module-info.java", """
                        module %s {
                            requires repro.api;
                            requires io.vidocq.vauban.api;
                            requires jakarta.cdi;
                            provides io.vidocq.vauban.api.VaubanComponentProvider with %s._VaubanComponents;
                        }
                        """.formatted(module, pkg),
                pkg.replace('.', '/') + "/ExchangeBeans.java", """
                        package %s;

                        import io.repro.api.Exchange;
                        import jakarta.enterprise.context.Dependent;
                        import jakarta.enterprise.context.RequestScoped;
                        import jakarta.enterprise.inject.Produces;

                        @Dependent
                        class ExchangeBeans {
                            @Produces
                            @RequestScoped
                            Exchange exchange() {
                                return () -> "/";
                            }
                        }
                        """.formatted(pkg));
    }

    private static final String EXCHANGE_RESOURCE = """
            package io.repro.app;

            import io.repro.api.Exchange;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Inject;

            @ApplicationScoped
            public class WhereAmI {
                @Inject
                Exchange exchange;

                public String where() { return exchange.path(); }
            }
            """;

    private static final Map<String, String> EXCHANGE_APP = Map.of(
            "module-info.java", """
                    module repro.app {
                        requires repro.api;
                        requires io.vidocq.vauban.api;
                        requires jakarta.cdi;
                        provides io.vidocq.vauban.api.VaubanComponentProvider with io.repro.app._VaubanComponents;
                    }
                    """,
            "io/repro/app/WhereAmI.java", EXCHANGE_RESOURCE);

    /**
     * Vidocq's layouts: the extension's -codegen bundle puts the brick's CDI jar on the processor path, and the jar is
     * either on the module path, required by no module of the application, or not there at all, as a runtime
     * dependency of the application.
     */
    @ParameterizedTest(name = "also on the module path: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("a bean module on the processor path, which the application does not read, satisfies its injection "
            + "point without the deferral warning")
    void aBeanModuleTheApplicationDoesNotReadResolvesFromTheProcessorPath(boolean onTheModulePath) throws Exception {
        Path api = compileModule("api", API, Layout.modules());
        Path beans = compileModule("beans", producerModule("repro.beans", "io.repro.beans"), Layout.modules(api));

        var modulePath = onTheModulePath ? List.of(api, beans) : List.of(api);
        var result = compile("app", EXCHANGE_APP, new Layout(modulePath, List.of(), List.of(beans)));

        assertTrue(result.success(), result.messages().toString());
        assertFalse(result.defers(),
                "the producer of repro.beans satisfies the injection point: " + result.messages());
        String beansList = Files.readString(result.classes().resolve("META-INF/vauban-beans.list"));
        assertFalse(beansList.contains("ExchangeBeans"),
                "the other module's bean stays its module's, not listed by the application: " + beansList);
        assertFalse(Files.exists(result.generated().resolve("io/repro/beans")),
                "nothing generated in the other module's packages (split package)");
    }

    @Test
    @DisplayName("a bean archive on the class path and the processor path satisfies a class path application")
    void aBeanArchiveOnTheClassPathResolvesFromTheProcessorPath() throws Exception {
        Path api = compileModule("api", API, Layout.modules());
        Path beans = compileModule("beans", producerModule("repro.beans", "io.repro.beans"), Layout.modules(api));

        var result = compile("app", Map.of("io/repro/app/WhereAmI.java", EXCHANGE_RESOURCE),
                new Layout(List.of(), List.of(api, beans), List.of(beans)));

        assertTrue(result.success(), result.messages().toString());
        assertFalse(result.defers(),
                "the producer of the bean archive satisfies the injection point: " + result.messages());
    }

    @Test
    @DisplayName("two bean archives on the processor path producing the same type: an ambiguity left to the "
            + "container, as a warning")
    void anAmbiguityAmongProcessorPathArchivesStaysAWarning() throws Exception {
        Path api = compileModule("api", API, Layout.modules());
        Path beans = compileModule("beans", producerModule("repro.beans", "io.repro.beans"), Layout.modules(api));
        Path more = compileModule("more", producerModule("repro.more", "io.repro.more"), Layout.modules(api));

        var result = compile("app", EXCHANGE_APP,
                new Layout(List.of(api, beans, more), List.of(), List.of(beans, more)));

        assertTrue(result.success(), "an ambiguity among other modules' beans does not fail the build: "
                + result.messages());
        assertTrue(result.defers("Ambiguous dependency", "io.repro.beans.ExchangeBeans",
                        "io.repro.more.ExchangeBeans"),
                "the choice is the container's, with every archive in view: " + result.messages());
    }

    /**
     * A bean module that is neither read by the compilation nor on its processor path stays out of sight: javac
     * gives a processor no way to list the module path, only the modules it resolved. Vidocq puts the brick's CDI
     * jar on the processor path; another build adds it with {@code --add-modules}.
     */
    @Test
    @DisplayName("a bean module neither read nor on the processor path stays deferred, with the warning")
    void aBeanModuleNeitherReadNorOnTheProcessorPathStaysDeferred() throws Exception {
        Path api = compileModule("api", API, Layout.modules());
        Path beans = compileModule("beans", producerModule("repro.beans", "io.repro.beans"), Layout.modules(api));

        var result = compile("app", EXCHANGE_APP, Layout.modules(api, beans));

        assertTrue(result.defers("Exchange"),
                "nothing the processor can read declares the producer: " + result.messages());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /**
     * Where a compilation finds what it does not compile: its module path, its class path (a compilation of the
     * unnamed module when not empty) and its processor path, which the processor's bean archive scan reads.
     */
    private record Layout(List<Path> modulePath, List<Path> classPath, List<Path> processorPath) {

        static Layout modules(Path... modulePath) {
            return new Layout(List.of(modulePath), List.of(), List.of());
        }
    }

    private CompilationResult compileApp(Path core, String resource) throws Exception {
        var sources = Map.of(
                "module-info.java", """
                        module repro.app {
                            requires repro.core;
                            requires io.vidocq.vauban.api;
                            requires jakarta.cdi;
                            provides io.vidocq.vauban.api.VaubanComponentProvider with io.repro.app._VaubanComponents;
                        }
                        """,
                "io/repro/app/HelloResource.java", resource);
        return compile("app", sources, Layout.modules(core));
    }

    private Path compileModule(String name, Map<String, String> sources, List<Path> extraModulePath) throws Exception {
        return compileModule(name, sources, new Layout(extraModulePath, List.of(), List.of()));
    }

    private Path compileModule(String name, Map<String, String> sources, Layout layout) throws Exception {
        var result = compile(name, sources, layout);
        assertTrue(result.success(), "module " + name + " must compile: " + result.messages());
        return result.classes();
    }

    private CompilationResult compile(String name, Map<String, String> sources, Layout layout) throws Exception {
        Path src = Files.createDirectories(tempDir.resolve(name + "-src"));
        var files = new ArrayList<Path>();
        for (var entry : sources.entrySet()) {
            Path file = src.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            files.add(file);
        }
        var apiPath = new LinkedHashSet<String>();
        for (var clazz : List.of(VaubanComponentProvider.class, ApplicationScoped.class,
                jakarta.inject.Inject.class, jakarta.annotation.Priority.class,
                jakarta.interceptor.Interceptor.class, jakarta.enterprise.lang.model.AnnotationInfo.class)) {
            apiPath.add(Path.of(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        boolean onClassPath = !layout.classPath().isEmpty();
        (onClassPath ? layout.classPath() : layout.modulePath()).forEach(p -> apiPath.add(p.toString()));
        Path classes = Files.createDirectories(tempDir.resolve(name + "-classes"));
        Path generated = Files.createDirectories(tempDir.resolve(name + "-gen"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var processorPath = new URL[layout.processorPath().size()];
        for (int i = 0; i < processorPath.length; i++) {
            processorPath[i] = layout.processorPath().get(i).toUri().toURL();
        }
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             var processorLoader = new URLClassLoader(processorPath, getClass().getClassLoader())) {
            var options = List.of("--release", "25", "-proc:full",
                    onClassPath ? "-classpath" : "--module-path", String.join(File.pathSeparator, apiPath),
                    "-d", classes.toString(), "-s", generated.toString());
            var task = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(files));
            var processor = new VaubanProcessor();
            processor.overrideBceClassLoader(processorLoader);
            task.setProcessors(List.of(processor));
            boolean success = task.call();
            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }
            return new CompilationResult(success, messages, classes, generated);
        }
    }

    record CompilationResult(boolean success, List<String> messages, Path classes, Path generated) {

        /** Whether a warning defers an injection point to the container, its message naming each of {@code names}. */
        boolean defers(String... names) {
            return messages.stream().anyMatch(message -> message.startsWith("WARNING")
                    && message.contains("deferred to the runtime container")
                    && Arrays.stream(names).allMatch(message::contains));
        }
    }

}
