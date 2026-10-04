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
package io.vidocq.vauban.processor.codegen.interceptor;

import io.vidocq.vauban.core.interceptor.MethodShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The interface a rendered subclass lists to reach a shadowed default method must be in a module
 * the bean's module <em>reads</em>, not only one that exports it (JLS 7.7.1): a bean that inherits
 * the interface through a superclass of another module may not read the interface's module, and
 * then cannot name it — "package q is declared in module m.iface, but module m.bean does not read
 * it". Three Java modules are compiled in-process: {@code m.iface} (the interface), {@code m.base}
 * (a class implementing it under a private shadow), {@code m.bean} (the bean).
 */
@DisplayName("Shadowed default owner — the bean's module must read the interface's module")
class DefaultOwnerReadabilityTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the bean's module does not read the interface's module: the method is left out")
    void unreadableInterfaceModule() throws Exception {
        assertEquals(Set.of("own()"), shape("requires m.iface;", "requires m.base;"));
    }

    @Test
    @DisplayName("the bean's module requires the interface's module: reached through the interface")
    void readThroughADirectRequires() throws Exception {
        assertEquals(Set.of("own()", "hidden()@q.Hider"), shape("requires m.iface;", "requires m.base; requires m.iface;"));
    }

    @Test
    @DisplayName("the superclass's module requires the interface's module transitively: reached through the interface")
    void readThroughARequiresTransitive() throws Exception {
        assertEquals(Set.of("own()", "hidden()@q.Hider"), shape("requires transitive m.iface;", "requires m.base;"));
    }

    /** The processor's shape of {@code p.Bean}, with the given {@code requires} in m.base and m.bean. */
    private Set<String> shape(String baseRequires, String beanRequires) throws Exception {
        write("m.iface/module-info.java", "module m.iface { exports q; }");
        write("m.iface/q/Hider.java", "package q; public interface Hider { default String hidden() { return \"default\"; } }");
        write("m.base/module-info.java", "module m.base { " + baseRequires + " exports r; }");
        write("m.base/r/PrivateGrand.java",
                "package r; public class PrivateGrand { @SuppressWarnings(\"unused\") private String hidden() { return \"private\"; } }");
        write("m.base/r/Base.java", "package r; public class Base extends PrivateGrand implements q.Hider {}");
        write("m.bean/module-info.java", "module m.bean { " + beanRequires + " }");
        write("m.bean/p/Bean.java", "package p; public class Bean extends r.Base { public String own() { return \"own\"; } }");

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var captured = new AtomicReference<Set<String>>();
        var out = Files.createDirectories(tmp.resolve("out"));
        try (var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.MODULE_SOURCE_PATH, List.of(tmp));
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(out));
            var units = fm.getJavaFileObjectsFromPaths(sources());
            var task = compiler.getTask(null, fm, diagnostics, List.of("--release", "25", "-proc:only"), null, units);
            task.setProcessors(List.of(new CaptureProcessor(captured)));
            task.call();
        }
        assertNotNull(captured.get(), "the processor captured nothing: " + diagnostics.getDiagnostics().stream()
                .map(d -> d.getKind() + ": " + d.getMessage(null)).collect(Collectors.joining("; ")));
        return captured.get();
    }

    private void write(String relative, String content) throws Exception {
        var file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Path> sources() throws Exception {
        try (var files = Files.walk(tmp)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Captures the shape of {@code p.Bean} once the bean module's elements are available. */
    private static final class CaptureProcessor extends AbstractProcessor {
        private final AtomicReference<Set<String>> sink;

        CaptureProcessor(AtomicReference<Set<String>> sink) {
            this.sink = sink;
        }

        @Override public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            if (sink.get() != null) return false;
            var elements = processingEnv.getElementUtils();
            var module = elements.getModuleElement("m.bean");
            var bean = module == null ? null : elements.getTypeElement(module, "p.Bean");
            if (bean == null) return false;
            var shape = InterceptedShapeFromElements.from(bean, elements, processingEnv.getTypeUtils());
            sink.set(shape.methods().stream().map(DefaultOwnerReadabilityTest::key)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
            return false;
        }
    }

    private static String key(MethodShape m) {
        return m.name() + "(" + m.params().stream().map(Object::toString).collect(Collectors.joining(","))
                + ")" + (m.defaultOwner() != null ? "@" + m.defaultOwner() : "");
    }
}
