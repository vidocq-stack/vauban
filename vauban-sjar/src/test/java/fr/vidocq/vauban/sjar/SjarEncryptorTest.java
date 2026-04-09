package fr.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        var plaintext = "Hello CDI World!".getBytes();
        var iv = SjarEncryptor.generateIv();

        var encrypted = SjarEncryptor.encryptBytes(plaintext, key, iv);
        assertNotEquals(new String(plaintext), new String(encrypted));

        // Encrypted format: [12B IV][ciphertext+tag]
        assertEquals(SjarMetadata.IV_LENGTH + plaintext.length + SjarMetadata.TAG_LENGTH / 8, encrypted.length);

        var decrypted = SjarEncryptor.decryptBytes(encrypted, key);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void encryptJarToSjar() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createTestJar();
        var sjarPath = tempDir.resolve("test.sjar");

        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");

        assertTrue(Files.exists(sjarPath));
        assertTrue(Files.size(sjarPath) > 0);

        // Verify SJAR structure
        try (var sjar = new JarFile(sjarPath.toFile())) {
            // Metadata must exist
            assertNotNull(sjar.getEntry(SjarMetadata.METADATA_ENTRY));

            // Original .class entries should NOT exist
            assertNull(sjar.getEntry("com/example/TestBean.class"));

            // Encrypted entries should exist
            assertNotNull(sjar.getEntry("com/example/TestBean.class.enc"));

            // Non-class resources should be preserved as-is
            assertNotNull(sjar.getEntry("META-INF/beans.xml"));
        }
    }

    @Test
    void encryptedClassesAreDecryptable() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createTestJar();
        var sjarPath = tempDir.resolve("test.sjar");

        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");

        // Read metadata and verify we can decrypt
        try (var sjar = new JarFile(sjarPath.toFile())) {
            var metaEntry = sjar.getEntry(SjarMetadata.METADATA_ENTRY);
            SjarMetadata metadata;
            try (var is = sjar.getInputStream(metaEntry)) {
                metadata = SjarMetadata.readFrom(is);
            }

            assertEquals("test-key", metadata.keyAlias());
            assertFalse(metadata.entries().isEmpty());

            // Decrypt each entry
            for (var entry : metadata.entries().entrySet()) {
                var encEntry = sjar.getEntry(entry.getKey());
                assertNotNull(encEntry, "Missing encrypted entry: " + entry.getKey());

                try (var is = sjar.getInputStream(encEntry)) {
                    var encrypted = is.readAllBytes();
                    var decrypted = SjarEncryptor.decryptBytes(encrypted, key);
                    assertEquals(entry.getValue().originalSize(), decrypted.length);
                }
            }
        }
    }

    @Test
    void wrongKeyFailsDecryption() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var wrongKey = SjarKeyProvider.generateKey();
        var plaintext = "secret data".getBytes();
        var iv = SjarEncryptor.generateIv();

        var encrypted = SjarEncryptor.encryptBytes(plaintext, key, iv);

        assertThrows(Exception.class, () ->
                SjarEncryptor.decryptBytes(encrypted, wrongKey));
    }

    @Test
    void generateIvProducesUniqueValues() {
        var iv1 = SjarEncryptor.generateIv();
        var iv2 = SjarEncryptor.generateIv();
        assertEquals(SjarMetadata.IV_LENGTH, iv1.length);
        assertEquals(SjarMetadata.IV_LENGTH, iv2.length);
        assertFalse(java.util.Arrays.equals(iv1, iv2));
    }

    private Path createTestJar() throws Exception {
        var jarPath = tempDir.resolve("test.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            // Add a fake class entry
            jos.putNextEntry(new JarEntry("com/example/TestBean.class"));
            jos.write(createMinimalClassBytes());
            jos.closeEntry();

            // Add a resource
            jos.putNextEntry(new JarEntry("META-INF/beans.xml"));
            jos.write("<beans/>".getBytes());
            jos.closeEntry();
        }
        return jarPath;
    }

    private byte[] createMinimalClassBytes() {
        // Minimal valid class file header (not a real class, but enough bytes for encryption test)
        return new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, // magic
                0x00, 0x00, 0x00, 0x41, // version 65 (Java 21)
                0x00, 0x07, // constant pool count
                // Minimal constant pool entries
                0x07, 0x00, 0x02, // Class -> #2
                0x01, 0x00, 0x17, // Utf8 "com/example/TestBean"
                'c', 'o', 'm', '/', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '/',
                'T', 'e', 's', 't', 'B', 'e', 'a', 'n', 'C', 'l', 'a',
                0x07, 0x00, 0x04, // Class -> #4
                0x01, 0x00, 0x10, // Utf8 "java/lang/Object"
                'j', 'a', 'v', 'a', '/', 'l', 'a', 'n', 'g', '/', 'O', 'b', 'j', 'e', 'c', 't',
                0x01, 0x00, 0x06, // Utf8 "<init>"
                '<', 'i', 'n', 'i', 't', '>',
                0x01, 0x00, 0x03, // Utf8 "()V"
                '(', ')', 'V',
                // Access flags, this class, super class
                0x00, 0x21, // ACC_PUBLIC | ACC_SUPER
                0x00, 0x01, // this_class -> #1
                0x00, 0x03, // super_class -> #3
                0x00, 0x00, // interfaces count
                0x00, 0x00, // fields count
                0x00, 0x00, // methods count
                0x00, 0x00  // attributes count
        };
    }
}
