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

import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vidocq/vauban#177: resources of plain jars and exploded directories have the URLs the JDK's own
 * loaders give them, so a library can list a resource folder (Vaadin's development mode copies its
 * frontend files that way). Only archives read by a {@link ByteSourcePlugin} keep {@code vauban:} URLs.
 */
@DisplayName("VaubanClassLoader — standard resource URLs")
class StandardResourceUrlsTest {

    @Test
    @DisplayName("a plain-jar resource has a readable jar: URL")
    void plainJarResourceHasAJarUrl(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("lib.jar"));
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            var url = loader.getResource("a/b/c.txt");
            assertNotNull(url);
            assertEquals("jar", url.getProtocol());
            assertEquals(jar.toUri().toURL(), ((JarURLConnection) url.openConnection()).getJarFileURL());
            assertEquals("c", read(url));
            assertNull(loader.getResource("a/b/missing.txt"));
        }
    }

    @Test
    @DisplayName("a plain-jar folder can be listed through its jar: URL")
    void plainJarFolderCanBeListed(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("lib.jar"));
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            var url = loader.getResource("a/b/");
            assertNotNull(url, "the folder entry must be found");
            var connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            try (var file = connection.getJarFile()) {
                var listed = file.stream().map(JarEntry::getName)
                        .filter(n -> n.startsWith(connection.getEntryName()) && !n.endsWith("/"))
                        .toList();
                assertEquals(List.of("a/b/c.txt", "a/b/d.txt"), listed);
            }
            assertEquals(1, Collections.list(loader.getResources("a/b/")).size());
        }
    }

    @Test
    @DisplayName("names with a space, a # or a % are encoded in the jar: URL")
    void plainJarNamesAreEncoded(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("my lib.jar"));
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertEquals("space", read(loader.getResource("a/with space.txt")));
            assertEquals("hash", read(loader.getResource("a/x#1.txt")));
            assertEquals("percent", read(loader.getResource("a/50%.txt")));
        }
    }

    @Test
    @DisplayName("a URL relative to a jar folder reads the entry")
    void relativeUrlInAJarFolder(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("lib.jar"));
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            @SuppressWarnings("deprecation")
            var relative = new URL(loader.getResource("a/b/"), "d.txt");
            assertEquals("d", read(relative));
        }
    }

    @Test
    @DisplayName("a caching connection shares a jar file that closes with the loader, outside the JDK's cache")
    void cachingConnectionClosesWithTheLoader(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("lib.jar"));
        java.util.jar.JarFile shared;
        URL url;
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            url = loader.getResource("a/b/c.txt");
            var connection = (JarURLConnection) url.openConnection();
            assertTrue(connection.getUseCaches());
            try (var in = connection.getInputStream()) {
                assertEquals("c", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(1, connection.getContentLengthLong());
            shared = connection.getJarFile();
            assertSame(shared, ((JarURLConnection) url.openConnection()).getJarFile());
            assertNotNull(shared.getEntry("a/b/d.txt"));
        }
        assertThrows(IllegalStateException.class, () -> shared.getEntry("a/b/d.txt"),
                "the shared jar file closes with the loader");
        assertThrows(java.io.IOException.class, url::openStream, "the loader is closed");
    }

    @Test
    @DisplayName("a connection connected before the loader closes reports the closed loader as an IOException")
    void connectedConnectionReadAfterTheLoaderClosesThrowsIOException(@TempDir Path dir) throws Exception {
        // VAU-CL-002: the closed jar file used to surface as a NullPointerException.
        var jar = jar(dir.resolve("lib.jar"));
        URLConnection connection;
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            connection = loader.getResource("a/b/c.txt").openConnection();
            connection.connect();
        }
        var thrown = assertThrows(IOException.class, connection::getInputStream);
        assertTrue(thrown.getMessage().contains("is closed"), thrown.getMessage());
        assertEquals(-1, connection.getContentLengthLong(), "a closed loader has no known length");
    }

    @Test
    @DisplayName("an exploded-directory file and folder have file: URLs")
    void directoryResourcesHaveFileUrls(@TempDir Path dir) throws Exception {
        var root = dir.resolve("classes");
        Files.createDirectories(root.resolve("a/b"));
        Files.writeString(root.resolve("a/b/c.txt"), "c");
        Files.writeString(dir.resolve("outside.txt"), "outside");
        try (var loader = VaubanClassLoader.of(List.of(root), getClass().getClassLoader(),
                PluginContext.empty())) {
            var file = loader.getResource("a/b/c.txt");
            assertNotNull(file);
            assertEquals(root.resolve("a/b/c.txt").toUri().toURL(), file);
            assertEquals("c", read(file));

            var folder = loader.getResource("a/b/");
            assertNotNull(folder, "the folder must be found");
            assertEquals("file", folder.getProtocol());
            try (Stream<Path> listed = Files.list(Path.of(folder.toURI()))) {
                assertEquals(List.of("c.txt"), listed.map(p -> p.getFileName().toString()).toList());
            }
            assertNull(loader.getResource("a/b/missing.txt"));
            assertNull(loader.getResource("../outside.txt"), "a name must not escape the archive");
            assertNull(loader.getResourceAsStream("../outside.txt"), "nor through a stream");
            var outside = dir.resolve("outside.txt").toAbsolutePath().toString();
            assertNull(loader.getResource(outside), "an absolute name is outside too");
            assertNull(loader.getResourceAsStream(outside));
            assertTrue(Collections.list(loader.getResources("")).contains(root.toUri().toURL()),
                    "the empty name is the directory, after the parent's own");
        }
    }

    @Test
    @DisplayName("an archive read by a ByteSourcePlugin keeps its vauban: URLs")
    void pluginArchiveKeepsVaubanUrls(@TempDir Path dir) throws Exception {
        var archive = Files.createFile(dir.resolve("secret.bin"));
        var plugin = new ByteSourcePlugin() {
            @Override
            public String protocol() {
                return "test";
            }

            @Override
            public boolean handles(Path path) {
                return path.equals(archive);
            }

            @Override
            public ArchiveReader open(Path path, PluginContext context) {
                return new MapReader(Map.of("a/b/c.txt", "c", "a/with space.txt", "space"));
            }
        };
        try (var loader = VaubanClassLoader.of(List.of(archive), getClass().getClassLoader(),
                PluginContext.empty(), List.of(plugin))) {
            var url = loader.getResource("a/b/c.txt");
            assertNotNull(url);
            assertEquals("vauban", url.getProtocol());
            assertEquals("c", read(url));
            assertEquals("space", read(loader.getResource("a/with space.txt")));
        }
    }

    private static Path jar(Path jar) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var folder : List.of("a/", "a/b/")) {
                out.putNextEntry(new JarEntry(folder));
                out.closeEntry();
            }
            for (var entry : Map.of("a/b/c.txt", "c", "a/b/d.txt", "d", "a/with space.txt", "space",
                            "a/x#1.txt", "hash", "a/50%.txt", "percent")
                    .entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String read(URL url) throws IOException {
        var connection = url.openConnection();
        connection.setUseCaches(false);
        try (var in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record MapReader(Map<String, String> entries) implements ArchiveReader {
        @Override
        public List<String> classEntries() {
            return List.of();
        }

        @Override
        public byte[] readClass(String entryName) throws IOException {
            throw new IOException("No class " + entryName);
        }

        @Override
        public Optional<byte[]> readResource(String entryName) {
            return Optional.ofNullable(entries.get(entryName)).map(s -> s.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
