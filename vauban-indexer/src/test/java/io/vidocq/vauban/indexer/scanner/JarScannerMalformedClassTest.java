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
package io.vidocq.vauban.indexer.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import io.vidocq.vauban.classloader.spi.PluginContext;
import io.vidocq.vauban.indexer.model.ClassInfo;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A class file that cannot be parsed is skipped, in a jar as in an archive a plugin opens. */
class JarScannerMalformedClassTest {

    private static final byte[] GOOD = ClassFile.of().build(ClassDesc.of("a.Good"), clb -> { });
    private static final byte[] BAD = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0};

    @TempDir
    Path tmp;

    private static Map<String, byte[]> entries() {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("module-info.class", BAD);
        entries.put("a/module-info.class", BAD);
        entries.put("a/Good.class", GOOD);
        entries.put("a/Bad.class", BAD);
        return entries;
    }

    private static List<String> names(List<ClassInfo> classes) {
        return classes.stream().map(info -> info.name().value()).toList();
    }

    @Test
    void aJarKeepsItsReadableClassesOnly() throws IOException {
        var jar = tmp.resolve("lib.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries().entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }

        assertEquals(List.of("a.Good"), names(JarScanner.scan(jar)));
    }

    @Test
    void anArchiveAPluginOpensKeepsItsReadableClassesOnly() throws IOException {
        var archive = tmp.resolve("lib.sealed");
        var plugin = new ByteSourcePlugin() {
            @Override
            public String protocol() {
                return "sealed";
            }

            @Override
            public boolean handles(Path archivePath) {
                return archivePath.getFileName().toString().endsWith(".sealed");
            }

            @Override
            public ArchiveReader open(Path archivePath, PluginContext context) {
                return new InMemoryReader(entries());
            }
        };

        assertEquals(List.of("a.Good"), names(JarScanner.scan(archive, List.of(plugin), PluginContext.empty())));
    }

    /** An archive held in memory. */
    private record InMemoryReader(Map<String, byte[]> entries) implements ArchiveReader {

        @Override
        public List<String> classEntries() {
            return List.copyOf(entries.keySet());
        }

        @Override
        public byte[] readClass(String entryName) {
            return entries.get(entryName);
        }

        @Override
        public Optional<byte[]> readResource(String entryName) {
            return Optional.empty();
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
