package fr.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void readsClassEntriesFromSjar() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createTestJarWithClasses();
        var sjarPath = tempDir.resolve("test.sjar");

        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(sjarPath, ctx)) {
            var entries = reader.classEntries();
            assertFalse(entries.isEmpty());
            assertTrue(entries.stream().allMatch(e -> e.endsWith(".class")));
            assertTrue(entries.stream().noneMatch(e -> e.contains(".enc")));
        }
    }

    @Test
    void decryptsClassBytesCorrectly() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var classBytes = new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x41, 0x00, 0x02,
                0x07, 0x00, 0x01,
                0x00, 0x21, 0x00, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        var jarPath = createJarWithBytes("com/example/Foo.class", classBytes);
        var sjarPath = tempDir.resolve("test.sjar");

        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(sjarPath, ctx)) {
            var decrypted = reader.readClass("com/example/Foo.class");
            assertArrayEquals(classBytes, decrypted);
        }
    }

    @Test
    void cachesDecryptedBytes() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createTestJarWithClasses();
        var sjarPath = tempDir.resolve("test.sjar");

        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(sjarPath, ctx)) {
            var entries = reader.classEntries();
            var firstEntry = entries.getFirst();

            // Read twice — should return same bytes (cached)
            var bytes1 = reader.readClass(firstEntry);
            var bytes2 = reader.readClass(firstEntry);
            assertSame(bytes1, bytes2, "Cache should return the same array instance");
        }
    }

    @Test
    void readsNonClassResources() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createTestJarWithClasses();
        var sjarPath = tempDir.resolve("test.sjar");

        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(sjarPath, ctx)) {
            var resource = reader.readResource("META-INF/beans.xml");
            assertTrue(resource.isPresent());
            assertEquals("<beans/>", new String(resource.get()));
        }
    }

    @Test
    void missingMetadataThrows() {
        var badJar = tempDir.resolve("bad.sjar");
        assertThrows(Exception.class, () -> {
            // Create a plain JAR renamed to .sjar (no metadata)
            var manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            try (var jos = new JarOutputStream(Files.newOutputStream(badJar), manifest)) {
                jos.putNextEntry(new JarEntry("dummy.txt"));
                jos.write("test".getBytes());
                jos.closeEntry();
            }
            var ctx = SjarKeyProvider.withKey(SjarKeyProvider.generateKey());
            new SjarArchiveReader(badJar, ctx);
        });
    }

    private Path createTestJarWithClasses() throws Exception {
        var jarPath = tempDir.resolve("input.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jos.putNextEntry(new JarEntry("com/example/Foo.class"));
            jos.write(fakeClassBytes());
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/Bar.class"));
            jos.write(fakeClassBytes());
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/beans.xml"));
            jos.write("<beans/>".getBytes());
            jos.closeEntry();
        }
        return jarPath;
    }

    private Path createJarWithBytes(String entryName, byte[] bytes) throws Exception {
        var jarPath = tempDir.resolve("custom.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(bytes);
            jos.closeEntry();
        }
        return jarPath;
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
