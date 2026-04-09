package fr.vidocq.vauban.sjar;

import fr.vidocq.vauban.classloader.spi.ArchiveReader;
import fr.vidocq.vauban.classloader.spi.PluginContext;

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
 * Reads an SJAR archive, decrypting class entries on demand with caching.
 */
public final class SjarArchiveReader implements ArchiveReader {

    private final JarFile jarFile;
    private final SjarMetadata metadata;
    private final SecretKey key;
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    SjarArchiveReader(Path sjarPath, PluginContext context) throws IOException {
        this.jarFile = new JarFile(sjarPath.toFile());

        // Read metadata
        var metadataEntry = jarFile.getEntry(SjarMetadata.METADATA_ENTRY);
        if (metadataEntry == null) {
            jarFile.close();
            throw new IOException("Missing SJAR metadata: " + SjarMetadata.METADATA_ENTRY);
        }
        try (var is = jarFile.getInputStream(metadataEntry)) {
            this.metadata = SjarMetadata.readFrom(is);
        }

        // Resolve decryption key
        this.key = context.resolveKey(metadata.keyAlias());
    }

    @Override
    public List<String> classEntries() throws IOException {
        var result = new ArrayList<String>();
        for (var encName : metadata.entries().keySet()) {
            // Convert "com/example/Foo.class.enc" -> "com/example/Foo.class"
            if (encName.endsWith(".class.enc")) {
                result.add(encName.substring(0, encName.length() - 4)); // remove ".enc"
            }
        }
        return result;
    }

    @Override
    public byte[] readClass(String entryName) throws IOException {
        return cache.computeIfAbsent(entryName, name -> {
            try {
                return decryptEntry(name + ".enc");
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
        var encEntry = "module-info.class.enc";
        if (metadata.entries().containsKey(encEntry)) {
            return Optional.of(decryptEntry(encEntry));
        }
        return Optional.empty();
    }

    @Override
    public void close() throws IOException {
        cache.clear();
        jarFile.close();
    }

    private byte[] decryptEntry(String encryptedEntryName) throws IOException {
        var entry = jarFile.getEntry(encryptedEntryName);
        if (entry == null) {
            throw new IOException("Missing encrypted entry: " + encryptedEntryName);
        }
        try (var is = jarFile.getInputStream(entry)) {
            var encrypted = is.readAllBytes();
            return SjarEncryptor.decryptBytes(encrypted, key);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to decrypt " + encryptedEntryName, e);
        }
    }
}
