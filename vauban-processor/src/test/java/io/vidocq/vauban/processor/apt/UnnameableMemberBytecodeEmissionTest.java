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
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client proxies whose overrides Java source cannot declare — an inherited member's signature names
 * a type the proxy's package cannot name — run through the real processor (BUG-20261004-09): the
 * proxy is emitted as a class file, not as source, and the {@code _VaubanComponents} provider
 * instantiates it through its own lookup, since javac does not read back a class file generated in
 * the last round.
 */
@DisplayName("Proxies Java source cannot express — emitted as bytecode by the processor")
class UnnameableMemberBytecodeEmissionTest {

    @TempDir
    Path tempDir;

    private static final Map<String, String> SOURCES = Map.of(
            "lib/HiddenArg.java", """
                    package lib;
                    class HiddenArg {
                        @Override public String toString() { return "hidden"; }
                    }
                    """,
            "lib/Taker.java", """
                    package lib;
                    public class Taker {
                        public String take(HiddenArg argument) { return "took " + argument + " on " + getClass().getSimpleName(); }
                        public static String callTake(Taker taker) { return taker.take(new HiddenArg()); }
                    }
                    """,
            "lib/Labeled.java", """
                    package lib;
                    public interface Labeled<T> {
                        default String label(T value) { return getClass().getSimpleName() + " " + value; }
                    }
                    """,
            "lib/HiddenDefaultBase.java", """
                    package lib;
                    public class HiddenDefaultBase implements Labeled<HiddenArg> {
                        public static String callLabel(HiddenDefaultBase bean) { return bean.label(new HiddenArg()); }
                    }
                    """,
            "app/TakerProducer.java", """
                    package app;
                    @jakarta.enterprise.context.Dependent
                    public class TakerProducer {
                        @jakarta.enterprise.inject.Produces
                        @jakarta.enterprise.context.ApplicationScoped
                        public lib.Taker taker() { return new lib.Taker(); }
                    }
                    """,
            "app/LabeledService.java", """
                    package app;
                    @jakarta.enterprise.context.ApplicationScoped
                    public class LabeledService extends lib.HiddenDefaultBase {
                        public String own() { return "own"; }
                    }
                    """);

    @Test
    @DisplayName("a producer's proxy over take(HiddenArg) is a class file the provider reaches by name")
    void producerProxy() throws Exception {
        var result = compile();
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        var proxyName = "app.Taker$$" + Integer.toHexString("lib.Taker".hashCode()) + "_ClientProxy";
        var proxyPath = proxyName.replace('.', '/');
        assertFalse(Files.exists(result.genDir().resolve(proxyPath + ".java")), "no source proxy");
        assertTrue(Files.exists(result.outputDir().resolve(proxyPath + ".class")), "a class-file proxy");
        var provider = Files.readString(result.genDir().resolve("app/_VaubanComponents.java"));
        assertTrue(provider.contains("case \"lib.Taker_ClientProxy\" -> {\n                return $$proxy(\""
                + proxyName + "\", delegate);"), provider);
        assertTrue(result.messages().stream().anyMatch(m -> m.contains("Generated " + proxyName + " as bytecode")),
                String.valueOf(result.messages()));

        assertEquals("took hidden on Taker", forwarded(result, proxyName, "lib.Taker", "lib.Taker", "callTake"),
                "forwarded to the contextual instance, not run on the proxy");
    }

    @Test
    @DisplayName("a bean proxy over the default label(HiddenArg) is a class file that forwards it (n3c)")
    void nonShadowedDefault() throws Exception {
        var result = compile();
        assertTrue(result.success(), "compilation should succeed. Messages: " + result.messages());
        assertFalse(Files.exists(result.genDir().resolve("app/LabeledService_ClientProxy.java")), "no source proxy");
        assertTrue(Files.exists(result.outputDir().resolve("app/LabeledService_ClientProxy.class")), "a class-file proxy");
        assertTrue(result.messages().stream().noneMatch(m -> m.contains("does not forward")),
                "nothing left out: " + result.messages());
        assertEquals("LabeledService hidden", forwarded(result, "app.LabeledService_ClientProxy",
                "app.LabeledService", "lib.HiddenDefaultBase", "callLabel"),
                "forwarded to the contextual instance, not run on the proxy");
    }

    /** Calls {@code caller.method(proxy)} on a proxy wired to a fresh instance of {@code bean}. */
    private static String forwarded(CompilationResult result, String proxyName, String bean, String caller,
            String method) throws Exception {
        try (var loader = new URLClassLoader(new URL[] {result.outputDir().toUri().toURL()},
                UnnameableMemberBytecodeEmissionTest.class.getClassLoader())) {
            var contextual = loader.loadClass(bean).getDeclaredConstructor().newInstance();
            var proxyClass = loader.loadClass(proxyName);
            var proxy = proxyClass.getDeclaredConstructor().newInstance();
            proxyClass.getMethod(ClientProxyShape.SET_DELEGATE_METHOD, Supplier.class)
                    .invoke(proxy, (Supplier<Object>) () -> contextual);
            var callerClass = loader.loadClass(caller);
            return (String) callerClass.getMethod(method, callerClass).invoke(null, proxy);
        }
    }

    // ---- in-process compilation through the processor ----

    private CompilationResult compile() throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var files = new ArrayList<Path>();
        for (var entry : SOURCES.entrySet()) {
            var file = tempDir.resolve("src").resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            files.add(file);
        }
        var outputDir = Files.createDirectories(tempDir.resolve("classes"));
        var genDir = Files.createDirectories(tempDir.resolve("gen"));
        var options = List.of("-d", outputDir.toString(), "-s", genDir.toString(), "--release", "25",
                "-classpath", resolveCompilationClasspath(), "-proc:full");
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null,
                    fileManager.getJavaFileObjectsFromPaths(files));
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
