package io.vidocq.vauban.classloader.spi;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Abstraction over an archive (JAR, SJAR, etc.) that yields class entry bytes.
 * Implementations handle decryption, decompression, or other transformations transparently.
 */
public interface ArchiveReader extends AutoCloseable {

    /**
     * List all class entries as internal names (e.g., "com/example/Foo.class").
     */
    List<String> classEntries() throws IOException;

    /**
     * Read the (decrypted) bytes for a class entry.
     *
     * @param entryName internal name (e.g., "com/example/Foo.class")
     * @return the class file bytes
     * @throws IOException if the entry cannot be read or decrypted
     */
    byte[] readClass(String entryName) throws IOException;

    /**
     * Read any resource entry (for META-INF/*, etc.).
     *
     * @param entryName resource path within the archive
     * @return the resource bytes, or empty if not found
     * @throws IOException if the entry cannot be read
     */
    Optional<byte[]> readResource(String entryName) throws IOException;

    /**
     * Module descriptor bytes if this is a modular archive.
     *
     * @return module-info.class bytes, or empty if not a modular archive
     */
    default Optional<byte[]> moduleInfo() throws IOException {
        return Optional.empty();
    }

    @Override
    void close() throws IOException;
}
