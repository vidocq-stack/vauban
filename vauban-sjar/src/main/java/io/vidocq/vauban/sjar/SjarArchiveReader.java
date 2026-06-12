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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Reads a v2 SJAR: a clear {@code META-INF/vauban.header} bootstraps the key,
 * the encrypted {@code META-INF/vauban.index} maps each internal entry path to
 * a UUID blob under {@code META-INF/vauban/}, and exported entries stay clear.
 */
public final class SjarArchiveReader implements ArchiveReader {

    private final JarFile jarFile;
    private final SjarMetadata index;
    private final SecretKey key;
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    public SjarArchiveReader(Path jarPath, PluginContext context) throws IOException {
        this.jarFile = new JarFile(jarPath.toFile());

        var headerEntry = jarFile.getEntry(SjarHeader.HEADER_ENTRY);
        if (headerEntry == null) {
            jarFile.close();
            throw new IOException("Not a v2 encrypted JAR — missing " + SjarHeader.HEADER_ENTRY);
        }
        SjarHeader header;
        try (var is = jarFile.getInputStream(headerEntry)) {
            header = SjarHeader.readFrom(is);
        }
        this.key = context.resolveKey(header.keyAlias());

        var indexEntry = jarFile.getEntry(SjarMetadata.INDEX_ENTRY);
        if (indexEntry == null) {
            jarFile.close();
            throw new IOException("Corrupt SJAR — missing " + SjarMetadata.INDEX_ENTRY);
        }
        try (var is = jarFile.getInputStream(indexEntry)) {
            var decrypted = SjarEncryptor.decryptBytes(is.readAllBytes(), key);
            this.index = SjarMetadata.readFrom(new ByteArrayInputStream(decrypted));
        } catch (GeneralSecurityException e) {
            jarFile.close();
            throw new IOException("Failed to decrypt " + SjarMetadata.INDEX_ENTRY, e);
        }
    }

    @Override
    public List<String> classEntries() throws IOException {
        var result = new ArrayList<String>();
        // Clear classes (exported packages)
        var entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            var name = entries.nextElement().getName();
            if (name.endsWith(".class") && !name.equals("module-info.class")
                    && !name.startsWith("META-INF/")) {
                result.add(name);
            }
        }
        // Encrypted internal classes (from the index, original paths)
        for (var e : index.entries().entrySet()) {
            if ("class".equals(e.getValue().kind())) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    @Override
    public byte[] readClass(String entryName) throws IOException {
        return readEntry(entryName);
    }

    @Override
    public Optional<byte[]> readResource(String entryName) throws IOException {
        // Clear resource present directly?
        var entry = jarFile.getEntry(entryName);
        if (entry != null) {
            try (var is = jarFile.getInputStream(entry)) {
                return Optional.of(is.readAllBytes());
            }
        }
        // Encrypted internal resource via index?
        if (index.entries().containsKey(entryName)) {
            return Optional.of(readEntry(entryName));
        }
        return Optional.empty();
    }

    private byte[] readEntry(String entryName) throws IOException {
        try {
            return cache.computeIfAbsent(entryName, name -> {
                try {
                    // Clear entry?
                    var entry = jarFile.getEntry(name);
                    if (entry != null) {
                        try (var is = jarFile.getInputStream(entry)) {
                            return is.readAllBytes();
                        }
                    }
                    // Encrypted via index?
                    var meta = index.entries().get(name);
                    if (meta != null) {
                        var blob = jarFile.getEntry(SjarMetadata.BLOB_DIR + meta.uuid());
                        if (blob == null) {
                            throw new IOException("Missing blob for " + name + " (uuid " + meta.uuid() + ")");
                        }
                        try (var is = jarFile.getInputStream(blob)) {
                            return SjarEncryptor.decryptBytes(is.readAllBytes(), key);
                        } catch (GeneralSecurityException e) {
                            throw new IOException("Failed to decrypt " + name, e);
                        }
                    }
                    throw new IOException("Entry not found: " + name);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
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

    public SjarMetadata index() {
        return index;
    }

    @Override
    public void close() throws IOException {
        cache.clear();
        jarFile.close();
    }
}
