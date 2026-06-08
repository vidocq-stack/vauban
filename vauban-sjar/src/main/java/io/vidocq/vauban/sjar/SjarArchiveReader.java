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

import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.PluginContext;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Reads a JAR with encrypted internal classes (marked by {@code META-INF/vauban.encrypted}).
 * Clear-text classes are read normally; encrypted classes ({@code .class.enc}) are decrypted on demand.
 */
public final class SjarArchiveReader implements ArchiveReader {

    private final JarFile jarFile;
    private final SjarMetadata metadata;
    private final SecretKey key;
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    public SjarArchiveReader(Path jarPath, PluginContext context) throws IOException {
        this.jarFile = new JarFile(jarPath.toFile());

        var metadataEntry = jarFile.getEntry(SjarMetadata.METADATA_ENTRY);
        if (metadataEntry == null) {
            jarFile.close();
            throw new IOException("Not an encrypted JAR — missing " + SjarMetadata.METADATA_ENTRY);
        }
        try (var is = jarFile.getInputStream(metadataEntry)) {
            this.metadata = SjarMetadata.readFrom(is);
        }

        this.key = context.resolveKey(metadata.keyAlias());
    }

    @Override
    public List<String> classEntries() throws IOException {
        var result = new ArrayList<String>();
        var entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            var name = entry.getName();

            if (name.endsWith(".class") && !name.equals("module-info.class")
                    && !name.startsWith("META-INF/")) {
                // Clear-text class
                result.add(name);
            } else if (name.endsWith(".class.enc")) {
                // Encrypted class — return as the original .class name
                var meta = metadata.entries().get(name);
                if (meta != null) {
                    result.add(meta.originalEntry());
                }
            }
        }
        return result;
    }

    @Override
    public byte[] readClass(String entryName) throws IOException {
        return cache.computeIfAbsent(entryName, name -> {
            try {
                // Try clear-text first
                var entry = jarFile.getEntry(name);
                if (entry != null) {
                    try (var is = jarFile.getInputStream(entry)) {
                        return is.readAllBytes();
                    }
                }

                // Try encrypted
                var encName = name + ".enc";
                var encEntry = jarFile.getEntry(encName);
                if (encEntry != null) {
                    try (var is = jarFile.getInputStream(encEntry)) {
                        return SjarEncryptor.decryptBytes(is.readAllBytes(), key);
                    } catch (GeneralSecurityException e) {
                        throw new IOException("Failed to decrypt " + encName, e);
                    }
                }

                throw new IOException("Class entry not found: " + name);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Override
    public Optional<byte[]> readResource(String entryName) throws IOException {
        var entry = jarFile.getEntry(entryName);
        if (entry == null) return Optional.empty();
        try (var is = jarFile.getInputStream(entry)) {
            return Optional.of(is.readAllBytes());
        }
    }

    @Override
    public Optional<byte[]> moduleInfo() throws IOException {
        var entry = jarFile.getEntry("module-info.class");
        if (entry == null) return Optional.empty();
        try (var is = jarFile.getInputStream(entry)) {
            return Optional.of(is.readAllBytes());
        }
    }

    public SjarMetadata metadata() {
        return metadata;
    }

    @Override
    public void close() throws IOException {
        cache.clear();
        jarFile.close();
    }
}
