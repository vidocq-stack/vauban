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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Encrypts internal classes of a modular JAR in-place using AES-256-GCM.
 *
 * <p>Classes in {@code exports} and {@code opens} packages (from {@code module-info.class})
 * remain in clear text for compilation. All other classes are encrypted and renamed
 * to {@code .class.enc}. A {@code META-INF/vauban.encrypted} marker file stores metadata.
 *
 * <p>The result is a standard JAR that is compilable (exported types visible),
 * distributable via Maven, and has internal implementation classes protected.
 */
public final class SjarEncryptor {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SjarEncryptor() {}

    /**
     * Encrypt a modular JAR in-place. Classes in exported/opened packages stay clear;
     * all other classes are encrypted.
     *
     * @param jarPath  path to the modular JAR (overwritten in-place)
     * @param key      AES-256 secret key
     * @param keyAlias key alias stored in metadata
     * @throws IllegalArgumentException if the JAR has no module-info.class
     */
    public static void encryptJar(Path jarPath, SecretKey key, String keyAlias)
            throws IOException, GeneralSecurityException {

        Set<String> clearPackages;
        String moduleName;

        // 1. Parse module-info.class to determine clear packages
        try (var jar = new JarFile(jarPath.toFile())) {
            var moduleEntry = jar.getEntry("module-info.class");
            if (moduleEntry == null) {
                throw new IllegalArgumentException(
                        "JAR has no module-info.class — only modular JARs can be encrypted: " + jarPath);
            }
            try (var is = jar.getInputStream(moduleEntry)) {
                var result = parseModuleInfo(is.readAllBytes());
                clearPackages = result.clearPackages();
                moduleName = result.moduleName();
            }
        }

        // 2. Build encrypted JAR into temp file
        var tempJar = Files.createTempFile("vauban-enc-", ".jar");
        var encryptedEntries = new LinkedHashMap<String, SjarMetadata.EntryMetadata>();

        try (var src = new JarFile(jarPath.toFile());
             var out = new JarOutputStream(Files.newOutputStream(tempJar))) {

            var srcEntries = src.entries();
            while (srcEntries.hasMoreElements()) {
                var entry = srcEntries.nextElement();
                var name = entry.getName();

                if (entry.isDirectory()) {
                    out.putNextEntry(new JarEntry(name));
                    out.closeEntry();
                    continue;
                }

                // Skip existing metadata marker — it will be rewritten below
                if (name.equals(SjarMetadata.METADATA_ENTRY)) continue;

                try (var is = src.getInputStream(entry)) {
                    var bytes = is.readAllBytes();

                    if (shouldEncrypt(name, clearPackages)) {
                        var encName = name + ".enc";
                        var iv = generateIv();
                        var encrypted = encryptBytes(bytes, key, iv);

                        encryptedEntries.put(encName,
                                new SjarMetadata.EntryMetadata(iv, bytes.length, name));

                        out.putNextEntry(new ZipEntry(encName));
                        out.write(encrypted);
                        out.closeEntry();
                    } else {
                        out.putNextEntry(new ZipEntry(name));
                        out.write(bytes);
                        out.closeEntry();
                    }
                }
            }

            // Write encryption marker
            var metadata = new SjarMetadata(keyAlias, encryptedEntries, clearPackages, moduleName);
            out.putNextEntry(new ZipEntry(SjarMetadata.METADATA_ENTRY));
            var metaBytes = new ByteArrayOutputStream();
            metadata.writeTo(metaBytes);
            out.write(metaBytes.toByteArray());
            out.closeEntry();
        }

        // 3. Replace original JAR
        Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Legacy method: encrypt a JAR into a separate output file (for backward compatibility).
     */
    public static void encrypt(Path inputJar, Path outputJar, SecretKey key, String keyAlias)
            throws IOException, GeneralSecurityException {
        Files.copy(inputJar, outputJar, StandardCopyOption.REPLACE_EXISTING);
        encryptJar(outputJar, key, keyAlias);
    }

    static boolean shouldEncrypt(String entryName, Set<String> clearPackages) {
        // Never encrypt non-class files, module-info, or META-INF
        if (!entryName.endsWith(".class")) return false;
        if (entryName.equals("module-info.class")) return false;
        if (entryName.startsWith("META-INF/")) return false;

        // Determine the package of this class
        var lastSlash = entryName.lastIndexOf('/');
        if (lastSlash < 0) return false; // default package — don't encrypt
        var packagePath = entryName.substring(0, lastSlash);

        // Check if the package (or a parent) is in clear packages
        return !clearPackages.contains(packagePath);
    }

    record ModuleInfoResult(String moduleName, Set<String> clearPackages) {}

    static ModuleInfoResult parseModuleInfo(byte[] moduleInfoBytes) {
        var classModel = ClassFile.of().parse(moduleInfoBytes);
        var clearPackages = new LinkedHashSet<String>();
        String moduleName = "unknown";

        for (var attr : classModel.attributes()) {
            if (attr instanceof ModuleAttribute ma) {
                moduleName = ma.moduleName().name().stringValue();
                for (var export : ma.exports()) {
                    clearPackages.add(export.exportedPackage().name().stringValue());
                }
                for (var open : ma.opens()) {
                    clearPackages.add(open.openedPackage().name().stringValue());
                }
            }
        }

        return new ModuleInfoResult(moduleName, clearPackages);
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
