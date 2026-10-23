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
package io.vidocq.vauban.sjar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The layout of an encrypted jar for directory entries and a jar encrypted twice, what the readers answer for what it
 * does not hold, and that a failed encryption leaves the jar as it was.
 */
class SjarLayoutAndMissingEntriesTest {

    private static final String INTERNAL_CLASS = "com/example/internal/Impl.class";
    private static final String INTERNAL_RESOURCE = "com/example/internal/data.bin";
    private static final String EXPORTED_CLASS = "com/example/api/Service.class";

    @TempDir
    Path tmp;

    private Path modularJar() throws IOException {
        var jar = tmp.resolve("lib.jar");
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("com.example"), mb -> {
            mb.requires(ModuleDesc.of("java.base"), 0, null);
            mb.exports(PackageDesc.ofInternalName("com/example/api"), 0);
        }));
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var directory : List.of("META-INF/", "com/", "com/example/", "com/example/api/",
                    "com/example/internal/")) {
                out.putNextEntry(new JarEntry(directory));
                out.closeEntry();
            }
            put(out, "module-info.class", moduleInfo);
            put(out, EXPORTED_CLASS, ClassFile.of().build(ClassDesc.of("com.example.api.Service"), clb -> { }));
            put(out, INTERNAL_CLASS, ClassFile.of().build(ClassDesc.of("com.example.internal.Impl"), clb -> { }));
            put(out, INTERNAL_RESOURCE, new byte[] {1, 2, 3});
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static List<String> names(Path jar) throws IOException {
        try (var file = new JarFile(jar.toFile())) {
            return Collections.list(file.entries()).stream().map(JarEntry::getName).toList();
        }
    }

    @Test
    void theDirectoriesOfInternalPackagesLeaveNoTrace() throws Exception {
        var jar = modularJar();

        SjarEncryptor.encryptJar(jar, SjarKeyProvider.generateKey(), "test");

        var names = names(jar);
        assertTrue(names.containsAll(List.of("META-INF/", "com/", "com/example/", "com/example/api/")), names.toString());
        assertFalse(names.contains("com/example/internal/"), names.toString());
    }

    @Test
    void encryptingTwiceKeepsOneHeaderAndOneIndex() throws Exception {
        var jar = modularJar();
        var key = SjarKeyProvider.generateKey();
        SjarEncryptor.encryptJar(jar, key, "test");

        SjarEncryptor.encryptJar(jar, key, "test");

        var names = names(jar);
        assertEquals(1, names.stream().filter(SjarHeader.HEADER_ENTRY::equals).count());
        assertEquals(1, names.stream().filter(SjarMetadata.INDEX_ENTRY::equals).count());
        try (var reader = new SjarArchiveReader(jar, SjarKeyProvider.withKey(key))) {
            assertEquals(List.of(), reader.classEntries().stream().filter(e -> e.startsWith(SjarMetadata.BLOB_DIR))
                    .toList(), "the first pass's blobs are not taken as entries");
        }
    }

    @Test
    void aFailedEncryptionLeavesTheJarAndNoTemporaryCopy() throws Exception {
        var jar = modularJar();
        var before = Files.readAllBytes(jar);
        var tooShort = new SecretKeySpec(new byte[5], "AES");

        assertThrows(GeneralSecurityException.class, () -> SjarEncryptor.encryptJar(jar, tooShort, "test"));

        assertArrayEquals(before, Files.readAllBytes(jar));
        try (Stream<Path> files = Files.list(tmp)) {
            assertEquals(List.of(jar), files.toList());
        }
    }

    @Test
    void anEntryNeitherClearNorIndexedIsNotFound() throws Exception {
        var jar = modularJar();
        var key = SjarKeyProvider.generateKey();
        SjarEncryptor.encryptJar(jar, key, "test");

        try (var reader = new SjarArchiveReader(jar, SjarKeyProvider.withKey(key))) {
            var failure = assertThrows(IOException.class, () -> reader.readClass("com/example/Absent.class"));

            assertEquals("Entry not found: com/example/Absent.class", failure.getMessage());
            var loader = new SjarClassLoader(reader, getClass().getClassLoader());
            loader.preload();
            assertTrue(loader.hasClass("com.example.internal.Impl"));
            assertFalse(loader.hasClass("com.example.Absent"));
            assertNull(loader.getResourceAsStream("com/example/Absent.class"));
            assertNull(loader.getResourceAsStream("absent.txt"));
        }
    }

    @Test
    void anIndexedResourceWhoseBlobIsGoneIsAbsent() throws Exception {
        var jar = modularJar();
        var key = SjarKeyProvider.generateKey();
        SjarEncryptor.encryptJar(jar, key, "test");
        var stripped = tmp.resolve("stripped.jar");
        try (var in = new JarFile(jar.toFile()); var out = new JarOutputStream(Files.newOutputStream(stripped))) {
            for (var entry : Collections.list(in.entries())) {
                if (!entry.getName().startsWith(SjarMetadata.BLOB_DIR)) {
                    try (var bytes = in.getInputStream(entry)) {
                        put(out, entry.getName(), bytes.readAllBytes());
                    }
                }
            }
        }

        try (var reader = new SjarArchiveReader(stripped, SjarKeyProvider.withKey(key))) {
            assertNull(new SjarClassLoader(reader, getClass().getClassLoader()).getResourceAsStream(INTERNAL_RESOURCE));
        }
    }

    @Test
    void aFileThatIsNoJarIsNotAnEncryptedArchive() throws IOException {
        var notAJar = Files.writeString(tmp.resolve("broken.jar"), "not a zip");

        assertFalse(new SjarPlugin().handles(notAJar));
    }

    @Test
    void entryMetadataIsComparedByContent() {
        var iv = "123456789012".getBytes(StandardCharsets.UTF_8);
        var metadata = new SjarMetadata.EntryMetadata("uuid", iv, 10, "class");
        var same = new SjarMetadata.EntryMetadata("uuid", iv.clone(), 10, "class");

        assertEquals(metadata, same);
        assertEquals(metadata.hashCode(), same.hashCode());
        assertEquals("EntryMetadata[uuid=uuid, iv=" + java.util.Arrays.toString(iv) + ", originalSize=10, kind=class]",
                metadata.toString());
        assertNotEquals(metadata, new SjarMetadata.EntryMetadata("uuid", iv, 11, "class"));
        assertNotEquals(metadata, new SjarMetadata.EntryMetadata("other", iv, 10, "class"));
        assertNotEquals(metadata, new SjarMetadata.EntryMetadata("uuid", new byte[12], 10, "class"));
        assertNotEquals(metadata, new SjarMetadata.EntryMetadata("uuid", iv, 10, "resource"));
        assertNotEquals(metadata, (Object) "uuid");
    }
}
