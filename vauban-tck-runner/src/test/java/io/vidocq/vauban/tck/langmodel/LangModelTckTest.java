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
package io.vidocq.vauban.tck.langmodel;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.jboss.cdi.lang.model.tck.LangModelVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The CDI language model TCK ({@code cdi-tck-lang-model}), which the core TCK never calls: its
 * {@code LangModelVerifier} runs from a build compatible extension at start, in Vauban's annotation processor and in
 * a deployment frozen at build, with the type annotation checks on. Each check is a test, named after the step of
 * {@code LangModelVerifier.verify} it runs; a check {@link KnownLangModelGaps} lists is skipped, naming its bug.
 */
@DisplayName("CDI language model TCK")
class LangModelTckTest {

    @TempDir
    Path workDir;

    @TestFactory
    @DisplayName("at start")
    Stream<DynamicTest> atStart() {
        VerifyingExtension.reset();
        try (var _ = SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(VerifyingExtension.class).initialize()) {
            return checks(Place.START, VerifyingExtension.outcomes());
        }
    }

    @TestFactory
    @DisplayName("in the processor")
    Stream<DynamicTest> inTheProcessor() throws IOException {
        VerifyingExtension.reset();
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(VerifyingExtension.class));
        var source = Files.createDirectories(workDir.resolve("src/app")).resolve("Bean.java");
        Files.writeString(source, """
                package app;

                @jakarta.enterprise.context.Dependent
                public class Bean {
                }
                """);
        var classes = Files.createDirectories(workDir.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("-d", classes.toString(),
                    "-classpath", classPath(), "-proc:full"), null, files.getJavaFileObjects(source));
            task.setProcessors(List.of(processor));
            assertTrue(task.call(), () -> "the compilation failed: " + diagnostics.getDiagnostics());
        }
        return checks(Place.PROCESSOR, VerifyingExtension.outcomes());
    }

    @TestFactory
    @DisplayName("frozen at build")
    Stream<DynamicTest> frozenAtBuild() throws IOException {
        var output = Files.createDirectories(workDir.resolve("classes"));
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
            VerifyingExtension.reset();
            var frozen = deployment(loader).freezeExtensions(output);
            assertTrue(frozen.frozen(), frozen.reason());
            var outcomes = VerifyingExtension.outcomes();

            VerifyingExtension.reset();
            try (var container = deployment(loader).build()) {
                assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
                assertEquals(List.of(), container.extensionsAtStart().ranAtStart());
            }
            assertNull(VerifyingExtension.outcomes(), "the start of a frozen deployment verified again");
            return checks(Place.FROZEN, outcomes);
        }
    }

    private static VaubanContainerBuilder deployment(ClassLoader loader) {
        return VaubanContainer.builder().classLoader(loader).addBeanClass(VerifyingExtension.class);
    }

    /** A test per check, as {@code outcomes} of the verification in {@code place} found it. */
    private static Stream<DynamicTest> checks(Place place, List<VerifierSteps.Outcome> outcomes) {
        assertNotNull(outcomes, () -> "the extension did not verify LangModelVerifier " + place);
        return outcomes.stream().map(outcome -> DynamicTest.dynamicTest(outcome.check(),
                () -> verdict(place, outcome)));
    }

    private static void verdict(Place place, VerifierSteps.Outcome outcome) {
        var name = outcome.check() + " " + place;
        var known = KnownLangModelGaps.gap(place, outcome.check());
        if (known.isEmpty()) {
            if (outcome.failure() != null) {
                fail(name + " failed: " + outcome.describe(), outcome.failure());
            }
            return;
        }
        var gap = known.get();
        if (outcome.failure() == null) {
            fail(name + " passes now: remove its line in KnownLangModelGaps (" + gap.bug() + ")");
        } else if (!(outcome.failure() instanceof AssertionError)) {
            fail(name + " fails otherwise than for " + gap.bug() + ": " + outcome.describe(), outcome.failure());
        } else if (!gap.line().equals(outcome.failedLine())) {
            fail(name + " fails at " + outcome.failedLine() + ", not at " + gap.line() + " pinned for " + gap.bug()
                    + ": " + outcome.describe(), outcome.failure());
        } else {
            abort(name + " fails for " + gap.bug() + ": " + outcome.describe());
        }
    }

    /** The test's class path, which a Surefire booter jar may hide, with the jars the compilation reads. */
    private static String classPath() {
        var paths = new LinkedHashSet<String>(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (var type : List.of(LangModelVerifier.class, jakarta.enterprise.context.Dependent.class,
                jakarta.enterprise.lang.model.declarations.ClassInfo.class, jakarta.inject.Inject.class,
                VaubanProcessor.class, VaubanContainer.class, io.vidocq.vauban.api.VaubanComponentProvider.class)) {
            try {
                paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        return String.join(File.pathSeparator, new ArrayList<>(paths));
    }
}
