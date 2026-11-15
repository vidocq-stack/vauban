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

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vauban writes its classes in the round after the companion processors' output, not in javac's last round: a file
 * created in the last round costs one "created in the last round will not be subject to annotation processing"
 * warning, one per generated class at every build (BUG-20261030-26 of vidocq). A bean another processor still
 * generates after that round fails the build by name, since the provider written before it cannot list it.
 */
class GenerationRoundTest {

    private static final String LAST_ROUND = "created in the last round";

    private static final Map<String, String> SOURCES = Map.of("app/Greeter.java", """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Greeter {
                public String hi() { return "hi"; }
            }
            """);

    @TempDir
    Path workDir;

    /** Generates one class per listed round, as Cassini, Cyrano or Grimm do in the first round. */
    @SupportedAnnotationTypes("*")
    static final class Companion extends AbstractProcessor {
        /** Round number to {class name, source}. */
        private final Map<Integer, List<String>> classesByRound;
        private int round;

        Companion(Map<Integer, List<String>> classesByRound) {
            this.classesByRound = classesByRound;
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            List<String> generated = classesByRound.get(++round);
            if (generated != null && !roundEnv.processingOver()) {
                try (var writer = processingEnv.getFiler().createSourceFile(generated.get(0)).openWriter()) {
                    writer.write(generated.get(1));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }
    }

    private static final List<String> PLAIN_COMPANION_CLASS = List.of("app.Greeter$$Companion", """
            package app;

            public final class Greeter$$Companion {
            }
            """);

    private static final List<String> LATE_BEAN = List.of("app.Late", """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Late {
            }
            """);

    @Test
    void theClassesAreWrittenBeforeTheLastRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, SOURCES, List.of(),
                List.of(new Companion(Map.of(1, PLAIN_COMPANION_CLASS)), new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
        assertTrue(Files.exists(result.classes().resolve("app/_VaubanComponents.class")));
        assertTrue(Files.exists(result.classes().resolve("app/Greeter_ClientProxy.class")));
    }

    /** A bean a companion generates in the first round, as Mansart Data does for a repository. */
    private static final List<String> GENERATED_REPOSITORY = List.of("app.GreeterRepositoryImpl", """
            package app;

            @javax.annotation.processing.Generated("test.Companion")
            @jakarta.inject.Singleton
            public class GreeterRepositoryImpl {
                @jakarta.inject.Inject
                Greeter greeter;

                @Override
                public String toString() { return "repository"; }
            }
            """);

    /**
     * Mansart Data's {@code BookRepositoryImpl} reaches Vauban in the second round. It only carries annotations no
     * companion acts on ({@code @Generated}, {@code @Singleton}, {@code @Inject}), so no further class can come from
     * it: Vauban writes in that round, the repository listed, instead of waiting for the last round (VAU-APT-007,
     * the tutorial's finding S4).
     */
    @Test
    void aBeanACompanionGeneratesWithInertAnnotationsIsListedBeforeTheLastRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, SOURCES, List.of(),
                List.of(new Companion(Map.of(1, GENERATED_REPOSITORY)), new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
        assertTrue(Files.readString(result.generated().resolve("app/_VaubanComponents.java"))
                .contains("app.GreeterRepositoryImpl"));
    }

    /** A generated bean carrying an annotation a companion may act on: Vauban waits, as before. */
    @Test
    void aBeanACompanionGeneratesWithAForeignAnnotationIsWaitedFor() throws IOException {
        var result = ProcessorCompiler.compile(workDir, Map.of(
                "app/Greeter.java", SOURCES.get("app/Greeter.java"),
                "app/Tracked.java", """
                        package app;

                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        public @interface Tracked {
                        }
                        """), List.of(),
                List.of(new Companion(Map.of(1, List.of("app.TrackedBean", """
                        package app;

                        @jakarta.enterprise.context.ApplicationScoped
                        @Tracked
                        public class TrackedBean {
                        }
                        """))), new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertTrue(Files.readString(result.generated().resolve("app/_VaubanComponents.java")).contains("app.TrackedBean"));
        // The companion wrote nothing in the second round: Vauban asks javac for a third one (BUG-20261115-06).
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
    }

    /** An extension of the module's build, as vauban-webcontexts' runs at foy-cdi-vauban's. */
    public static final class WebComponentsExtension implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = jakarta.inject.Named.class)
        public void named(ClassConfig type) {
            // Present for its phase only: the shape of the build, not what an extension does in it.
        }
    }

    /**
     * foy-cdi-vauban's shape: normal-scoped producers of interface types, the holders they read, a build compatible
     * extension, and no other processor. Without a companion writing a class in the first round, javac had no second
     * round and Vauban wrote its classes in the last one, one warning each (BUG-20261115-06).
     */
    @Test
    void aModuleWithNoOtherProcessorWritesBeforeTheLastRound() throws IOException {
        var vauban = new VaubanProcessor();
        vauban.overrideBceClasses(List.of(WebComponentsExtension.class));
        var result = ProcessorCompiler.compile(workDir, Map.of("app/ExchangeHolder.java", """
                package app;

                import io.vidocq.vauban.processor.apt.testfixtures.Exchange;

                @jakarta.enterprise.context.RequestScoped
                class ExchangeHolder {
                    private Exchange exchange;

                    void hold(Exchange served) { exchange = served; }

                    Exchange exchange() { return exchange; }
                }
                """, "app/Greeting.java", """
                package app;

                public interface Greeting {
                    String hi();
                }
                """, "app/BuiltInBeans.java", """
                package app;

                import io.vidocq.vauban.processor.apt.testfixtures.Exchange;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.enterprise.context.Dependent;
                import jakarta.enterprise.context.RequestScoped;
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.Typed;

                @Dependent
                class BuiltInBeans {
                    @Produces
                    @RequestScoped
                    @Typed(Exchange.class)
                    Exchange exchange(ExchangeHolder holder) { return holder.exchange(); }

                    @Produces
                    @ApplicationScoped
                    Greeting greeting() { return () -> "hi"; }
                }
                """), List.of(), List.of(vauban));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
        assertTrue(Files.exists(result.classes().resolve("app/_VaubanComponents.class")));
        assertTrue(Files.exists(result.classes().resolve("app/ExchangeHolder_ClientProxy.class")));
        try (var classes = Files.walk(result.classes())) {
            assertEquals(List.of(), classes.map(Path::getFileName).map(Path::toString)
                            .filter(name -> name.startsWith("_VaubanRound")).toList(),
                    "the extra round leaves no class behind");
        }
    }

    /**
     * A module that declares a type of the name the extra round's unit takes: the Filer refuses the unit, and Vauban
     * writes its classes in the last round, as before, the compilation unharmed.
     */
    @Test
    void aRoundUnitTheFilerRefusesFallsBackToTheLastRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, Map.of("app/Greeter.java", SOURCES.get("app/Greeter.java"),
                "app/_VaubanRound1.java", """
                        package app;

                        class _VaubanRound1 {
                        }
                        """), List.of(), List.of(new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertTrue(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
        assertTrue(Files.exists(result.classes().resolve("app/Greeter_ClientProxy.class")));
    }

    /** A module with nothing for Vauban to write leaves javac's rounds as they are. */
    @Test
    void aModuleWithoutBeansAsksForNoExtraRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, Map.of("app/Plain.java", """
                package app;

                public class Plain {
                }
                """), List.of(), List.of(new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        try (var generated = Files.walk(result.generated())) {
            assertEquals(List.of(), generated.filter(Files::isRegularFile).toList());
        }
    }

    /** A bean of the unnamed package: the round's unit is there too, without a package clause. */
    @Test
    void aBeanOfTheUnnamedPackageWritesBeforeTheLastRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, Map.of("Greeter.java", """
                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    public String hi() { return "hi"; }
                }
                """), List.of(), List.of(new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
        assertFalse(Files.readString(result.generated().resolve("_VaubanRound1.java")).contains("package"));
    }

    /**
     * A module with no bean that lists interfaces in {@code @ClientProxies}, an extension's: nothing is indexed, so
     * the round's unit goes in the package of a class the round compiles.
     */
    @Test
    void aModuleListingInterfacesOnlyWritesBeforeTheLastRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, Map.of("app/LedgerExtension.java", """
                package app;

                @io.vidocq.vauban.api.ClientProxies(app.LedgerExtension.Ledger.class)
                public class LedgerExtension {

                    public interface Ledger {
                        String name();
                    }
                }
                """), List.of(), List.of(new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
        assertTrue(Files.exists(result.generated().resolve("app/_VaubanRound1.java")),
                "the unit is in the package of the round's class");
    }

    @Test
    void aBeanAnotherProcessorGeneratesLaterFailsTheBuildByName() throws IOException {
        var result = ProcessorCompiler.compile(workDir, SOURCES, List.of(),
                List.of(new Companion(Map.of(1, PLAIN_COMPANION_CLASS, 2, LATE_BEAN)), new VaubanProcessor()));

        assertFalse(result.success(), String.join("\n", result.messages()));
        assertTrue(result.mentions("app.Late"), String.join("\n", result.messages()));
        assertTrue(result.mentions("-Avauban.generationRound=last"), String.join("\n", result.messages()));
    }

    @Test
    void theLastOptionKeepsTheLastRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, SOURCES, List.of("-Avauban.generationRound=last"),
                List.of(new Companion(Map.of(1, PLAIN_COMPANION_CLASS, 2, LATE_BEAN)), new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertTrue(Files.readString(result.generated().resolve("app/_VaubanComponents.java")).contains("app.Late"));
    }

    /**
     * A bean whose class carries an {@code @Inherited} annotation that is not CDI's: Vauban's subclasses inherit it,
     * and another processor (Dirac's for {@code @Counted}, Heisenberg's for {@code @Retry}) would take them for beans
     * of its own in the next round. Vauban then keeps javac's last round, where no processor sees its classes.
     */
    @Test
    void aBeanWithAForeignInheritedAnnotationKeepsTheLastRound() throws IOException {
        var result = ProcessorCompiler.compile(workDir, Map.of("app/Tracked.java", """
                package app;

                @java.lang.annotation.Inherited
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Tracked {
                }
                """, "app/Greeter.java", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                @Tracked
                public class Greeter {
                    public String hi() { return "hi"; }
                }
                """), List.of(), List.of(new Companion(Map.of(1, PLAIN_COMPANION_CLASS)), new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertTrue(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
    }

    /** JAX-RS's {@code @Produces} is {@code @Inherited}, but starts no processor: a resource keeps the early round. */
    @Test
    void aResourceWithProducesIsWrittenBeforeTheLastRound() throws IOException {
        // JAX-RS's API is not on this module's class path: a stand-in, @Inherited as the real one is.
        var result = ProcessorCompiler.compile(workDir, Map.of("jakarta/ws/rs/Produces.java", """
                package jakarta.ws.rs;

                @java.lang.annotation.Inherited
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Produces {
                    String[] value() default "*/*";
                }
                """, "app/Greeter.java", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                @jakarta.ws.rs.Produces("text/plain")
                public class Greeter {
                    public String hi() { return "hi"; }
                }
                """), List.of(), List.of(new Companion(Map.of(1, PLAIN_COMPANION_CLASS)), new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
    }

    /**
     * The Servlet API's {@code @ServletSecurity} is {@code @Inherited}, but starts no processor: Foy's takes the
     * classes javac hands it by {@code @WebServlet}, {@code @WebFilter}, {@code @WebListener}, none inherited, or by
     * type. A secured servlet, which Foy's build compatible extension makes a {@code @Dependent} bean, keeps the
     * early round (BUG-20261115-06, Vidocq's vidocq-runtime-it-foy-servlet).
     */
    @Test
    void aSecuredServletIsWrittenBeforeTheLastRound() throws IOException {
        // The Servlet API is not on this module's class path: a stand-in, @Inherited as the real one is.
        var result = ProcessorCompiler.compile(workDir, Map.of("jakarta/servlet/annotation/ServletSecurity.java", """
                package jakarta.servlet.annotation;

                @java.lang.annotation.Inherited
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface ServletSecurity {
                }
                """, "app/AccountServlet.java", """
                package app;

                @jakarta.enterprise.context.Dependent
                @jakarta.servlet.annotation.ServletSecurity
                public class AccountServlet {
                    @jakarta.inject.Inject Greeter greeter;
                }
                """, "app/Greeter.java", """
                package app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    public String hi() { return "hi"; }
                }
                """), List.of(), List.of(new VaubanProcessor()));

        assertTrue(result.success(), String.join("\n", result.messages()));
        assertFalse(result.mentions(LAST_ROUND), String.join("\n", result.messages()));
    }
}
