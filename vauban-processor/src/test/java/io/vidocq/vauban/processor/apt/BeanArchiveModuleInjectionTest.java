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
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertFalse(result.messages().stream().anyMatch(m -> m.contains("deferred to the runtime container")),
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

        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("WARNING") && m.contains("Unprovided")
                        && m.contains("deferred to the runtime container")),
                "no bean archive provides it: the warning stays: " + result.messages());
    }

    // ---- Helpers -------------------------------------------------------------------------------

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
        return compile("app", sources, List.of(core));
    }

    private Path compileModule(String name, Map<String, String> sources, List<Path> extraModulePath) throws Exception {
        var result = compile(name, sources, extraModulePath);
        assertTrue(result.success(), "module " + name + " must compile: " + result.messages());
        return result.classes();
    }

    private CompilationResult compile(String name, Map<String, String> sources, List<Path> extraModulePath)
            throws Exception {
        Path src = Files.createDirectories(tempDir.resolve(name + "-src"));
        var files = new ArrayList<Path>();
        for (var entry : sources.entrySet()) {
            Path file = src.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            files.add(file);
        }
        var modulePath = new LinkedHashSet<String>();
        for (var clazz : List.of(VaubanComponentProvider.class, ApplicationScoped.class,
                jakarta.inject.Inject.class, jakarta.annotation.Priority.class,
                jakarta.interceptor.Interceptor.class, jakarta.enterprise.lang.model.AnnotationInfo.class)) {
            modulePath.add(Path.of(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        extraModulePath.forEach(p -> modulePath.add(p.toString()));
        Path classes = Files.createDirectories(tempDir.resolve(name + "-classes"));
        Path generated = Files.createDirectories(tempDir.resolve(name + "-gen"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var options = List.of("--release", "25", "-proc:full",
                    "--module-path", String.join(File.pathSeparator, modulePath),
                    "-d", classes.toString(), "-s", generated.toString());
            var task = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new VaubanProcessor()));
            boolean success = task.call();
            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }
            return new CompilationResult(success, messages, classes, generated);
        }
    }

    record CompilationResult(boolean success, List<String> messages, Path classes, Path generated) {
    }

}
