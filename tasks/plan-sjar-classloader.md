# Plan: Plugin-based ClassLoader system for Vauban — SJAR (Secure JAR) support

## Context

Vauban is a native Java Modules CDI 4.1 container with 774/774 TCK. The goal is to introduce a **plugin classloader system** to support advanced use cases, starting with **encrypted JARs** (`.sjar`). This makes it possible to distribute CDI libraries whose classes are protected by AES-256-GCM encryption.

### Current classloading entry points

| File | Mechanism | Role |
|---------|-----------|------|
| `BeanDiscovery.java` | `Class.forName(name, false, cl)` | Loading discovered beans |
| `VaubanContainerBuilder.java` | `scanLocal/scanPackage/scanClasspath` | Discovery + `Class.forName()` |
| `BceProcessor.java` | `classLoader.loadClass()` | Build Compatible Extensions |
| `InterceptorBeanWrapper.java` | `loadOrDefineClassRobustly()` | Defining proxies/interceptors |
| `JarScanner.java` | `JarFile` -> `byte[]` -> `ClassFileScanner` | Bytecode indexing (no ClassLoader) |
| `VaubanGenerator.java` | `URLClassLoader` | Build-time generation |

### Key architectural decision

**Byte-level interception** rather than ClassLoader replacement:
- `ClassFileScanner.scan(byte[])` already works on raw bytes
- We intercept the byte reading **before** the scan and **before** the `defineClass`
- A custom `SjarClassLoader` provides the `defineClass` for the encrypted classes

---

## Architecture

### 2 new modules

#### `vauban-classloader-spi` — Pure SPI interfaces

```
io.vidocq.vauban.classloader.spi
|-- ByteSourcePlugin.java    — Main SPI (ServiceLoader)
|-- ArchiveReader.java       — Archive reading abstraction
|-- PluginContext.java        — Key access + config
```

#### `vauban-sjar` — AES-256-GCM encryption implementation

```
io.vidocq.vauban.sjar
|-- SjarPlugin.java          — implements ByteSourcePlugin
|-- SjarArchiveReader.java   — Reading + decryption of entries
|-- SjarClassLoader.java     — Custom ClassLoader (findClass + getResourceAsStream)
|-- SjarEncryptor.java       — JAR -> SJAR tool
|-- SjarKeyProvider.java     — Key resolution (env, keystore, callback)
|-- cli/
    |-- SjarTool.java        — encrypt/verify CLI
```

### SJAR format

A `.sjar` is a **standard ZIP** with:

```
META-INF/
  MANIFEST.MF              # Standard headers + Vauban-Encryption
  SJAR-METADATA.json        # Encryption metadata (not encrypted)
  SJAR-METADATA.sig          # HMAC-SHA256 of the metadata
com/example/MyBean.class.enc  # Encrypted class [12B IV][ciphertext+GCM tag]
resources/                     # Unencrypted resources
```

**SJAR-METADATA.json**:
```json
{
  "version": 1,
  "algorithm": "AES/GCM/NoPadding",
  "keyLength": 256,
  "keyAlias": "my-app-key",
  "entries": {
    "com/example/MyBean.class.enc": { "iv": "<base64>", "originalSize": 1234 }
  }
}
```

### SPI Interfaces

```java
public interface ByteSourcePlugin {
    String protocol();                    // ex: "sjar"
    boolean handles(Path archivePath);    // ex: path.endsWith(".sjar")
    ArchiveReader open(Path archivePath, PluginContext context) throws IOException;
    default int priority() { return 1000; }
}

public interface ArchiveReader extends AutoCloseable {
    List<String> classEntries();
    byte[] readClass(String entryName) throws IOException;
    byte[] readResource(String entryName) throws IOException;
    default Optional<byte[]> moduleInfo() { return Optional.empty(); }
}

public interface PluginContext {
    SecretKey resolveKey(String keyAlias) throws SecurityException;
    Optional<String> property(String name);
}
```

### Key management (3 levels)

1. **Env**: `VAUBAN_SJAR_KEY=<hex-256-bits>` — CI/CD
2. **Keystore**: `-Dvauban.sjar.keystore=path -Dvauban.sjar.keyalias=mykey` — production
3. **Programmatic**: `builder.pluginContext(custom)` — vault (HashiCorp, AWS KMS)

---

## Integration with the existing code

### 1. `JarScanner` — new plugin-aware overload

