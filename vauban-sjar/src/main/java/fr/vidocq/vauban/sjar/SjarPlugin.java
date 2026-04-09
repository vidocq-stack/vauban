package fr.vidocq.vauban.sjar;

import fr.vidocq.vauban.classloader.spi.ArchiveReader;
import fr.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import fr.vidocq.vauban.classloader.spi.PluginContext;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Plugin for Secure JAR (.sjar) files with AES-256-GCM encrypted class entries.
 */
public final class SjarPlugin implements ByteSourcePlugin {

    static final String SJAR_EXTENSION = ".sjar";

    @Override
    public String protocol() {
        return "sjar";
    }

    @Override
    public boolean handles(Path archivePath) {
        return archivePath.getFileName().toString().endsWith(SJAR_EXTENSION);
    }

    @Override
    public ArchiveReader open(Path archivePath, PluginContext context) throws IOException {
        return new SjarArchiveReader(archivePath, context);
    }

    @Override
    public int priority() {
        return 100;
    }
}
