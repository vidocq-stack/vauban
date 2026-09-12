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
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The annotation processor emits client proxies as Java <em>source</em>, so that the sibling
 * {@code _VaubanComponents} provider can instantiate them in-module — no reflection, hence no
 * {@code opens}. A nested bean takes that same route: {@code Holder$Counter_ClientProxy} is a
 * legal top-level class name in source ({@code $} is an identifier character), it simply must not
 * be a <em>member</em> of {@code Holder}, since nothing can add one to a class that already exists.
 *
 * <p>What the two names of a nested type cost is bookkeeping, and that is what these tests pin: the
 * binary name {@code app.Holder$Counter} is the key the container looks a component up by, while
 * only the canonical {@code app.Holder.Counter} can be written in a {@code new} expression.
 */
@DisplayName("Nested bean proxying — source proxy, binary key, canonical instantiation")
class NestedBeanProxyEmissionTest {

    @TempDir
    Path tempDir;

    /** A nested normal-scoped bean, next to a top-level one that takes the source route. */
    private static final String HOLDER = """
            package app;

            public class Holder {
                @jakarta.enterprise.context.ApplicationScoped
                public static class Counter {
                    private int hits;
                    public String hit() { hits++; return "hit:" + hits; }
                    public int hits() { return hits; }
                }
            }

            @jakarta.enterprise.context.ApplicationScoped
            class Plain {
                public String v() { return "plain"; }
            }
            """;

    private static final String NESTED_PROXY = "app/Holder$Counter_ClientProxy";

    @Test
    @DisplayName("the nested bean's proxy is emitted as source, like every other proxy")
    void nestedBeanTakesTheSourceRoute() throws Exception {
        var result = compile("Holder", HOLDER);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        assertTrue(Files.exists(result.genDir().resolve(NESTED_PROXY + ".java")),
                "a nested bean's proxy is readable source like any other. Generated: "
                        + emitted(result.genDir()));
        assertTrue(Files.exists(result.outputDir().resolve(NESTED_PROXY + ".class")),
                "and javac compiles it in a later round. Emitted: " + emitted(result.outputDir()));
        assertFalse(Files.exists(result.genDir().resolve("app/Holder/Counter_ClientProxy.java")),
                "the binary name must never be split into a class-as-package directory");

        // The contrast that shows the routing is uniform, not a special case.
        assertTrue(Files.exists(result.genDir().resolve("app/Plain_ClientProxy.java")),
                "a top-level bean takes the same route. Generated: " + emitted(result.genDir()));
    }

    @Test
    @DisplayName("the emitted bytes carry the client-proxy contract")
    void bytecodeProxyCarriesTheContract() throws Exception {
        var result = compile("Holder", HOLDER);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        ClassModel model = ClassFile.of()
                .parse(Files.readAllBytes(result.outputDir().resolve(NESTED_PROXY + ".class")));

        assertEquals("app.Holder$Counter_ClientProxy", binaryName(model.thisClass().asInternalName()),
                "the proxy keeps the bean's binary name, `$` included");
        assertEquals("app.Holder$Counter", binaryName(model.superclass().orElseThrow().asInternalName()),
                "the proxy extends the bean, so an injection point typed on the bean accepts it");

        var fields = model.fields().stream().map(f -> f.fieldName().stringValue()).toList();
        assertTrue(fields.contains(ClientProxyShape.FIELD_DELEGATE),
                "the lazy contextual-instance supplier field must be there. Fields: " + fields);

        var methods = model.methods().stream()
                .map(m -> m.methodName().stringValue() + m.methodType().stringValue())
                .toList();
        assertTrue(methods.contains("<init>()V"),
                "the runtime instantiates the proxy with a no-arg constructor. Methods: " + methods);
        assertTrue(methods.contains(ClientProxyShape.SET_DELEGATE_METHOD + "(Ljava/util/function/Supplier;)V"),
                "the container wires the delegate through $$setDelegate. Methods: " + methods);
        assertTrue(methods.contains("hit()Ljava/lang/String;") && methods.contains("hits()I"),
                "every non-final public method of the bean must be overridden. Methods: " + methods);
    }

    @Test
    @DisplayName("a loaded proxy forwards every call to the contextual instance, and keeps no state")
    void bytecodeProxyForwardsToTheContextualInstance() throws Exception {
        var result = compile("Holder", HOLDER);
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());

        try (var loader = new URLClassLoader(new URL[] {result.outputDir().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> bean = loader.loadClass("app.Holder$Counter");
            Class<?> proxy = loader.loadClass("app.Holder$Counter_ClientProxy");
            assertSame(bean, proxy.getSuperclass(), "the proxy must extend the bean it stands for");

            Object contextual = bean.getDeclaredConstructor().newInstance();
            Object instance = proxy.getDeclaredConstructor().newInstance();
            proxy.getMethod(ClientProxyShape.SET_DELEGATE_METHOD, Supplier.class)
                    .invoke(instance, (Supplier<Object>) () -> contextual);

            var hit = proxy.getMethod("hit");
            assertEquals("hit:1", hit.invoke(instance));
            assertEquals("hit:2", hit.invoke(instance), "calls must land on the same contextual instance");
            assertEquals(2, proxy.getMethod("hits").invoke(instance), "reads are forwarded too");

            var state = bean.getDeclaredField("hits");
            state.setAccessible(true);
            assertEquals(0, state.get(instance),
                    "the proxy's own inherited state must stay untouched — it forwards, it never runs "
                            + "the bean's body itself");
            assertEquals(2, state.get(contextual), "the contextual instance holds the state");
        }
    }

    // ---- minimal in-process compilation harness (with -s for generated sources) ----

    private CompilationResult compile(String simpleName, String source) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var dir = Files.createDirectories(tempDir.resolve("src").resolve("app"));
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

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

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
        for (var clazz : List.of(ApplicationScoped.class, jakarta.inject.Inject.class, VaubanProcessor.class)) {
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
