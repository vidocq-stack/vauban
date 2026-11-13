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
package io.vidocq.vauban.maven.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import io.vidocq.vauban.maven.generate.frozen.Greeting;
import io.vidocq.vauban.maven.generate.frozen.GreetingExtension;
import io.vidocq.vauban.maven.generate.frozen.Hello;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build step that freezes the extensions of an application (VAU-PERF-005): the application's classes directory
 * holds a bean, an extension declared as a service and the bean index {@code vauban:generate} writes; a start on the
 * same path then loads the record.
 */
@DisplayName("vauban:generate freezes the build compatible extensions")
class ExtensionFreezerTest {

    private static final String SERVICE =
            "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    @TempDir
    Path tmp;

    private Path classes;

    private static final class RecordingLog extends SystemStreamLog {
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            infos.add(content.toString());
        }

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }
    }

    /** A start's view of the application: its resources from its own path only, as a packaged application has. */
    private static final class ApplicationLoader extends URLClassLoader {
        ApplicationLoader(Path classes) throws IOException {
            super(new URL[] {classes.toUri().toURL()}, ExtensionFreezerTest.class.getClassLoader());
        }

        @Override
        public URL getResource(String name) {
            return findResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return findResources(name);
        }
    }

    @BeforeEach
    void application() throws IOException {
        classes = Files.createDirectories(tmp.resolve("classes"));
        GeneratorInputs.copyPackage(Hello.class, classes);
        Files.createDirectories(classes.resolve(SERVICE).getParent());
        Files.writeString(classes.resolve(SERVICE), GreetingExtension.class.getName() + "\n");
        GreetingExtension.RUNS.set(0);
    }

    private RecordingLog generate(boolean freeze) throws Exception {
        var project = new MavenProject();
        project.getBuild().setOutputDirectory(classes.toString());
        var mojo = new GenerateMojo();
        set(mojo, "project", project);
        set(mojo, "outputDirectory", classes.toFile());
        set(mojo, "freezeExtensions", freeze);
        var log = new RecordingLog();
        mojo.setLog(log);
        mojo.execute();
        return log;
    }

    private static void set(Object mojo, String name, Object value) throws ReflectiveOperationException {
        Field field = GenerateMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    @Test
    @DisplayName("with freezeExtensions, the start of the application loads what the extension did")
    void theGoalFreezesTheExtensions() throws Exception {
        RecordingLog log = generate(true);

        assertTrue(Files.isRegularFile(classes.resolve(FrozenExtensions.PATH)), log.warnings.toString());
        assertTrue(log.infos.contains("Vauban: froze 1 build compatible extension(s); the container start loads"
                + " what they did."), log.infos.toString());

        GreetingExtension.RUNS.set(0);
        try (var loader = new ApplicationLoader(classes);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(0, GreetingExtension.RUNS.get(), "the start must not run the frozen extension");
            assertTrue(container.extensionsAtStart().frozen(), String.valueOf(container.extensionsAtStart().note()));
            assertEquals("hello from the build", container.select(Greeting.class).text());
            assertEquals("hello", container.select(Hello.class).id());
        }
    }

    @Test
    @DisplayName("without freezeExtensions, no record is written and one of an earlier build goes")
    void theGoalLeavesNoRecordWhenOff() throws Exception {
        generate(true);
        assertTrue(Files.exists(classes.resolve(FrozenExtensions.PATH)));

        generate(false);

        assertFalse(Files.exists(classes.resolve(FrozenExtensions.PATH)));
        assertFalse(Files.exists(classes.resolve(FrozenExtensions.SYNTHETIC_PATH)));
        try (var loader = new ApplicationLoader(classes);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertTrue(GreetingExtension.RUNS.get() > 0, "the start runs the extension");
            assertEquals("hello from the build", container.select(Greeting.class).text());
        }
    }

    @Test
    @DisplayName("an application without an extension is told so, without a warning")
    void anApplicationWithoutExtension() throws Exception {
        Files.delete(classes.resolve(SERVICE));

        RecordingLog log = generate(true);

        assertFalse(Files.exists(classes.resolve(FrozenExtensions.PATH)));
        assertTrue(log.infos.stream().anyMatch(info -> info.contains(FrozenExtensions.NO_EXTENSION)),
                log.infos.toString());
        assertTrue(log.warnings.stream().noneMatch(warning -> warning.contains("frozen")), log.warnings.toString());
    }

    @Test
    @DisplayName("the report names the extensions that run again at start, and why nothing was frozen")
    void theReport() {
        assertEquals(List.of("Vauban: froze 2 build compatible extension(s); the container start loads what they did.",
                        "Vauban: 1 extension(s) run again at each start, having left work for it: com.acme.Check."),
                ExtensionFreezer.report(new FrozenExtensions.Outcome(true, null,
                        List.of("com.acme.A", "com.acme.B", "com.acme.Check"), List.of("com.acme.Check"))));
        assertEquals(List.of("Vauban: the build compatible extensions are not frozen, the container start runs them:"
                        + " com.acme.A removes annotations."),
                ExtensionFreezer.report(FrozenExtensions.Outcome.notFrozen("com.acme.A removes annotations")));
    }

    @Test
    @DisplayName("the path of the deployment alone is scanned, not the build tool's own")
    void onlyTheDeploymentIsScanned() throws Exception {
        generate(false);
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));

        var outcome = ExtensionFreezer.freeze(List.of(elsewhere), getClass().getClassLoader(), elsewhere, List.of());

        assertTrue(outcome.nothingToFreeze(), "the extension of this test's class path is not the deployment's: "
                + outcome);
        assertEquals(List.of(), listFiles(elsewhere));
    }

    private static List<File> listFiles(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).map(Path::toFile).toList();
        }
    }
}
