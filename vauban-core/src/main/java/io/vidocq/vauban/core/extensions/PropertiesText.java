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
package io.vidocq.vauban.core.extensions;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.TreeSet;

/**
 * The text of {@code .properties} files Vauban generates at build: escaped as {@link Properties#store(java.io.Writer, String)}
 * escapes, without the date it writes as a comment and with the keys sorted, so two builds of the same sources write
 * the same bytes and a build tool sees no change. What {@link Properties#load(java.io.Reader)} reads back is the same.
 */
final class PropertiesText {

    private PropertiesText() {}

    /** One {@code key=value} line, ended by {@code \n}, escaped as {@link Properties#store} escapes it. */
    static String entry(String key, String value) {
        var one = new Properties();
        one.setProperty(key, value);
        var stored = new StringWriter();
        try {
            one.store(stored, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var out = new StringBuilder();
        stored.toString().lines().filter(line -> !line.startsWith("#")).forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    /** The {@code header} comment line, then every entry of {@code properties}, keys sorted. */
    static String sorted(String header, Properties properties) {
        var out = new StringBuilder("# ").append(header).append('\n');
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            out.append(entry(key, properties.getProperty(key)));
        }
        return out.toString();
    }
}
