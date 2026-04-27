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
    void encryptJarInPlace() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();

        SjarEncryptor.encryptJar(jarPath, key, "test-key");

        // Verify JAR structure
        try (var jar = new JarFile(jarPath.toFile())) {
            // Marker must exist
            assertNotNull(jar.getEntry(SjarMetadata.METADATA_ENTRY));

            // module-info stays clear
            assertNotNull(jar.getEntry("module-info.class"));

            // Exported class stays clear
            assertNotNull(jar.getEntry("com/example/api/Service.class"));

            // Internal class is encrypted
            assertNull(jar.getEntry("com/example/internal/Impl.class"));
            assertNotNull(jar.getEntry("com/example/internal/Impl.class.enc"));
        }
    }

    @Test
    void encryptedClassIsDecryptable() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        var originalBytes = readEntryBytes(jarPath, "com/example/internal/Impl.class");

        SjarEncryptor.encryptJar(jarPath, key, "test-key");

        try (var jar = new JarFile(jarPath.toFile())) {
            var encEntry = jar.getEntry("com/example/internal/Impl.class.enc");
            try (var is = jar.getInputStream(encEntry)) {
                var decrypted = SjarEncryptor.decryptBytes(is.readAllBytes(), key);
                assertArrayEquals(originalBytes, decrypted);
            }
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

        // Internal class — encrypt
        assertTrue(SjarEncryptor.shouldEncrypt("com/example/internal/Impl.class", clearPackages));

        // Exported class — don't encrypt
        assertFalse(SjarEncryptor.shouldEncrypt("com/example/api/Service.class", clearPackages));

        // module-info — never encrypt
        assertFalse(SjarEncryptor.shouldEncrypt("module-info.class", clearPackages));

        // META-INF — never encrypt
        assertFalse(SjarEncryptor.shouldEncrypt("META-INF/beans.xml", clearPackages));

        // Non-class — never encrypt
        assertFalse(SjarEncryptor.shouldEncrypt("com/example/internal/data.txt", clearPackages));
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
