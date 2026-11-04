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
package io.vidocq.vauban.classloader;

import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A process snapshot (CRaC checkpoint, CRIU dump) refuses an open file: the loader lets go of the jars it holds
 * ({@link VaubanClassLoader#releaseArchives()}) and takes them again ({@link VaubanClassLoader#reopenArchives()}).
 */
@DisplayName("VaubanClassLoader — release and reopen the archives")
class ReleaseArchivesTest {

    private static Path jar(Path file, String entry, String content) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private static String read(URL url) throws IOException {
        try (var in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("a released loader holds no jar: the file is read again, from its path, once reopened")
    void releasedLoaderReadsTheFileAgainOnceReopened(@TempDir Path dir) throws Exception {
        var path = jar(dir.resolve("app.jar"), "a.txt", "first");
        try (var loader = VaubanClassLoader.of(List.of(path), getClass().getClassLoader(), PluginContext.empty())) {
            assertEquals("first", read(loader.getResource("a.txt")));

            // A new jar at the same path: the open one keeps reading the old file.
            var replacement = jar(dir.resolve("next.jar"), "b.txt", "second");
            Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING);
            assertNull(loader.getResource("b.txt"), "control: an open loader still reads the old file");

            loader.releaseArchives();
            loader.reopenArchives();

            assertNotNull(loader.getResource("b.txt"));
            assertEquals("second", read(loader.getResource("b.txt")));
            assertNull(loader.getResource("a.txt"));
        }
    }

    @Test
    @DisplayName("release and reopen are idempotent")
    void idempotent(@TempDir Path dir) throws Exception {
        var path = jar(dir.resolve("app.jar"), "a.txt", "first");
        try (var loader = VaubanClassLoader.of(List.of(path), getClass().getClassLoader(), PluginContext.empty())) {
            loader.releaseArchives();
            loader.releaseArchives();
            loader.reopenArchives();
            loader.reopenArchives();

            assertEquals("first", read(loader.getResource("a.txt")));
        }
    }

    @Test
    @DisplayName("a read while released takes the jar again by itself")
    void aReadWhileReleasedReopens(@TempDir Path dir) throws Exception {
        var path = jar(dir.resolve("app.jar"), "a.txt", "first");
        try (var loader = VaubanClassLoader.of(List.of(path), getClass().getClassLoader(), PluginContext.empty())) {
            loader.releaseArchives();

            assertEquals("first", read(loader.getResource("a.txt")));
        }
    }

    @Test
    @DisplayName("a closed loader stays closed: reopen does not bring it back")
    void closeIsFinal(@TempDir Path dir) throws Exception {
        var path = jar(dir.resolve("app.jar"), "a.txt", "first");
        var loader = VaubanClassLoader.of(List.of(path), getClass().getClassLoader(), PluginContext.empty());
        loader.close();

        assertThrows(IOException.class, loader::reopenArchives);
    }
}
