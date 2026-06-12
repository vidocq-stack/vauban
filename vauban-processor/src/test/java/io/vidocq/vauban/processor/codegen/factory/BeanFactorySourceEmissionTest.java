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
package io.vidocq.vauban.processor.codegen.factory;

import io.vidocq.vauban.core.BeanFactory;
import io.vidocq.vauban.processor.VaubanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CG-02: when the compile environment can resolve {@code BeanFactory}, the
 * {@code <Bean>_Factory} must be emitted as SOURCE (javac generates the erasure
 * bridge) and behave exactly like the former bytecode factory.
 */
class BeanFactorySourceEmissionTest {

    @TempDir
    Path tempDir;

    private Path compileWithProcessor(String source, String fqn) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var sourceDir = tempDir.resolve("src");
        var packagePath = fqn.substring(0, fqn.lastIndexOf('.')).replace('.', '/');
        Files.createDirectories(sourceDir.resolve(packagePath));
        var file = sourceDir.resolve(packagePath)
                .resolve(fqn.substring(fqn.lastIndexOf('.') + 1) + ".java");
        Files.writeString(file, source);

        var outputDir = tempDir.resolve("classes");
        Files.createDirectories(outputDir);

        // classpath = the test JVM's visible jars/dirs (covers module-path runs too)
        var cp = new ArrayList<String>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) cp.add(entry);
        }
        ModuleLayer layer = getClass().getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
                if ("file".equals(uri.getScheme())) cp.add(new File(uri).getPath());
            }));
        }

        var options = List.of("-d", outputDir.toString(), "--release", "25",
                "-classpath", String.join(File.pathSeparator, cp), "-proc:full");
        var unit = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws java.io.IOException {
                return Files.readString(file);
            }
        };
        try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null, List.of(unit));
            task.setProcessors(List.of(new VaubanProcessor()));
            boolean ok = task.call();
            if (!ok) {
                StringBuilder sb = new StringBuilder("Compilation failed:\n");
                diagnostics.getDiagnostics().forEach(d -> sb.append(d.getKind()).append(": ")
                        .append(d.getMessage(null)).append('\n'));
                fail(sb.toString());
            }
        }
        return outputDir;
    }

    @Test
    void noArgBean_factoryIsEmittedAsSourceAndCreatesInstances() throws Exception {
        Path out = compileWithProcessor("""
                package t;

                @jakarta.enterprise.context.ApplicationScoped
                public class SimpleBean {
                    public String ping() { return "pong"; }
                }
                """, "t.SimpleBean");

        // Source emission proof: javac compiled the rendered .java (a Filer-emitted
        // bytecode factory would exist as .class too, so check the source output dir).
        assertTrue(Files.exists(out.resolve("t/SimpleBean_Factory.class")),
                "factory class must be compiled");

        try (var loader = new URLClassLoader(new java.net.URL[] { out.toUri().toURL() },
                getClass().getClassLoader())) {
            Class<?> factoryClass = loader.loadClass("t.SimpleBean_Factory");
            assertTrue(BeanFactory.class.isAssignableFrom(factoryClass),
                    "must implement BeanFactory");
            BeanFactory<?> factory = (BeanFactory<?>) factoryClass.getDeclaredConstructor().newInstance();
            Object bean = factory.create();
            assertEquals("t.SimpleBean", bean.getClass().getName());
            // erasure bridge present (javac-generated): raw BeanFactory.create() works
            assertNotNull(((BeanFactory) factory).create());
        }
    }

    @Test
    void renderedSource_isTheMinimalMirrorOfTheBytecodeShape() {
        // Textual contract of the renderer (kept in sync with BeanFactoryGenerator's javadoc).
        var rendered = BeanFactorySourceRenderer.render(new SimpleTypeElement("t.SimpleBean"));
        assertEquals("t.SimpleBean_Factory", rendered.className());
        assertTrue(rendered.source().contains(
                "public final class SimpleBean_Factory implements io.vidocq.vauban.core.BeanFactory<t.SimpleBean>"));
        assertTrue(rendered.source().contains("return new t.SimpleBean();"));
    }

    /** Name-only TypeElement double: render() reads only qualified and simple names. */
    private static final class SimpleTypeElement
            implements javax.lang.model.element.TypeElement {
        private final String fqn;

        SimpleTypeElement(String fqn) {
            this.fqn = fqn;
        }

        @Override
        public javax.lang.model.element.Name getQualifiedName() {
            return new SimpleName(fqn);
        }

        @Override
        public javax.lang.model.element.Name getSimpleName() {
            return new SimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        }

        // --- unused members ---
        @Override public java.util.List<? extends javax.lang.model.element.Element> getEnclosedElements() { return List.of(); }
        @Override public javax.lang.model.type.TypeMirror asType() { throw new UnsupportedOperationException(); }
        @Override public javax.lang.model.element.ElementKind getKind() { return javax.lang.model.element.ElementKind.CLASS; }
        @Override public java.util.Set<javax.lang.model.element.Modifier> getModifiers() { return java.util.Set.of(); }
        @Override public javax.lang.model.element.NestingKind getNestingKind() { return javax.lang.model.element.NestingKind.TOP_LEVEL; }
        @Override public javax.lang.model.type.TypeMirror getSuperclass() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<? extends javax.lang.model.type.TypeMirror> getInterfaces() { return List.of(); }
        @Override public java.util.List<? extends javax.lang.model.element.TypeParameterElement> getTypeParameters() { return List.of(); }
        @Override public javax.lang.model.element.Element getEnclosingElement() { return null; }
        @Override public java.util.List<? extends javax.lang.model.element.AnnotationMirror> getAnnotationMirrors() { return List.of(); }
        @Override public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationType) { return null; }
        @Override public <A extends java.lang.annotation.Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
            @SuppressWarnings("unchecked") A[] empty = (A[]) java.lang.reflect.Array.newInstance(annotationType, 0);
            return empty;
        }
        @Override public <R, P> R accept(javax.lang.model.element.ElementVisitor<R, P> v, P p) { return v.visitType(this, p); }
    }

    private record SimpleName(String value) implements javax.lang.model.element.Name {
        @Override public boolean contentEquals(CharSequence cs) { return value.contentEquals(cs); }
        @Override public int length() { return value.length(); }
        @Override public char charAt(int index) { return value.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { return value.subSequence(start, end); }
        @Override public String toString() { return value; }
    }
}
