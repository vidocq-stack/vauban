package fr.vidocq.vauban.sjar;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Encrypts a standard JAR into a Secure JAR (.sjar) with AES-256-GCM encryption.
 */
public final class SjarEncryptor {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SjarEncryptor() {}

    /**
     * Encrypt a JAR file into an SJAR file.
     *
     * @param inputJar  path to the source JAR
     * @param outputSjar path to the output SJAR
     * @param key        AES-256 secret key
     * @param keyAlias   key alias stored in metadata
     */
    public static void encrypt(Path inputJar, Path outputSjar, SecretKey key, String keyAlias)
            throws IOException, GeneralSecurityException {

        var entries = new LinkedHashMap<String, SjarMetadata.EntryMetadata>();

        try (var jar = new JarFile(inputJar.toFile());
             var zipOut = new ZipOutputStream(java.nio.file.Files.newOutputStream(outputSjar))) {

            // Write manifest
            var manifest = jar.getManifest();
            if (manifest == null) manifest = new Manifest();
            manifest.getMainAttributes().putValue("Vauban-Encryption", SjarMetadata.ALGORITHM);
            manifest.getMainAttributes().putValue("Vauban-Key-Alias", keyAlias);

            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zipOut);
            zipOut.closeEntry();

            // Process all entries
            var jarEntries = jar.entries();
            while (jarEntries.hasMoreElements()) {
                var entry = jarEntries.nextElement();
                var name = entry.getName();

                // Skip manifest (already written) and directories
                if (name.equals("META-INF/MANIFEST.MF") || entry.isDirectory()) continue;

                try (var is = jar.getInputStream(entry)) {
                    var bytes = is.readAllBytes();

                    if (name.endsWith(".class")) {
                        // Encrypt class files
                        var encryptedName = name + ".enc";
                        var iv = generateIv();
                        var encryptedBytes = encryptBytes(bytes, key, iv);

                        entries.put(encryptedName, new SjarMetadata.EntryMetadata(iv, bytes.length));

                        zipOut.putNextEntry(new ZipEntry(encryptedName));
                        zipOut.write(encryptedBytes);
                        zipOut.closeEntry();
                    } else {
                        // Copy non-class resources as-is
                        zipOut.putNextEntry(new ZipEntry(name));
                        zipOut.write(bytes);
                        zipOut.closeEntry();
                    }
                }
            }

            // Write metadata
            var metadata = new SjarMetadata(keyAlias, entries);
            zipOut.putNextEntry(new ZipEntry(SjarMetadata.METADATA_ENTRY));
            var metadataBytes = new ByteArrayOutputStream();
            metadata.writeTo(metadataBytes);
            zipOut.write(metadataBytes.toByteArray());
            zipOut.closeEntry();
        }
    }

    static byte[] encryptBytes(byte[] plaintext, SecretKey key, byte[] iv)
            throws GeneralSecurityException {
        var cipher = Cipher.getInstance(SjarMetadata.ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(SjarMetadata.TAG_LENGTH, iv));
        var ciphertext = cipher.doFinal(plaintext);

        // Format: [IV][ciphertext + GCM tag]
        var result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        return result;
    }

    static byte[] decryptBytes(byte[] encrypted, SecretKey key) throws GeneralSecurityException {
        var iv = new byte[SjarMetadata.IV_LENGTH];
        System.arraycopy(encrypted, 0, iv, 0, iv.length);

        var ciphertext = new byte[encrypted.length - iv.length];
        System.arraycopy(encrypted, iv.length, ciphertext, 0, ciphertext.length);

        var cipher = Cipher.getInstance(SjarMetadata.ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(SjarMetadata.TAG_LENGTH, iv));
        return cipher.doFinal(ciphertext);
    }

    static byte[] generateIv() {
        var iv = new byte[SjarMetadata.IV_LENGTH];
        RANDOM.nextBytes(iv);
        return iv;
    }
}
