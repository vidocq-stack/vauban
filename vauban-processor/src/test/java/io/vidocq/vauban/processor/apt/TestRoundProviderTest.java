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
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vauban#101: Maven compiles a module twice, the main sources then the tests, each with the processor. The test round
 * used to emit {@code <package>._VaubanComponents} again, from the test sources alone, and that class shadowed the
 * main round's when the tests ran: the main beans disappeared. The test round now emits
 * {@code _VaubanComponentsTests}, which the container picks up next to the main provider.
 */
@DisplayName("vauban#101: the test round's provider does not shadow the main one")
class TestRoundProviderTest {

    private static final String SERVICES = "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";

    @TempDir
    Path tmp;

    private Path compile(String name, String source, Path output, Path extraClasspath) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path src = tmp.resolve(name + "-src/com/acme/app");
        Files.createDirectories(src);
        String className = source.replaceAll("(?s).*class (\\w+).*", "$1");
        Path file = src.resolve(className + ".java");
        Files.writeString(file, source);
        Files.createDirectories(output);
        String classpath = resolveCompilationClasspath();
        if (extraClasspath != null) {
            classpath = extraClasspath + File.pathSeparator + classpath;
        }
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-d", output.toString(), "--release", "25", "-classpath", classpath, "-proc:full"),
                    null, fileManager.getJavaFileObjects(file));
            task.setProcessors(List.of(new VaubanProcessor()));
            var messages = new ArrayList<String>();
            boolean ok = task.call();
            diagnostics.getDiagnostics().forEach(d -> messages.add(d.getKind() + ": " + d.getMessage(null)));
            assertTrue(ok, "compilation failed: " + messages);
        }
        return output;
    }

    private static final String MAIN_BEAN = """
            package com.acme.app;
            @jakarta.enterprise.context.ApplicationScoped
            public class Greeter {}
            """;

    private static final String TEST_BEAN = """
            package com.acme.app;
            @jakarta.enterprise.context.ApplicationScoped
            public class TestFixture {}
            """;

    @Test
    @DisplayName("the main round emits _VaubanComponents and registers it")
    void theMainRoundEmitsTheProvider() throws IOException {
        Path main = compile("main", MAIN_BEAN, tmp.resolve("classes"), null);

        assertTrue(Files.exists(main.resolve("com/acme/app/_VaubanComponents.class")));
        assertTrue(Files.readString(main.resolve(SERVICES)).contains("com.acme.app._VaubanComponents"));
    }

    @Test
    @DisplayName("the test round emits _VaubanComponentsTests, not a second _VaubanComponents")
    void theTestRoundEmitsItsOwnProvider() throws IOException {
        Path main = compile("main", MAIN_BEAN, tmp.resolve("classes"), null);

        Path test = compile("test", TEST_BEAN, tmp.resolve("test-classes"), main);

        assertTrue(Files.exists(test.resolve("com/acme/app/_VaubanComponentsTests.class")),
                "the test round's provider has its own name");
        assertFalse(Files.exists(test.resolve("com/acme/app/_VaubanComponents.class")),
                "a second _VaubanComponents would shadow the main round's");
        Path services = test.resolve(SERVICES);
        assertFalse(Files.exists(services) && Files.readString(services).contains("_VaubanComponents"),
                "the container finds the test provider through the main one, not through a services entry");
    }

    /**
     * When the tests run, the test output patches the module and its {@code META-INF/vauban-beans.list} hides the
     * main one: the main beans vanished from discovery (Vidocq's mansart example lost {@code ProductRepository}).
     * The test round's list therefore carries the main round's beans too.
     */
    @Test
    @DisplayName("the test round's beans list also lists the main round's beans")
    void theTestRoundBeansListCarriesTheMainBeans() throws IOException {
        Path main = compile("main", MAIN_BEAN, tmp.resolve("classes"), null);

        Path test = compile("test", TEST_BEAN, tmp.resolve("test-classes"), main);

        String list = Files.readString(test.resolve("META-INF/vauban-beans.list"));
        assertTrue(list.contains("com.acme.app.TestFixture"), list);
        assertTrue(list.contains("com.acme.app.Greeter"), "the main bean must survive the shadowing: " + list);
        assertTrue(list.lines().filter(l -> l.equals("com.acme.app.Greeter")).count() == 1, list);
    }

    @Test
    @DisplayName("recompiling the main sources over their own stale output still emits _VaubanComponents")
    void aStaleMainOutputIsNotATestRound() throws IOException {
        Path main = compile("main", MAIN_BEAN, tmp.resolve("classes"), null);

        compile("main-again", MAIN_BEAN, main, main);

        assertTrue(Files.exists(main.resolve("com/acme/app/_VaubanComponents.class")));
        assertFalse(Files.exists(main.resolve("com/acme/app/_VaubanComponentsTests.class")),
                "the main provider found in the round's own output is not another round's");
    }

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
}
