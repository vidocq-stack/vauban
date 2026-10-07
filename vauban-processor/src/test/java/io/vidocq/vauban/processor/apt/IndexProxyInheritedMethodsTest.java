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

import io.vidocq.vauban.core.proxy.ClientProxyShape;
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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor used to build a normal-scoped bean's client proxy from its index — overriding the
 * methods the bean class declares and no other — in two cases: a bean it did not find by name (a
 * top-level class whose own name contains {@code $}), and a bean with private constructors only.
 * A method the bean inherits from a superclass, or an interface default method, then ran on the
 * proxy instance instead of the contextual instance (BUG-20261004-11).
 *
 * <p>Each method of the fixtures returns the name of the class it runs on: the bean's on the
 * contextual instance, the proxy's on the proxy.
 */
@DisplayName("Client proxy of a bean the processor used to proxy from its index — inherited methods")
class IndexProxyInheritedMethodsTest {

    @TempDir
    Path tempDir;

    private static final String BASE = """
            package app;

            public class SelfNamingBase {
                public String inheritedSelf() { return getClass().getName(); }
            }
            """;

    private static final String INTERFACE = """
            package app;

            public interface SelfNaming {
                default String defaultSelf() { return getClass().getName(); }
            }
            """;

    private static final String DOLLAR_BEAN = """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Dollar$Service extends SelfNamingBase implements SelfNaming {
                public String own() { return getClass().getName(); }
            }
            """;

    private static final String PRIVATE_CTOR_BEAN = """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class PrivateCtorService extends SelfNamingBase implements SelfNaming {
                private PrivateCtorService() {}
                public String own() { return getClass().getName(); }
            }
            """;

    @Test
    @DisplayName("a bean whose name contains '$' gets a source proxy, like any other top-level bean")
    void dollarBeanTakesTheSourceRoute() throws Exception {
        var result = compile(List.of("SelfNamingBase", "SelfNaming", "Dollar$Service"),
                List.of(BASE, INTERFACE, DOLLAR_BEAN));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertTrue(Files.exists(result.genDir().resolve("app/Dollar$Service_ClientProxy.java")),
                "the processor finds the bean by its binary name and renders its proxy as source. Generated: "
                        + emitted(result.genDir()));
    }

    @Test
    @DisplayName("a bean whose name contains '$': inherited methods reach the contextual instance")
    void dollarBeanForwardsInheritedMethods() throws Exception {
        var result = compile(List.of("SelfNamingBase", "SelfNaming", "Dollar$Service"),
                List.of(BASE, INTERFACE, DOLLAR_BEAN));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertForwardsInheritedMethods(result, "app.Dollar$Service");
    }

    @Test
    @DisplayName("a bean with private constructors only: inherited methods reach the contextual instance")
    void privateConstructorBeanForwardsInheritedMethods() throws Exception {
        var result = compile(List.of("SelfNamingBase", "SelfNaming", "PrivateCtorService"),
                List.of(BASE, INTERFACE, PRIVATE_CTOR_BEAN));
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertForwardsInheritedMethods(result, "app.PrivateCtorService");
    }

    /**
     * Loads the bean and its proxy, wires a contextual instance into the proxy, and calls the
     * declared, the inherited and the default method through it.
     */
    private void assertForwardsInheritedMethods(CompilationResult result, String beanName) throws Exception {
        try (var loader = new URLClassLoader(new URL[] {result.outputDir().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> bean = loader.loadClass(beanName);
            Class<?> proxy = loader.loadClass(beanName + ClientProxyShape.PROXY_SUFFIX);

            var beanCtor = bean.getDeclaredConstructor();
            beanCtor.setAccessible(true);
            Object contextual = beanCtor.newInstance();
            var proxyCtor = proxy.getDeclaredConstructor();
            proxyCtor.setAccessible(true);
            Object instance = proxyCtor.newInstance();
            proxy.getMethod(ClientProxyShape.SET_DELEGATE_METHOD, Supplier.class)
                    .invoke(instance, (Supplier<Object>) () -> contextual);

            assertEquals(beanName, proxy.getMethod("own").invoke(instance),
                    "a declared method runs on the contextual instance");
            assertEquals(beanName, proxy.getMethod("inheritedSelf").invoke(instance),
                    "a superclass method runs on the contextual instance, not on the proxy");
            assertEquals(beanName, proxy.getMethod("defaultSelf").invoke(instance),
                    "an interface default method runs on the contextual instance, not on the proxy");
        }
    }

    // ---- minimal in-process compilation harness (with -s for generated sources) ----

    private CompilationResult compile(List<String> simpleNames, List<String> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var dir = Files.createDirectories(tempDir.resolve("src").resolve("app"));
        var files = new ArrayList<JavaFileObject>();
        for (int i = 0; i < sources.size(); i++) {
            var file = dir.resolve(simpleNames.get(i) + ".java");
            Files.writeString(file, sources.get(i));
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
                messages.add(d.getKind() + ": " + d.getMessage(null));
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
