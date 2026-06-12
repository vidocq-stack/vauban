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
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Encrypts internal classes and resources of a modular JAR in-place using AES-256-GCM.
 *
 * <p>Entries of {@code exports}/{@code opens} packages (from {@code module-info.class})
 * stay clear for compilation and reflection. Every other class and resource is encrypted
 * and stored under an opaque {@code META-INF/vauban/<uuid>} name; the path→UUID mapping
 * lives only inside the encrypted {@code META-INF/vauban.index}. A tiny clear
 * {@code META-INF/vauban.header} carries the key alias to bootstrap decryption.
 *
 * <p>The result is a standard JAR: compilable (exported types visible), distributable
 * via Maven, with internal implementation fully opaque on disk.
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

        // 2. Build the opaque JAR into a temp file
        var tempJar = Files.createTempFile("vauban-enc-", ".jar");
        var indexEntries = new LinkedHashMap<String, SjarMetadata.EntryMetadata>();

        try (var src = new JarFile(jarPath.toFile());
             var out = new JarOutputStream(Files.newOutputStream(tempJar))) {

            var srcEntries = src.entries();
            while (srcEntries.hasMoreElements()) {
                var entry = srcEntries.nextElement();
                var name = entry.getName();

                if (entry.isDirectory()) {
                    // Skip internal-package directories so they leave no trace;
                    // keep META-INF/ and exported-package dirs as-is.
                    if (isInternalDirectory(name, clearPackages)) continue;
                    out.putNextEntry(new JarEntry(name));
                    out.closeEntry();
                    continue;
                }

                // Drop any stale v1 marker if re-encrypting
                if (name.equals(SjarHeader.HEADER_ENTRY) || name.equals(SjarMetadata.INDEX_ENTRY)
                        || name.startsWith(SjarMetadata.BLOB_DIR)) {
                    continue;
                }

                try (var is = src.getInputStream(entry)) {
                    var bytes = is.readAllBytes();

                    if (shouldEncrypt(name, clearPackages)) {
                        var uuid = UUID.randomUUID().toString();
                        var iv = generateIv();
                        var encrypted = encryptBytes(bytes, key, iv);
                        var kind = name.endsWith(".class") ? "class" : "resource";

                        indexEntries.put(name,
                                new SjarMetadata.EntryMetadata(uuid, iv, bytes.length, kind));

                        out.putNextEntry(new ZipEntry(SjarMetadata.BLOB_DIR + uuid));
                        out.write(encrypted);
                        out.closeEntry();
                    } else {
                        out.putNextEntry(new ZipEntry(name));
                        out.write(bytes);
                        out.closeEntry();
                    }
                }
            }

            // 3. Write the encrypted index (self-describing [IV][ct+tag] blob)
            var index = new SjarMetadata(indexEntries, clearPackages, moduleName);
            var indexPlain = new ByteArrayOutputStream();
            index.writeTo(indexPlain);
            var indexIv = generateIv();
            var indexBlob = encryptBytes(indexPlain.toByteArray(), key, indexIv);
            out.putNextEntry(new ZipEntry(SjarMetadata.INDEX_ENTRY));
            out.write(indexBlob);
            out.closeEntry();

            // 4. Write the clear bootstrap header
            var header = new SjarHeader(keyAlias);
            var headerBytes = new ByteArrayOutputStream();
            header.writeTo(headerBytes);
            out.putNextEntry(new ZipEntry(SjarHeader.HEADER_ENTRY));
            out.write(headerBytes.toByteArray());
            out.closeEntry();
        }

        // 5. Replace original JAR
        Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isInternalDirectory(String dirName, Set<String> clearPackages) {
        if (dirName.startsWith("META-INF/")) return false;
        var pkg = dirName.endsWith("/") ? dirName.substring(0, dirName.length() - 1) : dirName;
        if (pkg.isEmpty()) return false;
        // A directory is internal if no clear package equals or is nested under it,
        // and it is not itself a clear package.
        if (clearPackages.contains(pkg)) return false;
        for (var clear : clearPackages) {
            if (clear.equals(pkg) || clear.startsWith(pkg + "/")) return false;
        }
        return true;
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
        // Never obfuscate module-info or META-INF
        if (entryName.equals("module-info.class")) return false;
        if (entryName.startsWith("META-INF/")) return false;

        // Determine the package/directory of this entry (class OR resource)
        var lastSlash = entryName.lastIndexOf('/');
        if (lastSlash < 0) return false; // default package — keep clear
        var packagePath = entryName.substring(0, lastSlash);

        // Obfuscate only if the package is not exported/opened
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
