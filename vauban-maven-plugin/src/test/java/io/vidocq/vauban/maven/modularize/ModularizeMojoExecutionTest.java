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
package io.vidocq.vauban.maven.modularize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code vauban:modularize} over a project without jar dependencies: nothing to patch, and the licence gate passes. */
class ModularizeMojoExecutionTest {

    @TempDir
    Path tmp;

    private static final class RecordingLog extends SystemStreamLog {
        final List<String> infos = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            infos.add(content.toString());
        }
    }

    private static void set(Object mojo, String name, Object value) throws ReflectiveOperationException {
        Field field = ModularizeMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    @Test
    void aProjectWithoutJarsPatchesNothingAndPassesTheLicenceGate() throws Exception {
        var project = new MavenProject();
        project.setArtifacts(Set.of());
        var mojo = new ModularizeMojo();
        set(mojo, "project", project);
        set(mojo, "buildDir", tmp.toFile());
        set(mojo, "mode", "derived");
        set(mojo, "allowedLicenses", List.of("Apache-2.0"));
        var log = new RecordingLog();
        mojo.setLog(log);

        mojo.execute();

        var summary = log.infos.getLast();
        assertTrue(summary.startsWith("vauban:modularize — 0 jar(s) patched, 0 left as is; report: "), summary);
        assertEquals(ModularizedJars.root(tmp).resolve(Modularizer.REPORT_FILE_NAME).toString(),
                summary.substring(summary.indexOf("report: ") + "report: ".length()));
    }

    @Test
    void theSelectionsOfTheConfigurationAreAccepted() throws Exception {
        var project = new MavenProject();
        project.setArtifacts(Set.of());
        var mojo = new ModularizeMojo();
        set(mojo, "project", project);
        set(mojo, "buildDir", tmp.toFile());
        set(mojo, "includes", List.of("lib-a"));
        set(mojo, "excludes", List.of("lib-b"));
        set(mojo, "moduleNames", Map.of("lib-a", "com.acme.a"));
        var log = new RecordingLog();
        mojo.setLog(log);

        mojo.execute();

        var summary = log.infos.getLast();
        assertTrue(summary.startsWith("vauban:modularize — 0 jar(s) patched, 0 left as is; report: "), summary);
        assertEquals(ModularizedJars.root(tmp).resolve(Modularizer.REPORT_FILE_NAME).toString(),
                summary.substring(summary.indexOf("report: ") + "report: ".length()));
    }
}
