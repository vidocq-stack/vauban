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
package io.vidocq.vauban.maven.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A jar without a manifest or without a usable module name, and class entries outside any package. */
class ModuleAnalyzerManifestTest {

    @TempDir
    Path tmp;

    private Path jar(Manifest manifest) throws IOException {
        var file = tmp.resolve("my-lib-1.0.jar");
        try (var out = manifest == null ? new JarOutputStream(Files.newOutputStream(file))
                : new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var name : new String[] {"com/acme/", "com/acme/Thing.class", "Root.class", ".class", "readme.txt"}) {
                out.putNextEntry(new JarEntry(name));
                out.write("x".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    void aJarWithoutAManifestIsUnnamedAndOnlyPackagedClassesCount() throws IOException {
        var result = ModuleAnalyzer.analyze(jar(null));

        assertEquals(ModuleType.UNNAMED, result.type());
        assertEquals("my.lib", result.moduleName());
        assertEquals(Set.of("com.acme"), result.packages());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"   "})
    void aManifestWithoutAModuleNameLeavesTheJarUnnamed(String automaticName) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticName != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticName);
        }

        var result = ModuleAnalyzer.analyze(jar(manifest));

        assertEquals(ModuleType.UNNAMED, result.type());
        assertEquals("my.lib", result.moduleName());
    }

    @Test
    void anAutomaticModuleNameIsTrimmed() throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "  com.acme.lib  ");

        var result = ModuleAnalyzer.analyze(jar(manifest));

        assertEquals(ModuleType.AUTOMATIC, result.type());
        assertEquals("com.acme.lib", result.moduleName());
    }
}
