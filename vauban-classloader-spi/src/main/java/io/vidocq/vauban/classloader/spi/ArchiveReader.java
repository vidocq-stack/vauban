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
