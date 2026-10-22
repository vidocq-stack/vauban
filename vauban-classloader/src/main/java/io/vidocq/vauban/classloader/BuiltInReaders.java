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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Built-in {@link ArchiveReader}s for the two archive shapes that need no plugin: plain
 * jars and exploded class directories. Anything else (sjar…) comes from a
 * {@code ByteSourcePlugin}.
 */
final class BuiltInReaders {

    private BuiltInReaders() {}

    /**
     * A built-in reader gives its resources the URLs the JDK's own loaders give them: {@code jar:} for
     * a plain jar, {@code file:} for a directory. A library can then list a resource folder, as
     * Vaadin's development mode does to copy its frontend files (vauban#177).
     */
    interface StandardUrls {
        /** The URL of the entry {@code name}, file or folder, or {@code null} when the archive has none. */
        URL resourceUrl(String name) throws IOException;
    }

    static ArchiveReader open(Path archive) throws IOException {
        if (Files.isDirectory(archive)) {
            return new DirReader(archive);
        }
        return new JarReader(archive);
    }

    static final class DirReader implements ArchiveReader, StandardUrls {
        private final Path root;

        DirReader(Path root) {
            this.root = root;
        }

        @Override
        public List<String> classEntries() throws IOException {
            try (Stream<Path> walk = Files.walk(root)) {
                var entries = new ArrayList<String>();
                walk.filter(p -> p.getFileName().toString().endsWith(".class"))
                        .forEach(p -> entries.add(root.relativize(p).toString().replace('\\', '/')));
                return entries;
            }
        }

        @Override
        public byte[] readClass(String entryName) throws IOException {
            return Files.readAllBytes(root.resolve(entryName));
        }

        @Override
        public Optional<byte[]> readResource(String entryName) throws IOException {
            var file = root.resolve(entryName);
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        }

        @Override
        public URL resourceUrl(String name) throws IOException {
            try {
                var base = root.toAbsolutePath().normalize();
                var file = base.resolve(name).normalize();
                // As the JDK's loader does, a name never reaches outside the directory
                return file.startsWith(base) && Files.exists(file) ? file.toUri().toURL() : null;
            } catch (InvalidPathException e) {
                return null;
            }
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    static final class JarReader implements ArchiveReader, StandardUrls {
        private final JarFile jar;
        private final String base;

        JarReader(Path path) throws IOException {
            this.base = "jar:" + path.toUri() + "!/";
            // Multi-release aware, as the module path reads a jar: the base name resolves to the highest
            // META-INF/versions/N entry the running JDK accepts (vauban#154). No signature verification,
            // as before.
            this.jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, JarFile.runtimeVersion());
        }

        @Override
        public List<String> classEntries() {
            return jar.versionedStream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                    .map(JarEntry::getName)
                    .toList();
        }

        @Override
        public byte[] readClass(String entryName) throws IOException {
            var entry = jar.getEntry(entryName);
            if (entry == null) throw new IOException("No such entry: " + entryName);
            try (var in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }

        @Override
        public Optional<byte[]> readResource(String entryName) throws IOException {
            var entry = jar.getEntry(entryName);
            if (entry == null) return Optional.empty();
            try (var in = jar.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        }

        @Override
        public URL resourceUrl(String name) throws IOException {
            if (jar.getEntry(name) == null) return null;
            // The JDK marks the URLs of a multi-release jar so that they read the versioned entry
            var fragment = jar.isMultiRelease() ? "#runtime" : "";
            return URI.create(base + encodePath(name) + fragment).toURL();
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    /** Percent-encodes the characters of an entry name that a URI path cannot hold. */
    static String encodePath(String name) {
        var out = new StringBuilder(name.length());
        for (var b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "-._~/!$&'()*+,;=:@".indexOf(c) >= 0)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
