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
import java.lang.classfile.attribute.ModuleAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class SjarEncryptorTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptAndDecryptBytes() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var plaintext = "Hello CDI".getBytes();
        var iv = SjarEncryptor.generateIv();

        var encrypted = SjarEncryptor.encryptBytes(plaintext, key, iv);
        var decrypted = SjarEncryptor.decryptBytes(encrypted, key);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void wrongKeyFailsDecryption() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var wrongKey = SjarKeyProvider.generateKey();
        var iv = SjarEncryptor.generateIv();
        var encrypted = SjarEncryptor.encryptBytes("secret".getBytes(), key, iv);

        assertThrows(Exception.class, () -> SjarEncryptor.decryptBytes(encrypted, wrongKey));
    }

    @Test
    void encryptJarInPlaceProducesOpaqueLayout() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();

        SjarEncryptor.encryptJar(jarPath, key, "test-key");

        try (var jar = new JarFile(jarPath.toFile())) {
            // Clear bootstrap header + encrypted index exist
            assertNotNull(jar.getEntry(SjarHeader.HEADER_ENTRY));
            assertNotNull(jar.getEntry(SjarMetadata.INDEX_ENTRY));

            // module-info + exported class stay clear
            assertNotNull(jar.getEntry("module-info.class"));
            assertNotNull(jar.getEntry("com/example/api/Service.class"));

            // Internal class/resource no longer visible under their real path
            assertNull(jar.getEntry("com/example/internal/Impl.class"));
            assertNull(jar.getEntry("com/example/internal/Impl.class.enc"));
            assertNull(jar.getEntry("com/example/internal/data.bin"));

            // No ZIP entry leaks an internal package path
            var names = jar.stream().map(java.util.zip.ZipEntry::getName).toList();
            assertTrue(names.stream().noneMatch(n -> n.contains("com/example/internal")));

            // The internal entries are present as UUID blobs under META-INF/vauban/
            var blobCount = names.stream().filter(n -> n.startsWith(SjarMetadata.BLOB_DIR)).count();
            assertEquals(2, blobCount); // Impl.class + data.bin
        }
    }

    @Test
    void internalEntriesAreDecryptableViaIndex() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        var originalClass = readEntryBytes(jarPath, "com/example/internal/Impl.class");
        var originalRes = readEntryBytes(jarPath, "com/example/internal/data.bin");

        SjarEncryptor.encryptJar(jarPath, key, "test-key");

        try (var jar = new JarFile(jarPath.toFile())) {
            // Decrypt the index to find the UUID mapping
            var indexBytes = readJarEntry(jar, SjarMetadata.INDEX_ENTRY);
            var index = SjarMetadata.readFrom(
                    new java.io.ByteArrayInputStream(SjarEncryptor.decryptBytes(indexBytes, key)));

            var classMeta = index.entries().get("com/example/internal/Impl.class");
            assertEquals("class", classMeta.kind());
            var classBlob = readJarEntry(jar, SjarMetadata.BLOB_DIR + classMeta.uuid());
            assertArrayEquals(originalClass, SjarEncryptor.decryptBytes(classBlob, key));

            var resMeta = index.entries().get("com/example/internal/data.bin");
            assertEquals("resource", resMeta.kind());
            var resBlob = readJarEntry(jar, SjarMetadata.BLOB_DIR + resMeta.uuid());
            assertArrayEquals(originalRes, SjarEncryptor.decryptBytes(resBlob, key));
        }
    }

    @Test
    void nonModularJarThrows() {
        var jarPath = tempDir.resolve("plain.jar");
        assertThrows(Exception.class, () -> {
            createPlainJar(jarPath);
            SjarEncryptor.encryptJar(jarPath, SjarKeyProvider.generateKey(), "key");
        });
    }

    @Test
    void shouldEncryptLogic() {
        var clearPackages = Set.of("com/example/api", "com/example/spi");

        // Internal class — obfuscate
        assertTrue(SjarEncryptor.shouldEncrypt("com/example/internal/Impl.class", clearPackages));
        // Internal resource — obfuscate too (v2)
        assertTrue(SjarEncryptor.shouldEncrypt("com/example/internal/data.bin", clearPackages));

        // Exported class — keep clear
        assertFalse(SjarEncryptor.shouldEncrypt("com/example/api/Service.class", clearPackages));
        // Exported-package resource — keep clear
        assertFalse(SjarEncryptor.shouldEncrypt("com/example/api/messages.properties", clearPackages));

        // module-info — never
        assertFalse(SjarEncryptor.shouldEncrypt("module-info.class", clearPackages));
        // META-INF — never
        assertFalse(SjarEncryptor.shouldEncrypt("META-INF/beans.xml", clearPackages));
        // default package — never
        assertFalse(SjarEncryptor.shouldEncrypt("Foo.class", clearPackages));
    }

    @Test
    void parseModuleInfoExtractsExportsAndOpens() throws Exception {
        var jarPath = createModularJar();
        try (var jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getEntry("module-info.class");
            try (var is = jar.getInputStream(entry)) {
                var result = SjarEncryptor.parseModuleInfo(is.readAllBytes());
                assertEquals("com.example.mylib", result.moduleName());
                assertTrue(result.clearPackages().contains("com/example/api"));
                assertFalse(result.clearPackages().contains("com/example/internal"));
            }
        }
    }

    private Path createModularJar() throws Exception {
        var jarPath = tempDir.resolve("modular.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            // module-info.class with exports com.example.api
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(buildModuleInfo("com.example.mylib", "com/example/api"));
            jos.closeEntry();

            // Exported class
            jos.putNextEntry(new JarEntry("com/example/api/Service.class"));
            jos.write(fakeClassBytes());
            jos.closeEntry();

            // Internal class
            jos.putNextEntry(new JarEntry("com/example/internal/Impl.class"));
            jos.write(fakeClassBytes());
            jos.closeEntry();

            // Internal resource (must also be obfuscated in v2)
            jos.putNextEntry(new JarEntry("com/example/internal/data.bin"));
            jos.write(new byte[]{10, 20, 30, 40, 50});
            jos.closeEntry();

            // Resource
            jos.putNextEntry(new JarEntry("META-INF/beans.xml"));
            jos.write("<beans/>".getBytes());
            jos.closeEntry();
        }
        return jarPath;
    }

    private void createPlainJar(Path jarPath) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jos.putNextEntry(new JarEntry("com/example/Foo.class"));
            jos.write(fakeClassBytes());
            jos.closeEntry();
        }
    }

    private byte[] readEntryBytes(Path jarPath, String entryName) throws Exception {
        try (var jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getEntry(entryName);
            try (var is = jar.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    private byte[] readJarEntry(JarFile jar, String name) throws Exception {
        try (var is = jar.getInputStream(jar.getEntry(name))) {
            return is.readAllBytes();
        }
    }

    private byte[] buildModuleInfo(String moduleName, String... exportedPackages) {
        return ClassFile.of().buildModule(
                java.lang.classfile.attribute.ModuleAttribute.of(
                        java.lang.constant.ModuleDesc.of(moduleName),
                        mb -> {
                            mb.requires(java.lang.constant.ModuleDesc.of("java.base"), 0, null);
                            for (var pkg : exportedPackages) {
                                mb.exports(java.lang.constant.PackageDesc.ofInternalName(pkg), 0);
                            }
                        }),
                mb -> {}
        );
    }

    @Test
    void subpackageOfExportedIsEncrypted() {
        var clearPackages = java.util.Set.of("com/example/api");
        // exported package itself stays clear
        assertFalse(SjarEncryptor.shouldEncrypt("com/example/api/Service.class", clearPackages));
        // a subpackage is NOT exported by Java Modules — must be obfuscated
        assertTrue(SjarEncryptor.shouldEncrypt("com/example/api/sub/Helper.class", clearPackages));
    }

    private byte[] fakeClassBytes() {
        return new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x02,
                0x07, 0x00, 0x01,
                0x00, 0x21, 0x00, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
    }
}
