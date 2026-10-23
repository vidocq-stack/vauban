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
package io.vidocq.vauban.maven.enhance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.maven.enhance.fixture.Widget;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which dependency each requested type is found in, and what the goal says when it cannot enhance it. */
class EnhanceDependenciesMojoTest {

    private static final String WIDGET_ENTRY = Widget.class.getName().replace('.', '/') + ".class";
    private static final String GROUP = "com.acme";

    @TempDir
    Path tmp;

    /** Records what the goal logs. */
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

    private static Artifact artifact(String artifactId, File file) {
        var artifact = new DefaultArtifact(GROUP, artifactId, "1.0", Artifact.SCOPE_COMPILE, "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file);
        return artifact;
    }

    private static void set(Object mojo, String name, Object value) throws ReflectiveOperationException {
        Field field = EnhanceDependenciesMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private RecordingLog execute(List<String> types, Artifact... artifacts) throws Exception {
        var project = new MavenProject();
        project.setArtifacts(new LinkedHashSet<>(List.of(artifacts)));
        var mojo = new EnhanceDependenciesMojo();
        set(mojo, "project", project);
        set(mojo, "outputDirectory", tmp.resolve("enhanced").toFile());
        set(mojo, "enhancedTypes", types);
        var log = new RecordingLog();
        mojo.setLog(log);
        mojo.execute();
        return log;
    }

    private Path widgetJar(String name) throws IOException {
        var jar = tmp.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar));
                var in = Widget.class.getClassLoader().getResourceAsStream(WIDGET_ENTRY)) {
            out.putNextEntry(new JarEntry(WIDGET_ENTRY));
            out.write(in.readAllBytes());
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void aTypeInAClassesDirectoryOrNowhereCannotBeEnhanced() throws Exception {
        var classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve(WIDGET_ENTRY).getParent());
        Files.write(classes.resolve(WIDGET_ENTRY), new byte[] {0});
        var notAZip = Files.writeString(tmp.resolve("broken.jar"), "not a zip");

        var log = execute(List.of(Widget.class.getName(), "com.acme.Missing"),
                artifact("broken", notAZip.toFile()), artifact("module", classes.toFile()));

        assertEquals(3, log.warnings.size(), log.warnings.toString());
        assertTrue(log.warnings.get(0).startsWith("Vauban enhance-dependencies: " + Widget.class.getName()
                + " was found in " + classes + ", a classes directory rather than a jar"), log.warnings.get(0));
        assertEquals("Vauban enhance-dependencies: no dependency jar contains com.acme.Missing", log.warnings.get(1));
        assertTrue(log.warnings.get(2).startsWith("Vauban enhance-dependencies: none of the requested types were"
                + " found in a dependency jar"), log.warnings.get(2));
    }

    @Test
    void aTypeInAJarIsEnhancedIntoACopy() throws Exception {
        var jar = widgetJar("widget-1.0.jar");

        var log = execute(List.of(Widget.class.getName()), artifact("widget", jar.toFile()));

        assertTrue(Files.isRegularFile(tmp.resolve("enhanced/widget-1.0.jar")));
        assertTrue(log.infos.stream().anyMatch(info -> info.startsWith("Vauban: enhanced widget-1.0.jar -> "
                + "widget-1.0.jar (1 type(s); providers: [")), log.infos.toString());
    }
}
