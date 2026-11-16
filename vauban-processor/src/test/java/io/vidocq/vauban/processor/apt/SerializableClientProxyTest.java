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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client proxies the processor renders as source are {@code Serializable} and write their delegate in their
 * place, so a session value that holds one can move to another container (foy#114). The application compiles with
 * {@code -Xlint:serial} and no serial warning on a proxy, and a bean or an interface that declares its own
 * {@code writeReplace} or {@code readResolve} gets a proxy without a duplicate method.
 */
@DisplayName("Source client proxies are Serializable")
class SerializableClientProxyTest {

    private static final String LEDGER = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class Ledger {
                public int total() { return 1; }
            }
            """;

    private static final String OWN_HOOKS = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class OwnHooks implements java.io.Serializable {
                private static final long serialVersionUID = 1L;
                public String name() { return "own"; }
                protected Object writeReplace() throws java.io.ObjectStreamException { return this; }
                protected Object readResolve() throws java.io.ObjectStreamException { return this; }
            }
            """;

    private static final String PUBLIC_HOOK = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class PublicHook {
                public String name() { return "public"; }
                public Object writeReplace() { return this; }
            }
            """;

    private static final String HOOKED = """
            package app;
            public interface Hooked {
                default Object writeReplace() { return this; }
            }
            """;

    private static final String DEFAULT_HOOKED = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class DefaultHooked implements Hooked {
                public String name() { return "hooked"; }
            }
            """;

    private static final String GREETER = """
            package app;
            public interface Greeter {
                String greet();
            }
            """;

    private static final String REPLACEABLE = """
            package app;
            public interface Replaceable {
                String id();
                Object writeReplace();
                Object readResolve();
            }
            """;

    private static final String PRODUCERS = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Produces;
            @Dependent
            public class Producers {
                @Produces @ApplicationScoped
                public Greeter greeter() { return () -> "hi"; }
                @Produces @ApplicationScoped
                public Replaceable replaceable() {
                    return new Replaceable() {
                        public String id() { return "r"; }
                        public Object writeReplace() { return this; }
                        public Object readResolve() { return this; }
                    };
                }
            }
            """;

    private static final String STATIC_HOOK = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class StaticHook {
                public String name() { return "static"; }
                public static Object writeReplace() { return "static replacement"; }
            }
            """;

    private static final String TYPED_HOOK = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class TypedHook {
                public String writeReplace() { return "a business method"; }
            }
            """;

    private static final String LABELLED = """
            package app;
            public interface Labelled {
                default String writeReplace() { return "a label"; }
            }
            """;

    private static final String BADGE = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            @ApplicationScoped
            public class Badge implements Labelled {
                public String name() { return "badge"; }
            }
            """;

    private static final String GADGET = """
            package lib;
            public class Gadget {
                public Gadget() { }
                public String name() { return "gadget"; }
                public static Object writeReplace() { return "static replacement"; }
            }
            """;

    private static final String GADGET_PRODUCER = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Produces;
            @Dependent
            public class GadgetProducer {
                @Produces @ApplicationScoped
                public lib.Gadget gadget() { return new lib.Gadget(); }
            }
            """;

    private static final String TICKET = """
            package app;
            public interface Ticket {
                String id();
                String writeReplace();
            }
            """;

    private static final String TICKET_PRODUCER = """
            package app;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.inject.Produces;
            @Dependent
            public class TicketProducer {
                @Produces @ApplicationScoped
                public Ticket ticket() {
                    return new Ticket() {
                        public String id() { return "t"; }
                        public String writeReplace() { return "t"; }
                    };
                }
            }
            """;

    @TempDir
    static Path tempDir;

    private static Path out;
    private static List<Diagnostic<? extends JavaFileObject>> diagnostics;

    @BeforeAll
    static void compile() throws IOException {
        var compilation = compile("app", LEDGER, OWN_HOOKS, PUBLIC_HOOK, HOOKED, DEFAULT_HOOKED, GREETER, REPLACEABLE,
                PRODUCERS);
        assertTrue(compilation.success(), "the application must compile: " + compilation.diagnostics());
        out = compilation.out();
        diagnostics = compilation.diagnostics();
    }

    @Test
    @DisplayName("no serial warning on a generated proxy")
    void noSerialWarningOnAProxy() {
        var onProxies = diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .filter(d -> d.getSource() != null && d.getSource().getName().endsWith("_ClientProxy.java"))
                .map(d -> d.getSource().getName() + ": " + d.getMessage(null))
                .toList();

        assertEquals(List.of(), onProxies);
    }

    @Test
    @DisplayName("a bean's proxy is Serializable and has a private writeReplace")
    void aBeanProxyIsSerializable() throws IOException {
        var source = generated("app.Ledger" + ClientProxyShape.PROXY_SUFFIX);

        assertTrue(source.contains("extends app.Ledger implements java.io.Serializable {"), source);
        assertTrue(source.contains("private static final long serialVersionUID = 1L;"), source);
        assertTrue(source.contains(
                "private java.lang.Object writeReplace() throws java.io.ObjectStreamException {"), source);
    }

    @Test
    @DisplayName("a bean's own protected writeReplace and readResolve are not forwarded; the proxy's own overrides")
    void aBeanWithItsOwnProtectedHooks() throws IOException {
        var source = generated("app.OwnHooks" + ClientProxyShape.PROXY_SUFFIX);

        assertEquals(1, occurrences(source, "writeReplace("), source);
        assertEquals(0, occurrences(source, "readResolve("), source);
        assertTrue(source.contains("public java.lang.Object writeReplace() {\n        return this.$$delegate;"),
                source);
    }

    @Test
    @DisplayName("a bean's own public writeReplace is overridden by the proxy's own")
    void aBeanWithItsOwnPublicHook() throws IOException {
        var source = generated("app.PublicHook" + ClientProxyShape.PROXY_SUFFIX);

        assertEquals(1, occurrences(source, "writeReplace("), source);
        assertTrue(source.contains("public java.lang.Object writeReplace() {\n        return this.$$delegate;"),
                source);
    }

    @Test
    @DisplayName("a writeReplace a bean inherits from an interface is overridden by the proxy's own, public")
    void aBeanWithAnInterfaceHook() throws IOException {
        var source = generated("app.DefaultHooked" + ClientProxyShape.PROXY_SUFFIX);

        assertEquals(1, occurrences(source, "writeReplace("), source);
        assertTrue(source.contains("public java.lang.Object writeReplace() {\n        return this.$$delegate;"),
                source);
    }

    @Test
    @DisplayName("an interface proxy is Serializable and has a private writeReplace")
    void anInterfaceProxyIsSerializable() throws IOException {
        var source = generated(ClientProxyShape.producerProxyName("app", "app.Greeter"));

        assertTrue(source.contains("implements app.Greeter, java.io.Serializable {"), source);
        assertTrue(source.contains("private static final long serialVersionUID = 1L;"), source);
        assertTrue(source.contains(
                "private java.lang.Object writeReplace() throws java.io.ObjectStreamException {"), source);
    }

    @Test
    @DisplayName("an interface that declares writeReplace gets the proxy's own, public; its readResolve is forwarded")
    void anInterfaceWithItsOwnHooks() throws IOException {
        var source = generated(ClientProxyShape.producerProxyName("app", "app.Replaceable"));

        assertEquals(1, occurrences(source, "writeReplace("), source);
        assertTrue(source.contains("public java.lang.Object writeReplace() {\n        return this.$$delegate;"),
                source);
        assertTrue(source.contains("return $$delegate.get().readResolve();"), source);
    }

    @Test
    @DisplayName("a bean with a static writeReplace gets a bytecode proxy: no source method can sit next to it")
    void aStaticWriteReplaceSendsTheProxyToBytecode() throws IOException {
        var compilation = compile("static-hook", STATIC_HOOK);

        assertTrue(compilation.success(), String.valueOf(compilation.diagnostics()));
        assertBytecodeProxy(compilation, "app.StaticHook" + ClientProxyShape.PROXY_SUFFIX,
                "static java.lang.Object writeReplace()");
    }

    @Test
    @DisplayName("a bean whose writeReplace returns another type gets a bytecode proxy, and a warning: it is not forwarded")
    void aWriteReplaceOfAnotherTypeSendsTheProxyToBytecode() throws IOException {
        var compilation = compile("typed-hook", TYPED_HOOK, LABELLED, BADGE);

        assertTrue(compilation.success(), String.valueOf(compilation.diagnostics()));
        for (var bean : List.of("app.TypedHook", "app.Badge")) {
            var model = assertBytecodeProxy(compilation, bean + ClientProxyShape.PROXY_SUFFIX,
                    "java.lang.String writeReplace()");
            var descriptors = model.methods().stream()
                    .filter(m -> m.methodName().equalsString("writeReplace"))
                    .map(m -> m.methodType().stringValue()).toList();
            assertEquals(List.of("()Ljava/lang/Object;"), descriptors,
                    bean + ": its own hook only, which a forwarded String writeReplace() would hide");
            var warnings = compilation.diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.WARNING).map(d -> d.getMessage(null))
                    .filter(m -> m.startsWith("[Vauban] " + bean + ": the client proxy does not forward"
                            + " java.lang.String writeReplace()"))
                    .toList();
            assertEquals(1, warnings.size(), String.valueOf(compilation.diagnostics()));
        }
    }

    @Test
    @DisplayName("a producer's proxy of a class with a static writeReplace is bytecode too")
    void aProducedClassWithAStaticWriteReplaceSendsTheProxyToBytecode() throws IOException {
        var compilation = compile("produced-static-hook", GADGET, GADGET_PRODUCER);

        assertTrue(compilation.success(), String.valueOf(compilation.diagnostics()));
        assertBytecodeProxy(compilation, ClientProxyShape.producerProxyName("app", "lib.Gadget"),
                "static java.lang.Object writeReplace()");
    }

    @Test
    @DisplayName("an interface whose writeReplace returns another type has no proxy, and the build says why")
    void anInterfaceWriteReplaceOfAnotherTypeIsAnError() throws IOException {
        var compilation = compile("interface-typed-hook", TICKET, TICKET_PRODUCER);

        assertFalse(compilation.success(), "the build fails");
        var errors = compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).map(d -> d.getMessage(null)).toList();
        assertEquals(List.of("[Vauban] No client proxy can implement app.Ticket: it declares java.lang.String"
                + " writeReplace(), and a client proxy declares its own java.lang.Object writeReplace(), so that a"
                + " session holding it can move to another container (foy#114). Return java.lang.Object from"
                + " writeReplace(), or rename the method."), errors);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** What a compilation gave: whether it succeeded, its class output, and every diagnostic. */
    private record Compilation(boolean success, Path out, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}

    /** Compiles {@code sources} with the Vauban processor and {@code -Xlint:serial}, in a directory of its own. */
    private static Compilation compile(String name, String... sources) throws IOException {
        var classes = tempDir.resolve(name + "-classes");
        Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        var options = List.of("-d", classes.toString(), "--release", "25", "-Xlint:serial",
                "-classpath", compilationClasspath(), "-proc:full");
        try (var fm = compiler.getStandardFileManager(collector, null, null)) {
            var task = compiler.getTask(null, fm, collector, options, null,
                    writeSources(tempDir.resolve(name + "-src"), sources));
            task.setProcessors(List.of(new VaubanProcessor()));
            boolean success = task.call();
            return new Compilation(success, classes, collector.getDiagnostics());
        }
    }

    /**
     * Asserts that {@code proxy} was emitted as bytecode, with no source, that it is Serializable with its own
     * {@code Object writeReplace()}, and that the build said why, naming {@code clash}.
     */
    private static ClassModel assertBytecodeProxy(Compilation compilation, String proxy, String clash)
            throws IOException {
        var path = proxy.replace('.', '/');
        assertFalse(Files.exists(compilation.out().resolve(path + ".java")), proxy + " is not rendered as source");
        var model = ClassFile.of().parse(compilation.out().resolve(path + ".class"));
        assertTrue(model.interfaces().stream().anyMatch(i -> i.asInternalName().equals("java/io/Serializable")),
                proxy + " is Serializable");
        assertTrue(model.methods().stream().anyMatch(m -> m.methodName().equalsString("writeReplace")
                && m.methodType().equalsString("()Ljava/lang/Object;")), proxy + " has its own writeReplace()");
        var notes = compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE).map(d -> d.getMessage(null))
                .filter(m -> m.startsWith("[Vauban] Generated " + proxy + " as bytecode")).toList();
        assertEquals(1, notes.size(), String.valueOf(compilation.diagnostics()));
        assertTrue(notes.getFirst().contains(clash), notes.getFirst());
        return model;
    }

    private static String generated(String binaryName) throws IOException {
        return Files.readString(out.resolve(binaryName.replace('.', '/') + ".java"));
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }

    private static List<JavaFileObject> writeSources(Path root, String... sources) throws IOException {
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