```java
// Existing (unchanged):
public static List<ClassInfo> scan(Path jarPath) throws IOException;

// New:
public static List<ClassInfo> scan(Path archivePath,
    List<ByteSourcePlugin> plugins, PluginContext context) throws IOException;
```

### 2. `VaubanContainerBuilder` — plugin registration

- New `List<ByteSourcePlugin>` field + `PluginContext`
- `addByteSourcePlugin()`, `pluginContext()` methods
- Auto-discovery via `ServiceLoader.load(ByteSourcePlugin.class)` in `build()`
- `scanClasspath()` extended to detect `.sjar` files on the classpath
- New `scanSjar(Path)` to add an SJAR explicitly

### 3. `VaubanGenerator` (Maven plugin)

- `Config` record extended with `List<ByteSourcePlugin>` + `PluginContext`
- `generate()` uses the plugin-aware overload of `JarScanner`

### 4. New `EncryptMojo` Mojo

`vauban:encrypt` goal — takes a JAR as input, produces a `.sjar`.

---

## Files to create

| File | Module |
|---------|--------|
| `vauban-classloader-spi/pom.xml` | new |
| `vauban-classloader-spi/src/main/java/module-info.java` | new |
| `vauban-classloader-spi/.../spi/ByteSourcePlugin.java` | new |
| `vauban-classloader-spi/.../spi/ArchiveReader.java` | new |
| `vauban-classloader-spi/.../spi/PluginContext.java` | new |
| `vauban-sjar/pom.xml` | new |
| `vauban-sjar/src/main/java/module-info.java` | new |
| `vauban-sjar/.../sjar/SjarPlugin.java` | new |
| `vauban-sjar/.../sjar/SjarArchiveReader.java` | new |
| `vauban-sjar/.../sjar/SjarClassLoader.java` | new |
| `vauban-sjar/.../sjar/SjarEncryptor.java` | new |
| `vauban-sjar/.../sjar/SjarKeyProvider.java` | new |
| `vauban-sjar/.../sjar/cli/SjarTool.java` | new |
| Tests in `vauban-sjar/src/test/java/` | new |

## Files to modify

| File | Change |
|---------|-----------|
| `pom.xml` (root) | Add modules + dependencyManagement |
| `vauban-indexer/module-info.java` | `requires static io.vidocq.vauban.classloader.spi` |
| `vauban-indexer/.../JarScanner.java` | Plugin-aware overload |
| `vauban-core/module-info.java` | `requires static io.vidocq.vauban.classloader.spi`, `uses ByteSourcePlugin` |
| `vauban-core/.../VaubanContainerBuilder.java` | Plugin registration + ServiceLoader |
| `vauban-maven-plugin/.../VaubanGenerator.java` | Extended Config |
| `vauban-maven-plugin/.../GenerateMojo.java` | SJAR dependencies |
| `vauban-maven-plugin/plugin.xml` | New encrypt goal |

---

## Implementation phases

### Phase 1: SPI + basic SJAR (classpath, non-modular)

1. Create `vauban-classloader-spi` with the 3 interfaces
2. Create `vauban-sjar` with `SjarPlugin`, `SjarArchiveReader`, `SjarEncryptor`, `SjarClassLoader`
3. Modify `JarScanner` — plugin-aware overload
4. Modify `VaubanContainerBuilder` — ServiceLoader plugins + `scanSjar()`
5. Add `EncryptMojo` to the maven plugin
6. Tests: encrypt a test JAR, scan it, discover beans, run CDI

### Phase 2: Full integration

1. Build-time `VaubanGenerator` with SJAR
2. Java Modules `ModuleLayer` for named modules in SJARs
3. `SjarTool` CLI tool

### Phase 3: Hardening

1. HMAC signature verification of the metadata
2. Key rotation (multiple aliases per SJAR)
3. Selective encryption (specific packages)

---

## Verification

1. **Compilation**: `mvn compile -q` — all modules
2. **Unit tests**: `mvn test -pl vauban-sjar` — encryption/decryption, scan, discovery
3. **Integration test**: create an SJAR with a CDI bean, load it via `VaubanContainer.builder().scanSjar(path).build()`, verify injection
4. **TCK**: 774/774 unchanged (SJAR is additive, breaks nothing)
5. **Security**: verify that decrypted bytes do not leak (no temp files, in-memory cache only)

## Known limitations

- **Relative security**: encryption protects against static reading but not against `jmap` or JVMTI agents at runtime
- **Phase 1 classpath only**: no ModuleLayer support for modular SJARs (Phase 2)
- **No resource encryption** in Phase 1 (only `.class` files)
