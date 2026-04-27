package io.vidocq.vauban.classloader.spi;

import java.io.IOException;
import java.nio.file.Path;

/**
 * SPI for custom byte source providers. Implementations handle specific archive
 * formats (e.g., encrypted JARs) and provide decrypted/transformed class bytes.
 *
 * <p>Discovered via {@link java.util.ServiceLoader}.</p>
 */
public interface ByteSourcePlugin {

    /**
     * Unique protocol name for this plugin (e.g., "sjar", "remote").
     */
    String protocol();

    /**
     * Whether this plugin can handle the given archive path.
     */
    boolean handles(Path archivePath);

    /**
     * Open the archive and return a reader for its entries.
     *
     * @param archivePath path to the archive file
     * @param context     provides key material and configuration
     * @return a reader for the archive entries
     * @throws IOException if the archive cannot be opened
     */
    ArchiveReader open(Path archivePath, PluginContext context) throws IOException;

    /**
     * Priority for ordering when multiple plugins match.
     * Lower values have higher priority. Default is 1000.
     */
    default int priority() {
        return 1000;
    }
}
