# SJAR opaque packaging — UUID entries + encrypted index

- **Date**: 2026-06-12
- **Sub-project**: `vauban` (module `vauban-sjar`)
- **Status**: design approved, pending implementation plan

## Context

The SJAR format (`vauban-sjar`) encrypts the bytecode of internal classes
(packages **not** exported/opened by `module-info`) with AES-256-GCM, while
keeping Java Modules-exposed classes in clear text so they remain usable for
compilation and reflection.

The current format (`VERSION = 1`) has a confidentiality leak that defeats the
purpose of encrypting the bytecode: **the class names are still fully visible**.

1. Encrypted classes are stored under their original path with a suffix:
   `io/vidocq/internal/Impl.class.enc`. Unzipping the archive reveals the entire
   internal package tree and every class name.
2. The metadata file `META-INF/vauban.encrypted` is **clear-text JSON** and
   records, for every encrypted entry, its `originalEntry` — i.e. the original
   fully-qualified path. Even if (1) were fixed, this file alone leaks every
   internal FQCN.

The goal of this change is that a packaged SJAR reveals **no internal package or
class name on disk**. The only names visible in the ZIP listing are the ones
Java Modules forces to be public (module name + exported/opened packages via
`module-info.class`), which is inherent to the module system and accepted.

## Decisions (locked)

- **Disk-only obfuscation.** Internal entries are renamed to a UUID **on disk
  only**. At runtime the classloader calls `defineClass` with the *real* class
  name, so `Class.getName()`, stack traces, reflection, annotations, CDI and
  Java Modules all see the real FQCN. No bytecode is rewritten.
- **Scope: classes *and* internal resources.** Every entry of a non-exposed
  package (`.class` or any resource) is renamed + encrypted. Resources of
  exposed packages stay clear.
- **Encrypted index with a tiny clear bootstrap header.** A minimal clear file
  carries only what is needed to resolve the key and locate the index; the full
  name→UUID mapping lives in a separate encrypted blob.
- **Format `VERSION = 2` replaces v1.** SJAR is pre-release; v1 is the leaking
  format being fixed. The encryptor writes only v2 and the reader reads only v2.
  No legacy read path. Old v1 archives are re-packaged on the next build.

## On-disk format (v2)

After packaging, the ZIP listing exposes no internal path:

```
module-info.class                       ← clear (Java Modules API, unavoidable)
io/vidocq/api/Service.class             ← clear (exports/opens packages only)
io/vidocq/api/messages.properties       ← clear (resource of an exposed package)
META-INF/MANIFEST.MF
META-INF/vauban.header                  ← CLEAR, tiny: { version, algorithm, keyAlias, indexIv }
META-INF/vauban.index                   ← ENCRYPTED blob: the full mapping
META-INF/vauban/<uuid>                  ← ENCRYPTED blob, one per internal class/resource (name = UUID)
META-INF/vauban/<uuid>
...
```

Internal package directories (`io/vidocq/internal/...`) disappear entirely.

### `META-INF/vauban.header` (clear)

Minimal bootstrap, no class name ever:

```json
{
  "version": 2,
  "algorithm": "AES/GCM/NoPadding",
  "keyAlias": "vidocq-prod"
}
```

`keyAlias` is a logical key identifier (e.g. `vidocq-prod`), resolved by the
existing `SjarKeyProvider` (env `VAUBAN_SJAR_KEY` / keystore). It never contains
a class or package name.

### `META-INF/vauban.index` (encrypted with the master key)

Layout on disk: `[12-byte IV][ciphertext + 16-byte GCM tag]` — self-describing,
exactly like every other encrypted blob, so it reuses `SjarEncryptor.encryptBytes`
/ `decryptBytes` unchanged (the GCM IV is not secret). Once decrypted, the
plaintext is JSON, reusing the existing hand-rolled JSON writer/reader in
`SjarMetadata` (zero new dependency):

