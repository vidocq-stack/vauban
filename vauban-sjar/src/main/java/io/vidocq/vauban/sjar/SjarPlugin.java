package io.vidocq.vauban.sjar;

import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import io.vidocq.vauban.classloader.spi.PluginContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Plugin that handles JARs with encrypted internal classes.
 * Detects the {@code META-INF/vauban.encrypted} marker file to determine
 * if a JAR contains encrypted classes.
 */
public final class SjarPlugin implements ByteSourcePlugin {

    @Override
    public String protocol() {
        return "vauban-encrypted";
    }

    @Override
    public boolean handles(Path archivePath) {
        var fileName = archivePath.getFileName().toString();
        if (!fileName.endsWith(".jar") && !fileName.endsWith(".sjar")) return false;

        // Check for the encryption marker inside the JAR
        try (var jar = new JarFile(archivePath.toFile())) {
            return jar.getEntry(SjarMetadata.METADATA_ENTRY) != null;
        } catch (IOException e) {
            return false;
        }
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
