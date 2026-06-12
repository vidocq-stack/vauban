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
import io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import io.vidocq.vauban.classloader.spi.PluginContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Plugin that handles SJAR archives with encrypted internal classes and resources.
 * Detects the clear {@code META-INF/vauban.header} marker of a v2 SJAR to determine
 * if a JAR carries an encrypted index and opaque blobs.
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
            return jar.getEntry(SjarHeader.HEADER_ENTRY) != null;
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
