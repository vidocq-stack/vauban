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
package io.vidocq.vauban.indexer.scanner;

import io.vidocq.vauban.indexer.model.ClassInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public final class JarScanner {

    private JarScanner() {}

    public static List<ClassInfo> scan(Path jarPath) throws IOException {
        var classes = new ArrayList<ClassInfo>();
        try (var jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (name.endsWith(".class") && !entry.isDirectory()
                        && !name.equals("module-info.class") && !name.endsWith("/module-info.class")) {
                    try (var is = jar.getInputStream(entry)) {
                        var bytes = is.readAllBytes();
                        classes.add(ClassFileScanner.scan(bytes));
                    } catch (Exception | Error e) {
                        // Skip malformed class files and module-info
                    }
                }
            }
        }
        return classes;
    }

    /**
     * Scan an archive using byte source plugins for custom formats (e.g., encrypted SJARs).
     * Falls back to standard JAR scanning if no plugin handles the path.
     *
     * @param archivePath path to the archive
     * @param plugins     list of plugins to try (ordered by priority)
     * @param context     plugin context providing keys and configuration
     * @return list of discovered class metadata
     */
    public static List<ClassInfo> scan(Path archivePath,
                                       List<io.vidocq.vauban.classloader.spi.ByteSourcePlugin> plugins,
                                       io.vidocq.vauban.classloader.spi.PluginContext context) throws IOException {
        if (plugins != null) {
            for (var plugin : plugins) {
                if (plugin.handles(archivePath)) {
                    return scanWithPlugin(archivePath, plugin, context);
                }
            }
        }
        return scan(archivePath);
    }

    private static List<ClassInfo> scanWithPlugin(
            Path archivePath,
            io.vidocq.vauban.classloader.spi.ByteSourcePlugin plugin,
            io.vidocq.vauban.classloader.spi.PluginContext context) throws IOException {
        var classes = new ArrayList<ClassInfo>();
        try (var reader = plugin.open(archivePath, context)) {
            for (var entry : reader.classEntries()) {
                if (entry.equals("module-info.class") || entry.endsWith("/module-info.class")) {
                    continue;
                }
                try {
                    var bytes = reader.readClass(entry);
                    classes.add(ClassFileScanner.scan(bytes));
                } catch (Exception | Error e) {
                    // Skip malformed class files
                }
            }
        }
        return classes;
    }
}
