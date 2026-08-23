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

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * What a {@link ClassTransformerPlugin} can know about the archive a class comes from,
 * <em>without</em> the container: the loader precedes the container, so there is no bean
 * index and no discovery here — only the archive itself and lightweight metadata.
 */
public interface ArchiveContext {

    /** The archive path (jar, sjar, exploded directory). */
    Path archivePath();

    /** Entry-level access to the archive (already decrypted for sjar sources). */
    ArchiveReader reader();

    /**
     * The parsed {@code META-INF/vauban-beans.list} of this archive (binary names), or
     * empty when the archive was not APT-processed. Transformers use it as their O(1)
     * pre-filter.
     */
    Optional<Set<String>> beansList();

    /**
     * Resolves the class-file bytes of {@code binaryName} from this archive or its
     * siblings known to the loader, or {@code null} when unknown. Lets a transformer
     * inspect superclasses and annotations without loading any class.
     */
    byte[] classBytes(String binaryName);
}
