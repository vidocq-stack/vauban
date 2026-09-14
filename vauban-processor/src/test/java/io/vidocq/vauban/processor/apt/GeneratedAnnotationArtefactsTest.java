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
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module compiled with the processor ships what its qualifier types declare, a reader that calls
 * their members directly and a literal for each one, so the container never reads an annotation of
 * that module (vauban#70). This compiles a module, loads its generated provider in a class loader of
 * its own, and asks it the three questions the container asks.
 *
 * <p>The reference on the other side is what the JDK builds from the same declaration.
 */
@DisplayName("vauban#70: the processor generates the metadata, the reader and the literal of a qualifier")
class GeneratedAnnotationArtefactsTest {

    private static final String SOURCE = """
            package app;

            import jakarta.enterprise.context.Dependent;
            import jakarta.enterprise.util.Nonbinding;
            import jakarta.inject.Inject;
            import jakarta.inject.Qualifier;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            enum Hue { LOW, HIGH }

            @Retention(RetentionPolicy.RUNTIME)
            @interface Inner {
                String value() default "inner";
            }

            @Qualifier
            @Retention(RetentionPolicy.RUNTIME)
            @interface Channel {
                String value() default "card";

                @Nonbinding String note() default "";

                Hue hue() default Hue.HIGH;

                Class<?> kind() default int.class;

                int[] codes() default {1, 2};

                Inner inner() default @Inner("nested");

                long big() default 4L;

                boolean flag() default true;
            }

            // No @Retention: the runtime never sees it, so the module has nothing to say about it.
            @interface Tag {
                String value() default "none";
            }

            @Channel @Tag("card") @Dependent class CardPayment {
            }

            @Channel(value = "wire", note = "written", hue = Hue.LOW) @Dependent class WirePayment {
            }

            /** Qualified by a PUBLIC qualifier of a dependency, compiled without this processor. */
            @jakarta.enterprise.context.Initialized(jakarta.enterprise.context.ApplicationScoped.class)
            @Dependent class Boot {
            }

            @Dependent
            public class Checkout {
                // The same binding values as the bean; the @Nonbinding note may differ.
                @Inject @Channel(value = "wire", hue = Hue.LOW, note = "point") public WirePayment payment;
            }
            """;

    @TempDir
    static Path tempDir;

    private static ClassLoader module;
    private static VaubanComponentProvider provider;

    @BeforeAll
    static void compileAndLoad() throws Exception {
        var classes = compile();
        module = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                GeneratedAnnotationArtefactsTest.class.getClassLoader());
        var providerClass = module.loadClass("app._VaubanComponents");
        provider = (VaubanComponentProvider) providerClass.getDeclaredConstructor().newInstance();
    }

    /** The annotation the JDK builds for {@code carrier}, read through the compiled module's loader. */
    private static Annotation jdk(String carrier) throws ClassNotFoundException {
        var annotationType = module.loadClass("app.Channel").asSubclass(Annotation.class);
        return module.loadClass(carrier).getAnnotation(annotationType);
    }

    private static Object member(Annotation annotation, String member) throws Exception {
        // The fixture's qualifier is package-private, as a module's own qualifier often is.
        var accessor = annotation.annotationType().getMethod(member);
        accessor.setAccessible(true);
        return accessor.invoke(annotation);
    }

    @Test
    @DisplayName("the metadata says what the type declares: members, their types, their defaults, @Nonbinding")
    void metadata() throws Exception {
        var metadata = provider.annotationMetadata("app.Channel");

        assertNotNull(metadata, "the module declares @Channel, so its provider owns it");
        assertEquals(List.of("value", "note", "hue", "kind", "codes", "inner", "big", "flag"), metadata.members());
        assertEquals(Set.of("note"), metadata.nonbinding());
        assertEquals(String.class, metadata.types().get("value"));
        assertEquals(module.loadClass("app.Hue"), metadata.types().get("hue"));
        assertEquals(Class.class, metadata.types().get("kind"));
        assertEquals(int[].class, metadata.types().get("codes"));
        assertEquals(module.loadClass("app.Inner"), metadata.types().get("inner"));

        var defaults = metadata.defaults();
        assertEquals("card", defaults.get("value"));
        assertEquals("", defaults.get("note"));
        assertEquals(member(jdk("app.CardPayment"), "hue"), defaults.get("hue"), "the enum constant itself");
        assertEquals(int.class, defaults.get("kind"), "a primitive class literal");
        assertArrayEquals(new int[] {1, 2}, (int[]) defaults.get("codes"));
        assertEquals(member(jdk("app.CardPayment"), "inner"), defaults.get("inner"), "the nested annotation");
        assertEquals(4L, defaults.get("big"));
        assertEquals(true, defaults.get("flag"));
    }

    @Test
    @DisplayName("the reader gives the member values of an instance, every member of it")
    void reader() throws Exception {
        var written = jdk("app.WirePayment");

        var values = provider.readAnnotation(written);

        assertNotNull(values, "the module owns the type of that instance");
        assertEquals("wire", values.get("value"));
        assertEquals("written", values.get("note"));
        assertEquals(member(written, "hue"), values.get("hue"));
        assertEquals(member(written, "kind"), values.get("kind"), "a member left at its default");
        assertArrayEquals(new int[] {1, 2}, (int[]) values.get("codes"));
        assertEquals(member(written, "inner"), values.get("inner"));
    }

    @Test
    @DisplayName("the literal equals the instance the JDK builds from the same values, both ways")
    void literal() throws Exception {
        var literal = provider.annotationLiteral("app.Channel",
                Map.of("value", "wire", "note", "written", "hue", member(jdk("app.WirePayment"), "hue")));

        assertNotNull(literal, "the module generated a literal for its own qualifier");
        assertEquals(jdk("app.WirePayment"), literal, "the JDK instance must accept it");
        assertEquals(literal, jdk("app.WirePayment"), "it must accept the JDK instance");
        assertEquals(jdk("app.WirePayment").hashCode(), literal.hashCode());
        assertEquals("wire", member(literal, "value"));
        assertArrayEquals(new int[] {1, 2}, (int[]) member(literal, "codes"), "a member left at its default");
    }

    @Test
    @DisplayName("a literal built with no member is the one the declaration defaults to")
    void literalOfDefaults() throws Exception {
        var literal = provider.annotationLiteral("app.Channel", Map.of());

        assertEquals(jdk("app.CardPayment"), literal);
        assertEquals(literal, jdk("app.CardPayment"));
        assertEquals("@app.Channel(big=4, codes={1, 2}, flag=true, hue=HIGH, "
                        + "inner=@app.Inner(value=\"nested\"), kind=int.class, note=\"\", value=\"card\")",
                literal.toString(),
                "rendered as the container renders an instance it builds itself");
    }

    @Test
    @DisplayName("a type the runtime never sees is not the container's business")
    void classRetainedTypesAreLeftOut() {
        assertNull(provider.annotationMetadata("app.Tag"));
        assertNull(provider.annotationLiteral("app.Tag", Map.of()));
    }

    @Test
    @DisplayName("a type the module neither declares nor uses is not its provider's to answer for")
    void unknownType() {
        assertNull(provider.annotationMetadata("jakarta.inject.Named"));
        assertNull(provider.annotationLiteral("jakarta.inject.Named", Map.of()));
        assertNull(provider.readAnnotation(jakarta.enterprise.inject.Any.Literal.INSTANCE));
    }

    /**
     * A qualifier from a dependency built without this processor: nothing ships its artefacts, so
     * without this the container would have to build a {@code reflect.Proxy} for it — which
     * {@code forbid} refuses. The type is public, so the module that <em>uses</em> it can carry a
     * literal for it (vauban#88).
     */
    @Test
    @DisplayName("a public qualifier of a dependency gets a reader and a literal in the module that uses it")
    void aDependencyQualifierIsCoveredByItsConsumer() throws Exception {
        var declared = jdkInitialized();

        var literal = provider.annotationLiteral("jakarta.enterprise.context.Initialized",
                Map.of("value", jakarta.enterprise.context.ApplicationScoped.class));

        assertNotNull(literal, "the module uses it and its own module ships nothing for it");
        assertEquals(declared, literal, "equal to the instance the JDK builds from that declaration");
        assertEquals(literal, declared, "and accepted by it");
        assertEquals(declared.hashCode(), literal.hashCode());

        var values = provider.readAnnotation(declared);
        assertNotNull(values, "reading it must not need reflection either");
        assertEquals(jakarta.enterprise.context.ApplicationScoped.class, values.get("value"));

        // Only what the container needs instances of. @Dependent is on every bean of the fixture and
        // is not a qualifier, so carrying a literal for it would be pure weight.
        assertNull(provider.annotationLiteral("jakarta.enterprise.context.Dependent", Map.of()),
                "a dependency's annotation that is not a qualifier must not be rendered");
    }

    /** The {@code @Initialized} the fixture's bean carries, read through the compiled module. */
    private static java.lang.annotation.Annotation jdkInitialized() throws ClassNotFoundException {
        return module.loadClass("app.Boot")
                .getAnnotation(jakarta.enterprise.context.Initialized.class);
    }

    // ---- compilation harness ----

    private static Path compile() throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = Files.createDirectories(tempDir.resolve("src/app")).resolve("Checkout.java");
        Files.writeString(file, SOURCE);
        var source = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return Files.readString(file);
            }
        };
        var classes = Files.createDirectories(tempDir.resolve("classes"));
        var options = List.of(
                "-d", classes.toString(),
                "-s", Files.createDirectories(tempDir.resolve("gen")).toString(),
                "--release", "25",
                "-classpath", compilationClasspath(),
                "-proc:full");

        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null, List.of(source));
            task.setProcessors(List.of(new VaubanProcessor()));
            var messages = new ArrayList<String>();
            var success = task.call();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                messages.add(diagnostic.getKind() + ": " + diagnostic.getMessage(null));
            }
            assertTrue(success, "the fixture must compile: " + messages);
        }
        return classes;
    }

    private static String compilationClasspath() {
        var paths = new LinkedHashSet<String>();
        var classpath = System.getProperty("java.class.path");
        if (classpath != null && !classpath.isBlank()) {
            paths.addAll(List.of(classpath.split(File.pathSeparator)));
        }
        for (var type : List.of(Dependent.class, jakarta.inject.Inject.class, VaubanProcessor.class,
                VaubanComponentProvider.class)) {
            try {
                var location = type.getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    paths.add(Path.of(location.toURI()).toString());
                }
            } catch (Exception ignored) {
                // best-effort classpath assembly
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(module ->
                module.reference().location().ifPresent(uri -> {
                    if ("file".equals(uri.getScheme())) {
                        paths.add(Path.of(uri).toString());
                    }
                }));
        return String.join(File.pathSeparator, paths);
    }
}
