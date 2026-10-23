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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The built-in readers at their edges: names outside the archive, the jar root, a missing entry, non-ASCII names. */
class BuiltInReaderEdgesTest {

    private static final String ENTRY = "a/x.txt";

    @TempDir
    Path tmp;

    private Path jar() throws IOException {
        var jar = tmp.resolve("lib.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(ENTRY));
            out.write("content".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void aDirectoryReaderRefusesANameOutsideItsRoot() throws IOException {
        var root = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(tmp.resolve("outside.class"), "x");

        try (var reader = BuiltInReaders.open(root)) {
            var failure = assertThrows(IOException.class, () -> reader.readClass("../outside.class"));

            assertEquals("No such entry: ../outside.class", failure.getMessage());
            assertTrue(reader.readResource("bad\0name").isEmpty(), "a name no path can hold is no entry");
        }
    }

    @Test
    void theJarRootHasNoEntry() throws IOException {
        try (var reader = (BuiltInReaders.JarReader) BuiltInReaders.open(jar())) {
            @SuppressWarnings("deprecation")
            var root = new URL(reader.resourceUrl(ENTRY), "../");
            var connection = assertInstanceOf(JarURLConnection.class, root.openConnection());

            assertNull(connection.getJarEntry());
            assertEquals(-1, connection.getContentLengthLong());
            var failure = assertThrows(IOException.class, connection::getInputStream);
            assertEquals("no entry name specified", failure.getMessage());
        }
    }

    @Test
    void aMissingEntryIsNotFoundAndHasNoLength() throws IOException {
        try (var reader = (BuiltInReaders.JarReader) BuiltInReaders.open(jar())) {
            @SuppressWarnings("deprecation")
            var missing = new URL(reader.resourceUrl(ENTRY), "missing.txt");
            var connection = (JarURLConnection) missing.openConnection();

            assertEquals(-1, connection.getContentLengthLong());
            assertThrows(FileNotFoundException.class, connection::getJarEntry);
        }
    }

    @Test
    void anEntryConnectionNamesItsEntry() throws IOException {
        try (var reader = (BuiltInReaders.JarReader) BuiltInReaders.open(jar())) {
            var connection = (JarURLConnection) reader.resourceUrl(ENTRY).openConnection();

            assertEquals(ENTRY, connection.getJarEntry().getName());
            assertEquals("content".length(), connection.getContentLengthLong());
        }
    }

    @Test
    void aJrtLocationNamingNoModuleDirectoryHasNoArchive() {
        assertTrue(VaubanLayerFactory.archiveOf(java.net.URI.create("jrt:/a%00b")).isEmpty());
        assertTrue(VaubanLayerFactory.archiveOf(java.net.URI.create("jrt:/java.base")).isPresent());
    }

    @Test
    void aNonAsciiNameIsPercentEncodedByteByByte() {
        assertEquals("a/%C3%A9t%C3%A9%20x.txt", BuiltInReaders.encodePath("a/été x.txt"));
    }
}
