package io.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class SjarArchiveReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsAllClassEntries() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        SjarEncryptor.encryptJar(jarPath, key, "test");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var entries = reader.classEntries();
            // Should have both clear and encrypted entries (as original names)
            assertTrue(entries.contains("com/example/api/Service.class"));
            assertTrue(entries.contains("com/example/internal/Impl.class"));
            assertFalse(entries.stream().anyMatch(e -> e.contains(".enc")));
        }
    }

    @Test
    void decryptsEncryptedClass() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        var originalBytes = readEntry(jarPath, "com/example/internal/Impl.class");

        SjarEncryptor.encryptJar(jarPath, key, "test");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var decrypted = reader.readClass("com/example/internal/Impl.class");
            assertArrayEquals(originalBytes, decrypted);
        }
    }

    @Test
    void readsClearClassDirectly() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        var originalBytes = readEntry(jarPath, "com/example/api/Service.class");

        SjarEncryptor.encryptJar(jarPath, key, "test");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var bytes = reader.readClass("com/example/api/Service.class");
            assertArrayEquals(originalBytes, bytes);
        }
    }

    @Test
    void readsModuleInfo() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();

        SjarEncryptor.encryptJar(jarPath, key, "test");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var moduleInfo = reader.moduleInfo();
            assertTrue(moduleInfo.isPresent());
        }
    }

    @Test
    void cachesDecryptedBytes() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        SjarEncryptor.encryptJar(jarPath, key, "test");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var b1 = reader.readClass("com/example/internal/Impl.class");
            var b2 = reader.readClass("com/example/internal/Impl.class");
            assertSame(b1, b2);
        }
    }

    private Path createModularJar() throws Exception {
        var jarPath = tempDir.resolve("modular.jar");
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
            jos.write(fakeClassBytes());
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/internal/Impl.class"));
            jos.write(fakeClassBytes());
            jos.closeEntry();
        }
        return jarPath;
    }

    private byte[] readEntry(Path jarPath, String name) throws Exception {
        try (var jar = new java.util.jar.JarFile(jarPath.toFile())) {
            try (var is = jar.getInputStream(jar.getEntry(name))) {
                return is.readAllBytes();
            }
        }
    }

    private byte[] fakeClassBytes() {
        return new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x02, 0x07, 0x00, 0x01,
                0x00, 0x21, 0x00, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
    }
}
