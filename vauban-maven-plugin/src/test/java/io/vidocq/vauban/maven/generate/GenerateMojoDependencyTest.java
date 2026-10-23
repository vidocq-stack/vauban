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

import static io.vidocq.vauban.maven.generate.GeneratorInputs.DEPENDENT;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.beanClass;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.jar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code vauban:generate} scans the project's jar and directory dependencies, and nothing else. */
class GenerateMojoDependencyTest {

    @TempDir
    Path tmp;

    private static final class RecordingLog extends SystemStreamLog {
        final List<String> infos = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            infos.add(content.toString());
        }
    }

    private static Artifact artifact(String artifactId, File file) {
        var artifact = new DefaultArtifact("com.acme", artifactId, "1.0", Artifact.SCOPE_COMPILE, "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file);
        return artifact;
    }

    private static void set(Object mojo, String name, Object value) throws ReflectiveOperationException {
        Field field = GenerateMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    @Test
    void onlyJarsAndDirectoriesAreScanned() throws Exception {
        var lib = jar(tmp.resolve("lib.jar"), Map.of("com/lib/Service.class", beanClass("com.lib.Service", DEPENDENT)));
        var module = tmp.resolve("module-classes");
        GeneratorInputs.writeClass(module, "com.module.Bean", beanClass("com.module.Bean", DEPENDENT));
        var pom = Files.writeString(tmp.resolve("parent.pom"), "<project/>");
        var project = new MavenProject();
        project.setArtifacts(new LinkedHashSet<>(List.of(artifact("lib", lib.toFile()), artifact("module",
                module.toFile()), artifact("parent", pom.toFile()), artifact("unresolved", null))));
        var output = Files.createDirectories(tmp.resolve("classes"));
        var mojo = new GenerateMojo();
        set(mojo, "project", project);
        set(mojo, "outputDirectory", output.toFile());
        var log = new RecordingLog();
        mojo.setLog(log);

        mojo.execute();

        assertTrue(log.infos.stream().anyMatch(info -> info.startsWith("Vauban: discovered 2 CDI beans")),
                log.infos.toString());
        assertEquals(List.of("# Vauban discovered beans — generated at build time", "com.lib.Service",
                "com.module.Bean"), Files.readAllLines(output.resolve(VaubanGenerator.BEANS_LIST_PATH)));
    }
}
