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
}
