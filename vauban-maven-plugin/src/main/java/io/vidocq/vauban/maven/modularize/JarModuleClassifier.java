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

import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Classifies a jar using the JDK's own {@link ModuleFinder}: the derived automatic
 * name is therefore exactly the one javac and the runtime would compute.
 */
public final class JarModuleClassifier {

    private JarModuleClassifier() {}

    /**
     * The descriptor the platform reads from {@code jar}, explicit or derived.
     *
     * <p>{@link ModuleFinder#findAll()} signals a jar whose automatic name cannot be derived from
     * its file name ({@code 1weird-1.0.jar} — a leading digit is no Java identifier) with an
     * <em>unchecked</em> {@link FindException}. Left alone it escapes {@code modularize} as an
     * internal error naming no jar, so it is turned into the {@link IOException} this class already
     * promises. Single entry point on purpose: {@code UsesLegality} reads descriptors the same way.
     *
     * @throws IOException if the jar is not a module and no automatic name can be derived for it
     */
    static ModuleDescriptor descriptorOf(Path jar) throws IOException {
        try {
            return ModuleFinder.of(jar).findAll().stream()
                    .findFirst()
                    .orElseThrow(() -> new IOException("Not a module or automatic module: " + jar))
                    .descriptor();
        } catch (FindException e) {
            throw new IOException("Cannot derive a module descriptor for "
                    + jar.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public static JarModuleInfo classify(Path jar) throws IOException {
        ModuleDescriptor md = descriptorOf(jar);
        JarModuleInfo.Kind kind;
        if (!md.isAutomatic()) {
            kind = JarModuleInfo.Kind.EXPLICIT;
        } else {
            kind = hasAutomaticModuleName(jar) ? JarModuleInfo.Kind.AUTOMATIC_NAMED
                    : JarModuleInfo.Kind.AUTOMATIC_DERIVED;
        }
        return new JarModuleInfo(jar, kind, md.name(), md.packages());
    }

    private static boolean hasAutomaticModuleName(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            var mf = jf.getManifest();
            return mf != null && mf.getMainAttributes().getValue("Automatic-Module-Name") != null;
        }
    }
}
