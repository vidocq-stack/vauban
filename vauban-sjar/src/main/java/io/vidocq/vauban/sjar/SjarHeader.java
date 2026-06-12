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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The clear {@code META-INF/vauban.header} bootstrap file of a v2 SJAR.
 *
 * <p>Carries only the minimum needed to locate the key and the index:
 * format version, algorithm, and the logical {@code keyAlias}. It never
 * contains a class or package name.
 */
public final class SjarHeader {

    public static final String HEADER_ENTRY = "META-INF/vauban.header";

    private final String keyAlias;

    public SjarHeader(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    public String keyAlias() { return keyAlias; }
    public int version() { return SjarMetadata.VERSION; }

    public void writeTo(OutputStream out) throws IOException {
        var json = "{\n"
                + "  \"version\": " + SjarMetadata.VERSION + ",\n"
                + "  \"algorithm\": \"" + SjarMetadata.ALGORITHM + "\",\n"
                + "  \"keyAlias\": \"" + escapeJson(keyAlias) + "\"\n"
                + "}\n";
        out.write(json.getBytes(StandardCharsets.UTF_8));
    }

    public static SjarHeader readFrom(InputStream in) throws IOException {
        var json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new SjarHeader(extractStringValue(json, "keyAlias"));
    }

    private static String extractStringValue(String json, String key) {
        var keyPattern = "\"" + key + "\"";
        var idx = json.indexOf(keyPattern);
        if (idx < 0) return "";
        var colonIdx = json.indexOf(':', idx + keyPattern.length());
        var quoteStart = json.indexOf('"', colonIdx + 1);
        var quoteEnd = json.indexOf('"', quoteStart + 1);
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
