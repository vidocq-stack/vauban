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
