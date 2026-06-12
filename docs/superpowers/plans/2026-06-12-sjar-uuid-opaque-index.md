# SJAR opaque packaging (UUID entries + encrypted index) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a packaged SJAR reveal no internal package/class name on disk by renaming every internal class/resource to a UUID blob under `META-INF/vauban/`, with the name→UUID mapping stored only inside an encrypted `META-INF/vauban.index`, bootstrapped by a tiny clear `META-INF/vauban.header`.

**Architecture:** Disk-only obfuscation. The classloader still defines classes under their real FQCN (no bytecode rewrite). The format moves from v1 (`*.class.enc` + clear `META-INF/vauban.encrypted` JSON that leaks every FQCN) to v2 (UUID blobs + encrypted index). v2 fully replaces v1 — no legacy read path.

**Tech Stack:** Java 25, `javax.crypto` (AES-256-GCM), `java.lang.classfile` (module-info parsing), `java.util.jar`, JUnit 5. Zero new dependencies — reuses the existing hand-rolled JSON in `SjarMetadata` and `SjarKeyProvider`.

**Working directory:** `cd /Users/yblazart/projects/perso/vidocq/vauban` then `sdk env` (Java 25 + Maven 3.9.16). All `mvn` commands below run from `vauban/`.

**Build/test command for this module:**
`./mvnw -q -pl vauban-classloader-spi,vauban-sjar -am test`
(run the whole module's tests; `-Dtest=ClassName#method` to target one — but per workspace memory, prefer `clean` if you hit stale `target/`: `./mvnw -q -pl vauban-sjar -am clean test`).

---

## File Structure

**Create:**
- `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarHeader.java` — clear bootstrap file (`META-INF/vauban.header`): `{version, algorithm, keyAlias}`. Read/write JSON.
- `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarHeaderTest.java` — header round-trip + no-secret assertions.
- `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarOpaqueFormatTest.java` — end-to-end: no internal name on disk, index opacity, class + resource round-trip.

**Modify:**
- `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarMetadata.java` — repurpose as the **decrypted index** content. New entry constants, `EntryMetadata` gains `uuid` + `kind`, map keyed by `originalPath`, new JSON shape.
- `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarEncryptor.java` — write v2: UUID blobs for all non-exposed entries (classes **and** resources), encrypted index + clear header.
- `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarArchiveReader.java` — read v2: load header → key → decrypt index → resolve UUID blobs.
- `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarPlugin.java` — detect `vauban.header` instead of `vauban.encrypted`.
- `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarMetadataTest.java` — new index shape.
- `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarEncryptorTest.java` — assert UUID blobs, not `.class.enc`.

**Unchanged (verify only):** `SjarKeyProvider`, `SjarClassLoader` (its `getResourceAsStream` already delegates internal lookups to `reader.readClass`/`reader.readResource`, which become index-aware), `SjarTool`, SPI interfaces, `module-info.java`.

---

## Task 1: Repurpose `SjarMetadata` into the encrypted index model

**Files:**
- Modify: `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarMetadata.java`
- Test: `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarMetadataTest.java`

This class becomes the in-memory model of the **decrypted** index JSON. The map is keyed by the original path; each entry records its `uuid` and `kind`. Format constants stay here (still imported by encryptor/reader).

- [ ] **Step 1: Rewrite the test for the new index shape**

Replace the whole body of `SjarMetadataTest.java` (keep the license header lines 1-19) with:

```java
package io.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SjarMetadataTest {

    @Test
    void roundTripIndexSerialization() throws Exception {
        var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        var entries = Map.of(
                "com/example/internal/Impl.class",
                new SjarMetadata.EntryMetadata("3f2a-uuid", iv, 1024, "class"),
                "com/example/internal/templates/x.json",
                new SjarMetadata.EntryMetadata("a87e-uuid", iv, 42, "resource")
        );
        var clearPackages = Set.of("com/example/api");
        var index = new SjarMetadata(entries, clearPackages, "com.example.mylib");

        var out = new ByteArrayOutputStream();
        index.writeTo(out);

        var parsed = SjarMetadata.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("com.example.mylib", parsed.moduleName());
        assertTrue(parsed.clearPackages().contains("com/example/api"));
        assertEquals(2, parsed.entries().size());

        var classEntry = parsed.entries().get("com/example/internal/Impl.class");
        assertNotNull(classEntry);
        assertEquals("3f2a-uuid", classEntry.uuid());
        assertEquals(1024, classEntry.originalSize());
        assertEquals("class", classEntry.kind());
        assertArrayEquals(iv, classEntry.iv());

        var resEntry = parsed.entries().get("com/example/internal/templates/x.json");
        assertEquals("resource", resEntry.kind());
        assertEquals("a87e-uuid", resEntry.uuid());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw -q -pl vauban-sjar -am test-compile`
Expected: COMPILE FAILURE — `SjarMetadata` constructor still takes `keyAlias`, `EntryMetadata` has no `uuid`/`kind`.

- [ ] **Step 3: Rewrite `SjarMetadata` to the index model**

Replace the class body of `SjarMetadata.java` (keep license header lines 1-19, `package`, and imports — adjust imports as needed) with:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarMetadataTest`
Expected: PASS (1 test). NOTE: `SjarEncryptor`/`SjarArchiveReader` still reference the old API and will break test-compile of the *module*; that's fixed in Tasks 3-4. To isolate this task, run `test-compile` will still fail on those classes — so compile just this file with `./mvnw -q -pl vauban-sjar -am compile` will ALSO fail. This is expected mid-refactor; the green checkpoint for Task 1 is that `SjarMetadataTest` logic matches the new model. Proceed to Tasks 2-4 before the module compiles again.

- [ ] **Step 5: Commit**

```bash
git add vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarMetadata.java \
        vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarMetadataTest.java
git commit -m "refactor(sjar): repurpose SjarMetadata as the v2 encrypted index model"
```

---

## Task 2: Add `SjarHeader` (clear bootstrap file)

**Files:**
- Create: `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarHeader.java`
- Test: `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarHeaderTest.java`

- [ ] **Step 1: Write the failing test**

Create `SjarHeaderTest.java` (prefix with the same license header as other files, lines 1-19 of any sibling):

```java
package io.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SjarHeaderTest {

    @Test
    void roundTrip() throws Exception {
        var header = new SjarHeader("vidocq-prod");
        var out = new ByteArrayOutputStream();
        header.writeTo(out);

        var parsed = SjarHeader.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("vidocq-prod", parsed.keyAlias());
        assertEquals(2, parsed.version());
    }

    @Test
    void carriesNoClassName() throws Exception {
        var header = new SjarHeader("vidocq-prod");
        var out = new ByteArrayOutputStream();
        header.writeTo(out);
        var json = out.toString();

        // header must contain only bootstrap fields, never a package/class path
        assertFalse(json.contains("internal"));
        assertFalse(json.contains(".class"));
        assertFalse(json.contains("uuid"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl vauban-sjar -am test-compile`
Expected: COMPILE FAILURE — `SjarHeader` does not exist.

- [ ] **Step 3: Create `SjarHeader`**

Create `SjarHeader.java` (license header + the following):

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarHeaderTest`
Expected: PASS (2 tests). (Module compile of `SjarEncryptor`/`SjarArchiveReader` still broken until Tasks 3-4 — if `-Dtest` filtering still triggers full module compile and fails, accept the red and continue; the header logic is correct.)

- [ ] **Step 5: Commit**

```bash
git add vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarHeader.java \
        vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarHeaderTest.java
git commit -m "feat(sjar): add clear SjarHeader bootstrap file for v2 format"
```

---

## Task 3: Rewrite `SjarEncryptor` to emit v2 (UUID blobs + encrypted index + header)

**Files:**
- Modify: `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarEncryptor.java`
- Test: `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarEncryptorTest.java`

This is the write side. `shouldEncrypt` now covers **any** entry (class or resource) of a non-exposed package. Each obfuscated entry becomes a `META-INF/vauban/<uuid>` blob.

- [ ] **Step 1: Update the encryptor tests for v2 layout**

In `SjarEncryptorTest.java`, replace the `encryptJarInPlace`, `encryptedClassIsDecryptable`, and `shouldEncryptLogic` tests with the versions below, and add an internal-resource entry to the `createModularJar()` fixture.

Replace `encryptJarInPlace`:

```java
    @Test
    void encryptJarInPlaceProducesOpaqueLayout() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();

        SjarEncryptor.encryptJar(jarPath, key, "test-key");

        try (var jar = new JarFile(jarPath.toFile())) {
            // Clear bootstrap header + encrypted index exist
            assertNotNull(jar.getEntry(SjarHeader.HEADER_ENTRY));
            assertNotNull(jar.getEntry(SjarMetadata.INDEX_ENTRY));

            // module-info + exported class stay clear
            assertNotNull(jar.getEntry("module-info.class"));
            assertNotNull(jar.getEntry("com/example/api/Service.class"));

            // Internal class/resource no longer visible under their real path
            assertNull(jar.getEntry("com/example/internal/Impl.class"));
            assertNull(jar.getEntry("com/example/internal/Impl.class.enc"));
            assertNull(jar.getEntry("com/example/internal/data.bin"));

            // No ZIP entry leaks an internal package path
            var names = jar.stream().map(java.util.zip.ZipEntry::getName).toList();
            assertTrue(names.stream().noneMatch(n -> n.contains("com/example/internal")));

            // The internal entries are present as UUID blobs under META-INF/vauban/
            var blobCount = names.stream().filter(n -> n.startsWith(SjarMetadata.BLOB_DIR)).count();
            assertEquals(2, blobCount); // Impl.class + data.bin
        }
    }
```

Replace `encryptedClassIsDecryptable`:

```java
    @Test
    void internalEntriesAreDecryptableViaIndex() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        var originalClass = readEntryBytes(jarPath, "com/example/internal/Impl.class");
        var originalRes = readEntryBytes(jarPath, "com/example/internal/data.bin");

        SjarEncryptor.encryptJar(jarPath, key, "test-key");

        try (var jar = new JarFile(jarPath.toFile())) {
            // Decrypt the index to find the UUID mapping
            var indexBytes = readJarEntry(jar, SjarMetadata.INDEX_ENTRY);
            var index = SjarMetadata.readFrom(
                    new java.io.ByteArrayInputStream(SjarEncryptor.decryptBytes(indexBytes, key)));

            var classMeta = index.entries().get("com/example/internal/Impl.class");
            assertEquals("class", classMeta.kind());
            var classBlob = readJarEntry(jar, SjarMetadata.BLOB_DIR + classMeta.uuid());
            assertArrayEquals(originalClass, SjarEncryptor.decryptBytes(classBlob, key));

            var resMeta = index.entries().get("com/example/internal/data.bin");
            assertEquals("resource", resMeta.kind());
            var resBlob = readJarEntry(jar, SjarMetadata.BLOB_DIR + resMeta.uuid());
            assertArrayEquals(originalRes, SjarEncryptor.decryptBytes(resBlob, key));
        }
    }
```

Replace `shouldEncryptLogic`:

```java
    @Test
    void shouldEncryptLogic() {
        var clearPackages = Set.of("com/example/api", "com/example/spi");

        // Internal class — obfuscate
        assertTrue(SjarEncryptor.shouldEncrypt("com/example/internal/Impl.class", clearPackages));
        // Internal resource — obfuscate too (v2)
        assertTrue(SjarEncryptor.shouldEncrypt("com/example/internal/data.bin", clearPackages));

        // Exported class — keep clear
        assertFalse(SjarEncryptor.shouldEncrypt("com/example/api/Service.class", clearPackages));
        // Exported-package resource — keep clear
        assertFalse(SjarEncryptor.shouldEncrypt("com/example/api/messages.properties", clearPackages));

        // module-info — never
        assertFalse(SjarEncryptor.shouldEncrypt("module-info.class", clearPackages));
        // META-INF — never
        assertFalse(SjarEncryptor.shouldEncrypt("META-INF/beans.xml", clearPackages));
        // default package — never
        assertFalse(SjarEncryptor.shouldEncrypt("Foo.class", clearPackages));
    }
```

In `createModularJar()`, add an internal resource entry right after the internal class entry (before the `META-INF/beans.xml` block):

```java
            // Internal resource (must also be obfuscated in v2)
            jos.putNextEntry(new JarEntry("com/example/internal/data.bin"));
            jos.write(new byte[]{10, 20, 30, 40, 50});
            jos.closeEntry();
```

Add this helper method to the test class (next to `readEntryBytes`):

```java
    private byte[] readJarEntry(JarFile jar, String name) throws Exception {
        try (var is = jar.getInputStream(jar.getEntry(name))) {
            return is.readAllBytes();
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl vauban-sjar -am test-compile`
Expected: COMPILE FAILURE — `SjarHeader.HEADER_ENTRY`/`SjarMetadata.INDEX_ENTRY`/new `EntryMetadata` signature not yet used by encryptor; `encryptJar` still writes v1.

- [ ] **Step 3: Rewrite `encryptJar` and `shouldEncrypt`**

In `SjarEncryptor.java`:

(a) Add the import for UUID near the other `java.util` imports:

```java
import java.util.UUID;
```

(b) Replace the body of `encryptJar` (the method from line ~67 to line ~141) with:

```java
    public static void encryptJar(Path jarPath, SecretKey key, String keyAlias)
            throws IOException, GeneralSecurityException {

        Set<String> clearPackages;
        String moduleName;

        // 1. Parse module-info.class to determine clear packages
        try (var jar = new JarFile(jarPath.toFile())) {
            var moduleEntry = jar.getEntry("module-info.class");
            if (moduleEntry == null) {
                throw new IllegalArgumentException(
                        "JAR has no module-info.class — only modular JARs can be encrypted: " + jarPath);
            }
            try (var is = jar.getInputStream(moduleEntry)) {
                var result = parseModuleInfo(is.readAllBytes());
                clearPackages = result.clearPackages();
                moduleName = result.moduleName();
            }
        }

        // 2. Build the opaque JAR into a temp file
        var tempJar = Files.createTempFile("vauban-enc-", ".jar");
        var indexEntries = new LinkedHashMap<String, SjarMetadata.EntryMetadata>();

        try (var src = new JarFile(jarPath.toFile());
             var out = new JarOutputStream(Files.newOutputStream(tempJar))) {

            var srcEntries = src.entries();
            while (srcEntries.hasMoreElements()) {
                var entry = srcEntries.nextElement();
                var name = entry.getName();

                if (entry.isDirectory()) {
                    // Skip internal-package directories so they leave no trace;
                    // keep META-INF/ and exported-package dirs as-is.
                    if (isInternalDirectory(name, clearPackages)) continue;
                    out.putNextEntry(new JarEntry(name));
                    out.closeEntry();
                    continue;
                }

                // Drop any stale v1 marker if re-encrypting
                if (name.equals(SjarHeader.HEADER_ENTRY) || name.equals(SjarMetadata.INDEX_ENTRY)
                        || name.startsWith(SjarMetadata.BLOB_DIR)) {
                    continue;
                }

                try (var is = src.getInputStream(entry)) {
                    var bytes = is.readAllBytes();

                    if (shouldEncrypt(name, clearPackages)) {
                        var uuid = UUID.randomUUID().toString();
                        var iv = generateIv();
                        var encrypted = encryptBytes(bytes, key, iv);
                        var kind = name.endsWith(".class") ? "class" : "resource";

                        indexEntries.put(name,
                                new SjarMetadata.EntryMetadata(uuid, iv, bytes.length, kind));

                        out.putNextEntry(new ZipEntry(SjarMetadata.BLOB_DIR + uuid));
                        out.write(encrypted);
                        out.closeEntry();
                    } else {
                        out.putNextEntry(new ZipEntry(name));
                        out.write(bytes);
                        out.closeEntry();
                    }
                }
            }

            // 3. Write the encrypted index (self-describing [IV][ct+tag] blob)
            var index = new SjarMetadata(indexEntries, clearPackages, moduleName);
            var indexPlain = new ByteArrayOutputStream();
            index.writeTo(indexPlain);
            var indexIv = generateIv();
            var indexBlob = encryptBytes(indexPlain.toByteArray(), key, indexIv);
            out.putNextEntry(new ZipEntry(SjarMetadata.INDEX_ENTRY));
            out.write(indexBlob);
            out.closeEntry();

            // 4. Write the clear bootstrap header
            var header = new SjarHeader(keyAlias);
            var headerBytes = new ByteArrayOutputStream();
            header.writeTo(headerBytes);
            out.putNextEntry(new ZipEntry(SjarHeader.HEADER_ENTRY));
            out.write(headerBytes.toByteArray());
            out.closeEntry();
        }

        // 5. Replace original JAR
        Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isInternalDirectory(String dirName, Set<String> clearPackages) {
        if (dirName.startsWith("META-INF/")) return false;
        var pkg = dirName.endsWith("/") ? dirName.substring(0, dirName.length() - 1) : dirName;
        if (pkg.isEmpty()) return false;
        // A directory is internal if no clear package equals or is nested under it,
        // and it is not itself a clear package.
        if (clearPackages.contains(pkg)) return false;
        for (var clear : clearPackages) {
            if (clear.equals(pkg) || clear.startsWith(pkg + "/")) return false;
        }
        return true;
    }
```

(c) Replace `shouldEncrypt` (lines ~152-165) with the v2 version that also obfuscates resources:

```java
    static boolean shouldEncrypt(String entryName, Set<String> clearPackages) {
        // Never obfuscate module-info or META-INF
        if (entryName.equals("module-info.class")) return false;
        if (entryName.startsWith("META-INF/")) return false;

        // Determine the package/directory of this entry (class OR resource)
        var lastSlash = entryName.lastIndexOf('/');
        if (lastSlash < 0) return false; // default package — keep clear
        var packagePath = entryName.substring(0, lastSlash);

        // Obfuscate only if the package is not exported/opened
        return !clearPackages.contains(packagePath);
    }
```

Note: the `encrypt(input, output, ...)` convenience method (lines ~146-150) is unchanged and still works.

- [ ] **Step 4: Run the encryptor tests**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarEncryptorTest`
Expected: PASS — all encryptor tests green (module-compile of `SjarArchiveReader` is fixed in Task 4; if the reader still fails to compile, run Task 4 first then re-run). If reader compile blocks this, proceed to Task 4 and run both test classes together at Task 4 Step 4.

- [ ] **Step 5: Commit**

```bash
git add vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarEncryptor.java \
        vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarEncryptorTest.java
git commit -m "feat(sjar): emit v2 opaque layout (UUID blobs + encrypted index + header)"
```

---

## Task 4: Rewrite `SjarArchiveReader` to read v2

**Files:**
- Modify: `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarArchiveReader.java`
- Test: `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarArchiveReaderTest.java` (existing tests should pass unchanged — they only use the public reader API)

- [ ] **Step 1: Add a resource round-trip test**

The existing `SjarArchiveReaderTest` tests (`readsAllClassEntries`, `decryptsEncryptedClass`, `readsClearClassDirectly`, `readsModuleInfo`, `cachesDecryptedBytes`) are the behavioral spec and must keep passing. Add one test for internal resource resolution. First add an internal resource to that test's `createModularJar()` (after the internal class entry):

```java
            jos.putNextEntry(new JarEntry("com/example/internal/data.bin"));
            jos.write(new byte[]{9, 8, 7, 6, 5});
            jos.closeEntry();
```

Then add the test:

```java
    @Test
    void readsInternalResourceViaIndex() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        var original = readEntry(jarPath, "com/example/internal/data.bin");

        SjarEncryptor.encryptJar(jarPath, key, "test");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var res = reader.readResource("com/example/internal/data.bin");
            assertTrue(res.isPresent());
            assertArrayEquals(original, res.get());
        }
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarArchiveReaderTest`
Expected: FAIL — current reader still looks for `META-INF/vauban.encrypted` (v1) and `*.class.enc`; with v2 jars the constructor throws "Not an encrypted JAR".

- [ ] **Step 3: Rewrite the reader for v2**

Replace the body of `SjarArchiveReader.java` (keep license header + package). New imports add `ByteArrayInputStream`:

```java
package io.vidocq.vauban.sjar;

import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.PluginContext;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Reads a v2 SJAR: a clear {@code META-INF/vauban.header} bootstraps the key,
 * the encrypted {@code META-INF/vauban.index} maps each internal entry path to
 * a UUID blob under {@code META-INF/vauban/}, and exported entries stay clear.
 */
public final class SjarArchiveReader implements ArchiveReader {

    private final JarFile jarFile;
    private final SjarMetadata index;
    private final SecretKey key;
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    public SjarArchiveReader(Path jarPath, PluginContext context) throws IOException {
        this.jarFile = new JarFile(jarPath.toFile());

        var headerEntry = jarFile.getEntry(SjarHeader.HEADER_ENTRY);
        if (headerEntry == null) {
            jarFile.close();
            throw new IOException("Not a v2 encrypted JAR — missing " + SjarHeader.HEADER_ENTRY);
        }
        SjarHeader header;
        try (var is = jarFile.getInputStream(headerEntry)) {
            header = SjarHeader.readFrom(is);
        }
        this.key = context.resolveKey(header.keyAlias());

        var indexEntry = jarFile.getEntry(SjarMetadata.INDEX_ENTRY);
        if (indexEntry == null) {
            jarFile.close();
            throw new IOException("Corrupt SJAR — missing " + SjarMetadata.INDEX_ENTRY);
        }
        try (var is = jarFile.getInputStream(indexEntry)) {
            var decrypted = SjarEncryptor.decryptBytes(is.readAllBytes(), key);
            this.index = SjarMetadata.readFrom(new ByteArrayInputStream(decrypted));
        } catch (GeneralSecurityException e) {
            jarFile.close();
            throw new IOException("Failed to decrypt " + SjarMetadata.INDEX_ENTRY, e);
        }
    }

    @Override
    public List<String> classEntries() throws IOException {
        var result = new ArrayList<String>();
        // Clear classes (exported packages)
        var entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            var name = entries.nextElement().getName();
            if (name.endsWith(".class") && !name.equals("module-info.class")
                    && !name.startsWith("META-INF/")) {
                result.add(name);
            }
        }
        // Encrypted internal classes (from the index, original paths)
        for (var e : index.entries().entrySet()) {
            if ("class".equals(e.getValue().kind())) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    @Override
    public byte[] readClass(String entryName) throws IOException {
        return readEntry(entryName);
    }

    @Override
    public Optional<byte[]> readResource(String entryName) throws IOException {
        // Clear resource present directly?
        var entry = jarFile.getEntry(entryName);
        if (entry != null) {
            try (var is = jarFile.getInputStream(entry)) {
                return Optional.of(is.readAllBytes());
            }
        }
        // Encrypted internal resource via index?
        if (index.entries().containsKey(entryName)) {
            return Optional.of(readEntry(entryName));
        }
        return Optional.empty();
    }

    private byte[] readEntry(String entryName) throws IOException {
        try {
            return cache.computeIfAbsent(entryName, name -> {
                try {
                    // Clear entry?
                    var entry = jarFile.getEntry(name);
                    if (entry != null) {
                        try (var is = jarFile.getInputStream(entry)) {
                            return is.readAllBytes();
                        }
                    }
                    // Encrypted via index?
                    var meta = index.entries().get(name);
                    if (meta != null) {
                        var blob = jarFile.getEntry(SjarMetadata.BLOB_DIR + meta.uuid());
                        if (blob == null) {
                            throw new IOException("Missing blob for " + name + " (uuid " + meta.uuid() + ")");
                        }
                        try (var is = jarFile.getInputStream(blob)) {
                            return SjarEncryptor.decryptBytes(is.readAllBytes(), key);
                        } catch (GeneralSecurityException e) {
                            throw new IOException("Failed to decrypt " + name, e);
                        }
                    }
                    throw new IOException("Entry not found: " + name);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public Optional<byte[]> moduleInfo() throws IOException {
        var entry = jarFile.getEntry("module-info.class");
        if (entry == null) return Optional.empty();
        try (var is = jarFile.getInputStream(entry)) {
            return Optional.of(is.readAllBytes());
        }
    }

    public SjarMetadata index() {
        return index;
    }

    @Override
    public void close() throws IOException {
        cache.clear();
        jarFile.close();
    }
}
```

Note: the old public `metadata()` accessor is renamed to `index()`. Grep for callers: `grep -rn "\.metadata()" vauban-sjar/src` — if any non-test caller exists, update it; the production callers do not use it.

- [ ] **Step 4: Run reader + encryptor + metadata tests together**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarArchiveReaderTest,SjarEncryptorTest,SjarMetadataTest,SjarHeaderTest`
Expected: PASS — all green. The module now compiles fully.

- [ ] **Step 5: Commit**

```bash
git add vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarArchiveReader.java \
        vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarArchiveReaderTest.java
git commit -m "feat(sjar): read v2 opaque layout via clear header + encrypted index"
```

---

## Task 5: Point `SjarPlugin` detection at the v2 header

**Files:**
- Modify: `vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarPlugin.java`
- Test: `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarPluginTest.java` (passes unchanged once detection is fixed)

- [ ] **Step 1: Run the plugin test to confirm current break**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarPluginTest`
Expected: FAIL on `handlesEncryptedJar` — v2 jars no longer contain `META-INF/vauban.encrypted`.

- [ ] **Step 2: Update detection**

In `SjarPlugin.java`, change the `handles` check from the old metadata entry to the v2 header. Replace line 49:

```java
            return jar.getEntry(SjarMetadata.METADATA_ENTRY) != null;
```

with:

```java
            return jar.getEntry(SjarHeader.HEADER_ENTRY) != null;
```

- [ ] **Step 3: Run the plugin test**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarPluginTest`
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add vauban-sjar/src/main/java/io/vidocq/vauban/sjar/SjarPlugin.java
git commit -m "feat(sjar): detect v2 SJAR via META-INF/vauban.header"
```

---

## Task 6: End-to-end opacity + classloader round-trip test

**Files:**
- Create: `vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarOpaqueFormatTest.java`

This is the spec's acceptance test: no internal name on disk, index is opaque without the key, and a real class loads under its real FQCN through `SjarClassLoader`.

- [ ] **Step 1: Write the end-to-end test**

Create `SjarOpaqueFormatTest.java` (license header + the following). It compiles a real internal class with the ClassFile API so `defineClass` succeeds and `getName()` can be asserted:

```java
package io.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class SjarOpaqueFormatTest {

    @TempDir
    Path tempDir;

    @Test
    void noInternalNameLeaksOnDisk() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = buildJar();
        SjarEncryptor.encryptJar(jarPath, key, "vidocq-prod");

        try (var jar = new JarFile(jarPath.toFile())) {
            var names = jar.stream().map(java.util.zip.ZipEntry::getName).toList();
            assertTrue(names.stream().noneMatch(n -> n.contains("internal")),
                    "internal package path leaked: " + names);
            assertTrue(names.stream().noneMatch(n -> n.endsWith(".class.enc")));
            assertTrue(names.contains(SjarHeader.HEADER_ENTRY));
            assertTrue(names.contains(SjarMetadata.INDEX_ENTRY));
            assertTrue(names.stream().anyMatch(n -> n.startsWith(SjarMetadata.BLOB_DIR)));
        }
    }

    @Test
    void indexIsOpaqueWithoutKey() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = buildJar();
        SjarEncryptor.encryptJar(jarPath, key, "vidocq-prod");

        try (var jar = new JarFile(jarPath.toFile());
             var is = jar.getInputStream(jar.getEntry(SjarMetadata.INDEX_ENTRY))) {
            var raw = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            // Ciphertext must not contain readable FQCN fragments
            assertFalse(raw.contains("internal"));
            assertFalse(raw.contains("Secret"));
        }
        // Wrong key cannot decrypt the index
        var wrong = SjarKeyProvider.generateKey();
        var ctx = SjarKeyProvider.withKey(wrong);
        assertThrows(Exception.class, () -> new SjarArchiveReader(jarPath, ctx));
    }

    @Test
    void classLoadsUnderRealNameThroughClassLoader() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = buildJar();
        SjarEncryptor.encryptJar(jarPath, key, "vidocq-prod");

        var ctx = SjarKeyProvider.withKey(key);
        try (var reader = new SjarArchiveReader(jarPath, ctx)) {
            var loader = new SjarClassLoader(reader, getClass().getClassLoader());
            var loaded = Class.forName("com.example.internal.Secret", false, loader);
            assertEquals("com.example.internal.Secret", loaded.getName());
        }
    }

    private Path buildJar() throws Exception {
        var jarPath = tempDir.resolve("app.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(ClassFile.of().buildModule(
                    java.lang.classfile.attribute.ModuleAttribute.of(
                            java.lang.constant.ModuleDesc.of("com.example"),
                            mb -> {
                                mb.requires(java.lang.constant.ModuleDesc.of("java.base"), 0, null);
                                mb.exports(java.lang.constant.PackageDesc.ofInternalName("com/example/api"), 0);
                            }),
                    mb -> {}));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/api/Service.class"));
            jos.write(realClass("com/example/api/Service"));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("com/example/internal/Secret.class"));
            jos.write(realClass("com/example/internal/Secret"));
            jos.closeEntry();
        }
        return jarPath;
    }

    /** Build a minimal but real, loadable class with the given internal name. */
    private byte[] realClass(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), cb -> {});
    }
}
```

- [ ] **Step 2: Run to verify it fails (if any gap remains) then passes**

Run: `./mvnw -q -pl vauban-sjar -am test -Dtest=SjarOpaqueFormatTest`
Expected: PASS (3 tests). If `classLoadsUnderRealNameThroughClassLoader` fails with `ClassNotFoundException`, confirm `SjarClassLoader.findClass` reads via `reader.readClass(name.replace('.', '/') + ".class")` — that path resolves through the index (no code change needed; the reader handles it).

- [ ] **Step 3: Commit**

```bash
git add vauban-sjar/src/test/java/io/vidocq/vauban/sjar/SjarOpaqueFormatTest.java
git commit -m "test(sjar): end-to-end opacity + real-name classloading for v2"
```

---

## Task 7: Full module verification + docs touch-up

**Files:**
- Modify (if present): any module README / `vauban-sjar` docs referencing `META-INF/vauban.encrypted` or `.class.enc`.

- [ ] **Step 1: Run the full module test suite clean**

Run: `./mvnw -q -pl vauban-sjar -am clean test`
Expected: PASS — entire `vauban-sjar` suite green (per workspace memory, always `clean` to avoid stale `target/` false negatives on codegen modules).

- [ ] **Step 2: Grep for stale v1 references and update docs**

Run: `grep -rn "vauban.encrypted\|class.enc\|METADATA_ENTRY" vauban-sjar --include=*.java --include=*.md --include=*.adoc`
Expected: no remaining `.java` hits except inside `SjarEncryptor` comments. Update any doc/Javadoc still describing the v1 layout to the v2 layout (header + index + UUID blobs). Update the class Javadoc of `SjarEncryptor` (lines ~42-51) which still says "renamed to `.class.enc`" and "`META-INF/vauban.encrypted` marker".

Replace the `SjarEncryptor` class Javadoc with:

```java
/**
 * Encrypts internal classes and resources of a modular JAR in-place using AES-256-GCM.
 *
 * <p>Entries of {@code exports}/{@code opens} packages (from {@code module-info.class})
 * stay clear for compilation and reflection. Every other class and resource is encrypted
 * and stored under an opaque {@code META-INF/vauban/<uuid>} name; the path→UUID mapping
 * lives only inside the encrypted {@code META-INF/vauban.index}. A tiny clear
 * {@code META-INF/vauban.header} carries the key alias to bootstrap decryption.
 *
 * <p>The result is a standard JAR: compilable (exported types visible), distributable
 * via Maven, with internal implementation fully opaque on disk.
 */
```

- [ ] **Step 3: Commit any doc changes**

```bash
git add -A vauban-sjar
git commit -m "docs(sjar): update Javadoc/docs to the v2 opaque layout"
```

- [ ] **Step 4: Final verification — confirm green and report**

Run: `./mvnw -q -pl vauban-sjar -am clean test 2>&1 | tail -20`
Expected: `BUILD SUCCESS`. Report the test count and confirm: no ZIP entry under any test fixture leaks an internal package path (asserted by `SjarOpaqueFormatTest#noInternalNameLeaksOnDisk`).

---

## Self-Review notes (addressed)

- **Spec coverage:** header (Task 2), encrypted index closing the `originalEntry` leak (Tasks 1+3), UUID blobs for classes **and** resources (Tasks 3-4), v2-only detection (Task 5), v2 replaces v1 with no legacy path (encryptor/reader rewrites), acceptance tests for on-disk opacity + real-name runtime (Task 6). All spec sections map to a task.
- **Type consistency:** `EntryMetadata(String uuid, byte[] iv, int originalSize, String kind)` is defined in Task 1 and used identically in Tasks 3, 4, 6. `SjarMetadata` constructor `(entries, clearPackages, moduleName)` — no `keyAlias` — is consistent across encryptor/reader. `SjarHeader.HEADER_ENTRY`, `SjarMetadata.INDEX_ENTRY`, `SjarMetadata.BLOB_DIR` constants used verbatim everywhere.
- **No placeholders:** every code step shows full code; commands have expected output.
- **Mid-refactor red:** Tasks 1-2 intentionally leave the module non-compiling until Task 4 restores it — this is called out so the implementer doesn't panic. The first fully-green module checkpoint is Task 4 Step 4.
```
