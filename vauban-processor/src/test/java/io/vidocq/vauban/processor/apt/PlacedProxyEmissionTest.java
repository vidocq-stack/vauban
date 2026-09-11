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
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #42 Stage 4 — for a produced type whose client proxy must live in its own package, the
 * annotation processor cannot emit a source proxy (it would be a split package) and cannot
 * define a class there either. What it can do is emit the <em>co-located</em> proxy as bytecode,
 * shipped as a resource of the bean archive under {@code META-INF/vauban/placed/}, next to the
 * placement manifest that already names the type. The Vauban class loader then defines it into
 * the produced type's package at runtime — no {@code opens}, no agent, no rewritten jar.
 */
@DisplayName("Placed proxy emission — the APT ships in-package proxy bytes as a resource")
class PlacedProxyEmissionTest {

    @TempDir
    Path tempDir;

    private static final String LIB_GADGET = """
            package lib;
            public class Gadget {
                public String run() { return "gadget"; }
                String internalOnly() { return "internal"; }   // package-private: not proxyable across packages
            }
            """;

    private static final String LIB_HANDLE = """
            package lib;
            public class Handle {
                Handle() {}                                      // no accessible constructor
                public static Handle create() { return new Handle(); }
                public String id() { return "handle"; }
            }
            """;

    private static final String LIB_FOO = """
            package lib;
            public class Foo {
                public String greet() { return "hello"; }        // fully public: proxied in the producer's package
            }
            """;

    private static final String APP_PRODUCER = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.enterprise.inject.Produces;
            @ApplicationScoped
            public class Producers {
                @Produces @ApplicationScoped public lib.Gadget gadget() { return new lib.Gadget(); }
                @Produces @ApplicationScoped public lib.Handle handle() { return lib.Handle.create(); }
                @Produces @ApplicationScoped public lib.Foo foo() { return new lib.Foo(); }
            }
            """;

    @Test
    @DisplayName("a type listed in the placement manifest gets its co-located proxy bytes shipped")
    void listedTypesGetPlacedBytes() throws Exception {
        var lib = compileDependency(LIB_GADGET, LIB_HANDLE, LIB_FOO);
        var result = compileApp(lib, APP_PRODUCER);
        assertTrue(result.success(), "the application must compile: " + result.messages());

        var manifest = result.classes().resolve("META-INF/vauban/required-opens.list");
        assertTrue(Files.exists(manifest), "the placement manifest must be written");
        var listed = Files.readString(manifest);
        assertTrue(listed.contains("lib.Gadget") && listed.contains("lib.Handle"),
                "both in-package cases must be listed, got:\n" + listed);
        assertFalse(listed.contains("lib.Foo"), "a fully-public type needs no placement");

        for (var type : List.of("Gadget", "Handle")) {
            var placed = result.classes().resolve("META-INF/vauban/placed/lib/" + type + "_ClientProxy.class");
            assertTrue(Files.exists(placed), "placed bytes must be shipped for lib." + type);
            var model = ClassFile.of().parse(Files.readAllBytes(placed));
            assertEquals("lib." + type + "_ClientProxy", model.thisClass().asInternalName().replace('/', '.'),
                    "the proxy must be named for the produced type's own package");
            assertEquals("lib." + type, model.superclass().orElseThrow().asInternalName().replace('/', '.'),
                    "the placed proxy extends the produced type");
        }
        assertFalse(Files.exists(result.classes().resolve("META-INF/vauban/placed/lib/Foo_ClientProxy.class")),
                "an eligible type is proxied in the producer's package, never placed");
    }

    // ---- Helpers -------------------------------------------------------------------------------

    record CompilationResult(boolean success, List<String> messages, Path classes) {}

    private Path compileDependency(String... sources) throws IOException {
        Path out = tempDir.resolve("lib-classes");
        Files.createDirectories(out);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var options = List.of("-d", out.toString(), "--release", "25",
                "-classpath", compilationClasspath(), "-proc:none");
        try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null,
                    writeSources(tempDir.resolve("lib-src"), sources));
            assertTrue(task.call(), "the dependency must compile: " + diagnostics.getDiagnostics());
        }
        return out;
    }

    private CompilationResult compileApp(Path dependencyClasses, String... sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path out = tempDir.resolve("app-classes");
        Files.createDirectories(out);
        var options = List.of("-d", out.toString(), "--release", "25",
                "-classpath", dependencyClasses + File.pathSeparator + compilationClasspath(),
                "-proc:full");
        try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null,
                    writeSources(tempDir.resolve("app-src"), sources));
            task.setProcessors(List.of(new VaubanProcessor()));
            boolean success = task.call();
            var messages = new ArrayList<String>();
            for (var d : diagnostics.getDiagnostics()) {
                messages.add(d.getKind() + ": " + d.getMessage(null));
            }
            return new CompilationResult(success, messages, out);
        }
    }

    private List<JavaFileObject> writeSources(Path root, String... sources) throws IOException {
        var files = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            var pkg = group(Pattern.compile("package\\s+([\\w.]+)\\s*;"), source);
            var name = group(Pattern.compile("(?:class|interface|record|enum)\\s+(\\w+)"), source);
            Path dir = pkg == null ? root : root.resolve(pkg.replace('.', '/'));
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".java");
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

    /**
     * Under surefire the dependencies sit on the module path, not on {@code java.class.path}:
     * hand javac the class path plus every {@code file:} location of the boot layer.
     */
    private static String compilationClasspath() {
        var paths = new java.util.LinkedHashSet<String>();
        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            java.util.Collections.addAll(paths, cp.split(File.pathSeparator));
        }
        ModuleLayer.boot().configuration().modules().forEach(resolved ->
                resolved.reference().location()
                        .filter(uri -> "file".equals(uri.getScheme()))
                        .ifPresent(uri -> paths.add(Path.of(uri).toString())));
        return String.join(File.pathSeparator, paths);
    }

    private static String group(Pattern pattern, String source) {
        var m = pattern.matcher(source);
        return m.find() ? m.group(1) : null;
    }
}
