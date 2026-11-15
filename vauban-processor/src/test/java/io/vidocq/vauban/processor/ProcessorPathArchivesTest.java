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
package io.vidocq.vauban.processor;

import io.vidocq.vauban.indexer.model.ClassInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The beans of the bean archives on the processor path, which a compilation that does not read their modules still
 * resolves its injection points against (BUG-20261115-04): read from each archive's {@code vauban-beans.list} and
 * class files, never loaded.
 */
class ProcessorPathArchivesTest {

    @Test
    void everyArchiveOnThePathGivesItsListedBeansOnceEach(@TempDir Path dir) throws IOException {
        Path first = archive(dir.resolve("first"), """
                # Vauban discovered beans

                gen.Listed
                gen.Absent
                gen.Skipped
                """);
        writeClass(first, "gen.Listed");
        writeClass(first, "gen.Skipped");
        Path second = archive(dir.resolve("second"), "gen.Listed\ngen.Junk\n");
        Files.writeString(second.resolve("gen/Junk.class"), "not a class file");

        List<ClassInfo> beans;
        try (var loader = new URLClassLoader(new URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
            beans = ProcessorPathArchives.beanClasses(loader, name -> name.endsWith("Skipped"));
        }

        // gen.Absent has no class file, gen.Junk no class file this JDK reads, gen.Skipped is the compilation's.
        assertEquals(List.of("gen.Listed"), beans.stream().map(bean -> bean.name().value()).toList());
    }

    @Test
    void aListThatCannotBeReadDeclaresNothing(@TempDir Path dir) throws IOException {
        assertTrue(ProcessorPathArchives.listedClasses(dir.resolve("missing.list").toUri().toURL()).isEmpty());
    }

    @Test
    void aPathThatCannotBeListedHasNoBeans() {
        var loader = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("unreadable path");
            }
        };

        assertTrue(ProcessorPathArchives.beanClasses(loader, name -> false).isEmpty());
    }

    @Test
    void aClassFileThatCannotBeReadIsEmpty() {
        var loader = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable class file");
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        throw new IOException("unreadable class file");
                    }
                };
            }
        };

        assertArrayEquals(new byte[0], ProcessorPathArchives.classFile(loader, "gen.Listed"));
    }

    /** The reader a {@code vauban-beans.list} and a service file share, one name per line. */
    @Test
    void aListGivesItsNamesWithoutCommentsOrBlankLines() throws IOException {
        var list = "# Generated\n\n  gen.First  # the first\ngen.Second\n   \n";

        assertEquals(List.of("gen.First", "gen.Second"),
                ProcessorPathArchives.listedNames(new ByteArrayInputStream(list.getBytes(StandardCharsets.UTF_8))));
    }

    /** A list that fails while it is read throws, as an {@link IOException}, never an unchecked one. */
    @Test
    void aListThatFailsWhileItIsReadThrowsAnIOException() {
        var failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable list");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("unreadable list");
            }
        };

        assertThrows(IOException.class, () -> ProcessorPathArchives.listedNames(failing));
    }

    /** A directory with a {@code META-INF/vauban-beans.list} of {@code list}. */
    private static Path archive(Path root, String list) throws IOException {
        Files.createDirectories(root.resolve("META-INF"));
        Files.createDirectories(root.resolve("gen"));
        Files.writeString(root.resolve(ProcessorPathArchives.BEANS_LIST_PATH), list, StandardCharsets.UTF_8);
        return root;
    }

    private static void writeClass(Path root, String name) throws IOException {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(name), cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
        Files.write(root.resolve(name.replace('.', '/') + ".class"), bytes);
    }
}
