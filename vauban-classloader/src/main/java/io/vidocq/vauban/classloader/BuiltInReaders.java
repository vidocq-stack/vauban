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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Built-in {@link ArchiveReader}s for the two archive shapes that need no plugin: plain
 * jars and exploded class directories. Anything else (sjar…) comes from a
 * {@code ByteSourcePlugin}.
 */
final class BuiltInReaders {

    private BuiltInReaders() {}

    static ArchiveReader open(Path archive) throws IOException {
        if (Files.isDirectory(archive)) {
            return new DirReader(archive);
        }
        return new JarReader(archive);
    }

    static final class DirReader implements ArchiveReader {
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
        public void close() {
            // nothing to release
        }
    }

    static final class JarReader implements ArchiveReader {
        private final JarFile jar;

        JarReader(Path path) throws IOException {
            this.jar = new JarFile(path.toFile());
        }

        @Override
        public List<String> classEntries() {
            var entries = new ArrayList<String>();
            var e = jar.entries();
            while (e.hasMoreElements()) {
                var entry = e.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    entries.add(entry.getName());
                }
            }
            return entries;
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
        public void close() throws IOException {
            jar.close();
        }
    }
}
