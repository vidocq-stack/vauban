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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class SjarOpaqueFormatTest {

    @TempDir
    Path tempDir;

    @Test
    void noInternalNameLeaksOnDisk() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = buildJar();
        SjarEncryptor.encryptJar(jarPath, key, "vidocq-prod");

        try (var jar = new JarFile(jarPath.toFile())) {
            var names = jar.stream().map(java.util.zip.ZipEntry::getName).toList();
            assertTrue(names.stream().noneMatch(n -> n.contains("internal")),
                    "internal package path leaked: " + names);
            assertTrue(names.stream().noneMatch(n -> n.endsWith(".class.enc")));
            assertTrue(names.contains(SjarHeader.HEADER_ENTRY));
            assertTrue(names.contains(SjarMetadata.INDEX_ENTRY));
            assertTrue(names.stream().anyMatch(n -> n.startsWith(SjarMetadata.BLOB_DIR)));
        }
    }

    @Test
    void indexIsOpaqueWithoutKey() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = buildJar();
        SjarEncryptor.encryptJar(jarPath, key, "vidocq-prod");

        try (var jar = new JarFile(jarPath.toFile());
             var is = jar.getInputStream(jar.getEntry(SjarMetadata.INDEX_ENTRY))) {
            var raw = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            // Ciphertext must not contain readable FQCN fragments
            assertFalse(raw.contains("internal"));
            assertFalse(raw.contains("Secret"));
        }
        // Wrong key cannot decrypt the index
        var wrong = SjarKeyProvider.generateKey();
        var ctx = SjarKeyProvider.withKey(wrong);
        assertThrows(Exception.class, () -> new SjarArchiveReader(jarPath, ctx));
    }

    @Test
    void classLoadsUnderRealNameThroughClassLoader() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = buildJar();
        SjarEncryptor.encryptJar(jarPath, key, "vidocq-prod");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var loader = new SjarClassLoader(reader, getClass().getClassLoader());
            var loaded = Class.forName("com.example.internal.Secret", false, loader);
            assertEquals("com.example.internal.Secret", loaded.getName());
        }
    }

    private Path buildJar() throws Exception {
        var jarPath = tempDir.resolve("app.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(ClassFile.of().buildModule(
                    java.lang.classfile.attribute.ModuleAttribute.of(
                            java.lang.constant.ModuleDesc.of("com.example"),
                            mb -> {
                                mb.requires(java.lang.constant.ModuleDesc.of("java.base"), 0, null);
                                mb.exports(java.lang.constant.PackageDesc.ofInternalName("com/example/api"), 0);
                            }),
                    mb -> {}));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/api/Service.class"));
            jos.write(realClass("com/example/api/Service"));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/internal/Secret.class"));
            jos.write(realClass("com/example/internal/Secret"));
            jos.closeEntry();
        }
        return jarPath;
    }

    /** Build a minimal but real, loadable class with the given internal name. */
    private byte[] realClass(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), cb -> {});
    }
}
