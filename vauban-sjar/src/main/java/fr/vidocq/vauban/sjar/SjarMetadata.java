package fr.vidocq.vauban.sjar;

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
 * Represents the {@code META-INF/vauban.encrypted} marker file inside a JAR.
 * Contains encryption metadata: which entries are encrypted, which packages are clear,
 * and the module name.
 */
public final class SjarMetadata {

    public static final String METADATA_ENTRY = "META-INF/vauban.encrypted";
    static final int VERSION = 1;
    static final String ALGORITHM = "AES/GCM/NoPadding";
    static final int KEY_LENGTH = 256;
    static final int IV_LENGTH = 12;
    static final int TAG_LENGTH = 128;

    private final String keyAlias;
    private final Map<String, EntryMetadata> entries;
    private final Set<String> clearPackages;
    private final String moduleName;

    public SjarMetadata(String keyAlias, Map<String, EntryMetadata> entries,
                        Set<String> clearPackages, String moduleName) {
        this.keyAlias = keyAlias;
        this.entries = Map.copyOf(entries);
        this.clearPackages = Set.copyOf(clearPackages);
        this.moduleName = moduleName;
    }

    public String keyAlias() { return keyAlias; }
    public Map<String, EntryMetadata> entries() { return entries; }
    public Set<String> clearPackages() { return clearPackages; }
    public String moduleName() { return moduleName; }

    public record EntryMetadata(byte[] iv, int originalSize, String originalEntry) {
        public String ivBase64() {
            return Base64.getEncoder().encodeToString(iv);
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(VERSION).append(",\n");
        sb.append("  \"algorithm\": \"").append(ALGORITHM).append("\",\n");
        sb.append("  \"keyAlias\": \"").append(escapeJson(keyAlias)).append("\",\n");
        sb.append("  \"moduleName\": \"").append(escapeJson(moduleName)).append("\",\n");

        // Clear packages
        sb.append("  \"clearPackages\": [");
        var cpIt = clearPackages.iterator();
        while (cpIt.hasNext()) {
            sb.append("\"").append(escapeJson(cpIt.next())).append("\"");
            if (cpIt.hasNext()) sb.append(", ");
        }
        sb.append("],\n");

        // Encrypted entries
        sb.append("  \"encryptedEntries\": {\n");
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var meta = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {");
            sb.append(" \"iv\": \"").append(meta.ivBase64()).append("\",");
            sb.append(" \"originalSize\": ").append(meta.originalSize()).append(",");
            sb.append(" \"originalEntry\": \"").append(escapeJson(meta.originalEntry())).append("\"");
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
        var keyAlias = extractStringValue(json, "keyAlias");
        var moduleName = extractStringValue(json, "moduleName");

        // Parse clearPackages array
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

        // Parse encryptedEntries
        var entries = new LinkedHashMap<String, EntryMetadata>();
        var entriesStart = json.indexOf("\"encryptedEntries\"");
        if (entriesStart >= 0) {
            var braceStart = json.indexOf('{', entriesStart + 18);
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

                var iv = Base64.getDecoder().decode(extractStringValue(entryBlock, "iv"));
                var originalSize = extractIntValue(entryBlock, "originalSize");
                var originalEntry = extractStringValue(entryBlock, "originalEntry");

                entries.put(entryName, new EntryMetadata(iv, originalSize, originalEntry));
                pos = entryBraceEnd + 1;
            }
        }

        return new SjarMetadata(keyAlias, entries, clearPackages, moduleName);
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
