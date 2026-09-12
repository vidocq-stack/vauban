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
package io.vidocq.vauban.maven.modularize;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the modularized copies produced by {@code vauban:modularize}
 * ({@code target/vauban-modularized/<original file name>}). The dev, jlink and
 * package goals call {@link #resolve} for every dependency so a patched jar wins
 * over the original M2 file whenever one exists — same pattern as the sealed
 * cassini copies and {@link JpmsPatches}.
 */
public final class ModularizedJars {

    /** Directory name under {@code target/}. */
    public static final String DIR_NAME = "vauban-modularized";

    private ModularizedJars() {}

    /** {@code target/vauban-modularized}, existing or not. */
    public static Path root(Path buildDir) {
        return buildDir.resolve(DIR_NAME);
    }

    /** The modularized copy of {@code originalJar} if present, otherwise {@code originalJar}. */
    public static Path resolve(Path buildDir, Path originalJar) {
        Path candidate = root(buildDir).resolve(originalJar.getFileName().toString());
        return Files.isRegularFile(candidate) ? candidate : originalJar;
    }

    /** Whether a modularized copy exists for {@code originalJar}. */
    public static boolean isModularized(Path buildDir, Path originalJar) {
        return !resolve(buildDir, originalJar).equals(originalJar);
    }
}
