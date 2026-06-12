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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory model of the decrypted {@code META-INF/vauban.index} blob.
 *
 * <p>Maps each obfuscated original entry path (class or resource of a
 * non-exported package) to the UUID under which its encrypted bytes are stored
 * in {@code META-INF/vauban/<uuid>}, plus the per-entry GCM IV, original size,
 * and kind. The mapping only ever exists inside the encrypted index, never in
 * clear text.
 */
public final class SjarMetadata {

    /** Encrypted index blob entry name. */
    public static final String INDEX_ENTRY = "META-INF/vauban.index";
    /** Directory holding the UUID-named encrypted blobs. */
    public static final String BLOB_DIR = "META-INF/vauban/";

    static final int VERSION = 2;
    static final String ALGORITHM = "AES/GCM/NoPadding";
    static final int KEY_LENGTH = 256;
    static final int IV_LENGTH = 12;
    static final int TAG_LENGTH = 128;

    private final Map<String, EntryMetadata> entries;
    private final Set<String> clearPackages;
    private final String moduleName;

    public SjarMetadata(Map<String, EntryMetadata> entries,
                        Set<String> clearPackages, String moduleName) {
        this.entries = Map.copyOf(entries);
        this.clearPackages = Set.copyOf(clearPackages);
        this.moduleName = moduleName;
    }

    public Map<String, EntryMetadata> entries() { return entries; }
    public Set<String> clearPackages() { return clearPackages; }
    public String moduleName() { return moduleName; }

    /**
     * @param uuid         blob file name under {@link #BLOB_DIR}
     * @param iv           per-entry 12-byte GCM IV
     * @param originalSize plaintext size in bytes
     * @param kind         {@code "class"} or {@code "resource"}
     */
    public record EntryMetadata(String uuid, byte[] iv, int originalSize, String kind) {
        public String ivBase64() {
            return Base64.getEncoder().encodeToString(iv);
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(VERSION).append(",\n");
        sb.append("  \"moduleName\": \"").append(escapeJson(moduleName)).append("\",\n");

        sb.append("  \"clearPackages\": [");
        var cpIt = clearPackages.iterator();
        while (cpIt.hasNext()) {
            sb.append("\"").append(escapeJson(cpIt.next())).append("\"");
            if (cpIt.hasNext()) sb.append(", ");
        }
        sb.append("],\n");

        sb.append("  \"entries\": {\n");
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var meta = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {");
            sb.append(" \"uuid\": \"").append(escapeJson(meta.uuid())).append("\",");
            sb.append(" \"iv\": \"").append(meta.ivBase64()).append("\",");
            sb.append(" \"originalSize\": ").append(meta.originalSize()).append(",");
            sb.append(" \"kind\": \"").append(escapeJson(meta.kind())).append("\"");
            sb.append(" }");
            if (it.hasNext()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static SjarMetadata readFrom(InputStream in) throws IOException {
        var json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        var moduleName = extractStringValue(json, "moduleName");

        var clearPackages = new LinkedHashSet<String>();
        var cpStart = json.indexOf("\"clearPackages\"");
        if (cpStart >= 0) {
            var bracketStart = json.indexOf('[', cpStart);
            var bracketEnd = json.indexOf(']', bracketStart);
            var block = json.substring(bracketStart + 1, bracketEnd);
            int pos = 0;
            while (pos < block.length()) {
                var qs = block.indexOf('"', pos);
                if (qs < 0) break;
                var qe = block.indexOf('"', qs + 1);
                clearPackages.add(block.substring(qs + 1, qe));
                pos = qe + 1;
            }
        }

        var entries = new LinkedHashMap<String, EntryMetadata>();
        var entriesStart = json.indexOf("\"entries\"");
        if (entriesStart >= 0) {
            var braceStart = json.indexOf('{', entriesStart + "\"entries\"".length());
            var braceEnd = findMatchingBrace(json, braceStart);
            var entriesBlock = json.substring(braceStart + 1, braceEnd);

            int pos = 0;
            while (pos < entriesBlock.length()) {
                var nameStart = entriesBlock.indexOf('"', pos);
                if (nameStart < 0) break;
                var nameEnd = entriesBlock.indexOf('"', nameStart + 1);
                var entryName = entriesBlock.substring(nameStart + 1, nameEnd);

                var entryBraceStart = entriesBlock.indexOf('{', nameEnd);
                var entryBraceEnd = entriesBlock.indexOf('}', entryBraceStart);
                var entryBlock = entriesBlock.substring(entryBraceStart, entryBraceEnd + 1);

                var uuid = extractStringValue(entryBlock, "uuid");
                var iv = Base64.getDecoder().decode(extractStringValue(entryBlock, "iv"));
                var originalSize = extractIntValue(entryBlock, "originalSize");
                var kind = extractStringValue(entryBlock, "kind");

                entries.put(entryName, new EntryMetadata(uuid, iv, originalSize, kind));
                pos = entryBraceEnd + 1;
            }
        }

        return new SjarMetadata(entries, clearPackages, moduleName);
    }

    private static String extractStringValue(String json, String key) {
        var keyPattern = "\"" + key + "\"";
        var idx = json.indexOf(keyPattern);
        if (idx < 0) return "";
        var colonIdx = json.indexOf(':', idx + keyPattern.length());
        if (colonIdx < 0) return "";
        var quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return "";
        var quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return "";
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static int extractIntValue(String json, String key) {
        var keyPattern = "\"" + key + "\"";
        var idx = json.indexOf(keyPattern);
        if (idx < 0) return 0;
        var colonIdx = json.indexOf(':', idx + keyPattern.length());
        var start = colonIdx + 1;
        while (start < json.length() && !Character.isDigit(json.charAt(start))) start++;
        var end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    private static int findMatchingBrace(String json, int openBrace) {
        int depth = 1;
        for (int i = openBrace + 1; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return json.length() - 1;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