```json
{
  "version": 2,
  "moduleName": "io.vidocq.example",
  "clearPackages": ["io/vidocq/api"],
  "entries": {
    "io/vidocq/internal/Impl.class":        { "uuid": "3f2a...-9c1b", "iv": "<b64>", "originalSize": 4096, "kind": "class" },
    "io/vidocq/internal/templates/x.json":  { "uuid": "a87e...-44d0", "iv": "<b64>", "originalSize": 812,  "kind": "resource" }
  }
}
```

This is where the v1 leak (`originalEntry` in clear) is closed: the mapping only
ever exists inside the encrypted blob.

### `META-INF/vauban/<uuid>` (encrypted blobs)

Same per-entry encryption as today: `[12-byte IV][ciphertext + 16-byte GCM
tag]`, AES-256-GCM, unique IV per entry. Placed under `META-INF/` so the Java Modules
module reader and the `classEntries()` scan ignore them. The UUID is a random
v4 UUID string; the file has no extension.

## Read / write flow

### Build (write) — `SjarEncryptor.encryptJar`

1. Parse `module-info.class` → `clearPackages` (unchanged logic).
2. For each entry of a **non-exposed** package (class **or** resource):
   generate a UUID, encrypt the bytes, write `META-INF/vauban/<uuid>`, and
   record `originalPath → { uuid, iv, originalSize, kind }` in the index.
3. Entries of exposed packages + `module-info.class` + `META-INF/*`: copied
   clear.
4. Serialize the index JSON → encrypt with the master key (fresh IV) →
   `META-INF/vauban.index`; write the clear `META-INF/vauban.header`.

### Runtime (read) — `SjarArchiveReader`

1. Read `META-INF/vauban.header` → `keyAlias` → `context.resolveKey()`
   (unchanged). Fail fast if the header is missing (not a v2 SJAR).
2. Read `META-INF/vauban.index`, decrypt with the master key + `indexIv`, parse
   JSON → build `originalPath → entry` map (and the inverse for
   `classEntries()`).
3. `readClass(name)`: if a clear entry exists → read directly; else
   `uuid = index.get(name).uuid` → read `META-INF/vauban/<uuid>` → decrypt.
   In-memory cache unchanged.
4. `SjarClassLoader.getResourceAsStream`: same index resolution for internal
   resources (clear resources read directly).

### Unchanged

- `SjarKeyProvider` (3-level key resolution).
- The SPI `ArchiveReader` / `ByteSourcePlugin` / `PluginContext`.
- `SjarPlugin` detection — now keys off `META-INF/vauban.header` instead of
  `META-INF/vauban.encrypted`.
- `SjarTool` CLI and the Maven plugin: public API identical.

## Testing (TDD — tests first)

1. **No internal name on disk**: package a fixture jar with exported + internal
   packages; assert the ZIP listing contains no entry under any internal package
   path and no `*.class.enc`.
2. **Header carries no secret**: assert `vauban.header` parses and contains no
   FQCN / internal package string.
3. **Index is opaque**: assert `vauban.index` bytes are not parseable as JSON
   without decryption, and decrypting with the wrong key fails (GCM tag).
4. **Round-trip class**: load an internal class via UUID resolution; assert
   `Class.getName()` returns the real FQCN and the class is functional.
5. **Round-trip resource**: `getResourceAsStream` of an internal resource
   returns the original bytes.
6. **Exposed entries stay clear**: exported-package classes and their resources
   remain clear and loadable.
7. **Wrong/missing header**: a non-v2 archive (no `vauban.header`) is rejected
   with a clear error.

## Out of scope

- Runtime bytecode renaming (explicitly rejected — breaks the ecosystem).
- Backward-compatible reading of v1 archives.
- Hiding the module name / exported packages (inherent to Java Modules).
- Defeating a runtime attacker who already holds the master key (this is
  on-disk opacity, not key escrow).
