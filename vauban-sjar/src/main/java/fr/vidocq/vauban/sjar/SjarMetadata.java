package fr.vidocq.vauban.sjar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the SJAR-METADATA.json file inside a .sjar archive.
 * Minimal JSON serialization without external dependencies.
 */
public final class SjarMetadata {

    static final String METADATA_ENTRY = "META-INF/SJAR-METADATA.json";
    static final int VERSION = 1;
    static final String ALGORITHM = "AES/GCM/NoPadding";
    static final int KEY_LENGTH = 256;
    static final int IV_LENGTH = 12;
    static final int TAG_LENGTH = 128;

    private final String keyAlias;
    private final Map<String, EntryMetadata> entries;

    public SjarMetadata(String keyAlias, Map<String, EntryMetadata> entries) {
        this.keyAlias = keyAlias;
        this.entries = Map.copyOf(entries);
    }

    public String keyAlias() {
        return keyAlias;
    }

    public Map<String, EntryMetadata> entries() {
        return entries;
    }

    public record EntryMetadata(byte[] iv, int originalSize) {
        public String ivBase64() {
            return Base64.getEncoder().encodeToString(iv);
        }
    }

    /**
     * Serialize to JSON (minimal, no dependencies).
     */
    public void writeTo(OutputStream out) throws IOException {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(VERSION).append(",\n");
        sb.append("  \"algorithm\": \"").append(ALGORITHM).append("\",\n");
        sb.append("  \"keyLength\": ").append(KEY_LENGTH).append(",\n");
        sb.append("  \"keyAlias\": \"").append(escapeJson(keyAlias)).append("\",\n");
        sb.append("  \"ivLength\": ").append(IV_LENGTH).append(",\n");
        sb.append("  \"tagLength\": ").append(TAG_LENGTH).append(",\n");
        sb.append("  \"entries\": {\n");

        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var meta = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {");
            sb.append(" \"iv\": \"").append(meta.ivBase64()).append("\",");
            sb.append(" \"originalSize\": ").append(meta.originalSize());
            sb.append(" }");
            if (it.hasNext()) sb.append(",");
            sb.append("\n");
        }

        sb.append("  }\n");
        sb.append("}\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse from JSON (minimal parser, handles only our format).
     */
    public static SjarMetadata readFrom(InputStream in) throws IOException {
        var json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        var keyAlias = extractStringValue(json, "keyAlias");
        var entries = new LinkedHashMap<String, EntryMetadata>();

        var entriesStart = json.indexOf("\"entries\"");
        if (entriesStart >= 0) {
            var braceStart = json.indexOf('{', entriesStart + 9);
            var braceEnd = findMatchingBrace(json, braceStart);
            var entriesBlock = json.substring(braceStart + 1, braceEnd);

            // Parse each entry
            int pos = 0;
            while (pos < entriesBlock.length()) {
                var nameStart = entriesBlock.indexOf('"', pos);
                if (nameStart < 0) break;
                var nameEnd = entriesBlock.indexOf('"', nameStart + 1);
                var entryName = entriesBlock.substring(nameStart + 1, nameEnd);

                var entryBraceStart = entriesBlock.indexOf('{', nameEnd);
                var entryBraceEnd = entriesBlock.indexOf('}', entryBraceStart);
                var entryBlock = entriesBlock.substring(entryBraceStart, entryBraceEnd + 1);

                var iv = Base64.getDecoder().decode(extractStringValue(entryBlock, "iv"));
                var originalSize = extractIntValue(entryBlock, "originalSize");

                entries.put(entryName, new EntryMetadata(iv, originalSize));
                pos = entryBraceEnd + 1;
            }
        }

        return new SjarMetadata(keyAlias, entries);
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
