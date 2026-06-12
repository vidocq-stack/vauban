# Vauban SJAR — In-JAR Class Encryption

Encrypts internal classes of a **modular JAR** in-place using AES-256-GCM.
Exported/opened packages stay in clear text for compilation; all other packages
are encrypted. The result is a single, standard JAR that is compilable,
distributable via Maven, and has its internals protected.

## How It Works

The encryption is driven by `module-info.class`:

- `exports` packages → **clear** (needed for compilation)
- `opens` packages → **clear** (needed for reflection)
- `module-info.class`, `META-INF/*` → **clear**
- **Everything else** (classes **and** resources) → encrypted into opaque
  `META-INF/vauban/<uuid>` blobs

A clear `META-INF/vauban.header` carries the key alias to bootstrap decryption.
The path→UUID mapping plus per-entry metadata (algorithm, IVs, original sizes,
clear packages) lives only inside the **encrypted** `META-INF/vauban.index` — so
no internal package or class name ever appears on disk.

```
my-library.jar
  module-info.class                          # clear — module system
  META-INF/vauban.header                     # clear — key alias bootstrap
  META-INF/vauban.index                      # encrypted — path→UUID map + metadata
  com/example/api/MyService.class            # clear — exported
  META-INF/vauban/3f2a...-uuid               # encrypted — opaque internal blob
```

## Quick Start

### 1. Structure your module

```java
module com.example.mylib {
    exports com.example.mylib.api;       // public API — stays clear
    // com.example.mylib.internal → encrypted automatically
}
```

### 2. Generate a key

```bash
java -m io.vidocq.vauban.sjar generate-key
# Output: a4b2c3d4e5f6...  (64 hex chars = 256 bits)
```

### 3. Encrypt at build time (Maven plugin)

```xml
<plugin>
    <groupId>io.vidocq.vauban</groupId>
    <artifactId>vauban-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>encrypt</goal></goals>
            <configuration>
                <keyAlias>my-key</keyAlias>
            </configuration>
        </execution>
    </executions>
</plugin>
```

```bash
mvn package -Dvauban.sjar.key=a4b2c3d4...
# The JAR is encrypted in-place — one artifact, standard Maven
```

### 4. Consumers compile normally

```xml
<!-- Just a regular dependency — exported types are visible -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>mylib</artifactId>
    <version>1.0</version>
</dependency>
```

### 5. Runtime: transparent decryption

When `vauban-sjar` is on the classpath, `scanClasspath()` **automatically**
detects encrypted JARs and decrypts their internal classes:

```java
// No special code needed — encrypted JARs are handled transparently
var container = VaubanContainer.builder()
    .scanClasspath()
    .build();
```

Set the decryption key via environment variable:
```bash
export VAUBAN_SJAR_KEY=a4b2c3d4e5f6...
java -cp "lib/*" com.example.Main
```

If the key is missing, Vauban prints a warning:
```
[WARN] Vauban: cannot decrypt encrypted JAR mylib.jar — No SJAR key found...
[WARN] Vauban: set VAUBAN_SJAR_KEY environment variable or call builder.pluginContext()
[WARN] Vauban: beans from this JAR will NOT be available.
```

## Key Management

| Source | Configuration | Use case |
|--------|--------------|----------|
| Environment variable | `VAUBAN_SJAR_KEY` (hex) | Production, CI/CD |
| System property | `-Dvauban.sjar.key` (hex) | Maven builds |
| Java Keystore | `-Dvauban.sjar.keystore=path -Dvauban.sjar.keypassword=pass` | Enterprise |
| Programmatic | `SjarKeyProvider.withKey(secretKey)` | Custom vaults (HashiCorp, AWS KMS) |

## ClassLoader Plugin SPI

The encryption system is built on a pluggable architecture. Vauban can load
classes from any custom source — not just encrypted JARs.

### Architecture

```
scanClasspath()
  │
  ├─ Detect META-INF/vauban.header in JARs on classpath
  │    ├─ ServiceLoader.load(ByteSourcePlugin.class)  ← finds all plugins
  │    ├─ plugin.handles(jarPath) → first match wins (sorted by priority)
  │    └─ plugin.open(jar, ctx) → ArchiveReader
  │         ├─ reader.classEntries() → list of class names
  │         ├─ reader.readClass(entry) → decrypted bytes (cached in memory)
  │         └─ createPluginClassLoader → ClassLoader with defineClass()
  │
  └─ Read META-INF/vauban-beans.list (standard bean discovery)
```

### SPI Interfaces (`vauban-classloader-spi`)

```java
// Declares what archive format this plugin handles
public interface ByteSourcePlugin {
    String protocol();                                    // e.g. "vauban-encrypted"
    boolean handles(Path archivePath);                    // inspects the archive
    ArchiveReader open(Path archivePath, PluginContext ctx) throws IOException;
    default int priority() { return 1000; }               // lower = higher priority
}

// Provides decrypted class bytes from an archive
public interface ArchiveReader extends AutoCloseable {
    List<String> classEntries() throws IOException;       // e.g. "com/example/Foo.class"
    byte[] readClass(String entryName) throws IOException;
    Optional<byte[]> readResource(String entryName) throws IOException;
    default Optional<byte[]> moduleInfo() throws IOException { return Optional.empty(); }
}

// Provides keys and configuration to plugins
public interface PluginContext {
    SecretKey resolveKey(String keyAlias);
    Optional<String> property(String name);
}
```

### Multiple Plugins

The system supports **multiple plugins simultaneously**. All plugins declared
via `ServiceLoader` (or registered manually) are loaded and sorted by priority:

```java
ServiceLoader.load(ByteSourcePlugin.class)  // discovers all plugins
    .sorted(by priority)                     // lower priority value = checked first
```

When scanning, the first plugin whose `handles()` returns `true` wins:

```java
for (var plugin : plugins) {
    if (plugin.handles(archivePath)) {
        // this plugin processes the archive
    }
}
```

Example with multiple plugins:

| Plugin | Priority | Handles |
|--------|----------|---------|
| `SjarPlugin` | 100 | JARs with `META-INF/vauban.header` |
| `RemotePlugin` | 200 | JARs downloaded from a remote server |
| `VaultPlugin` | 300 | Keys resolved from HashiCorp Vault |

### Writing a Custom Plugin

1. Implement `ByteSourcePlugin`:

```java
public class MyPlugin implements ByteSourcePlugin {
    @Override public String protocol() { return "my-format"; }
    @Override public boolean handles(Path path) { /* check the file */ }
    @Override public ArchiveReader open(Path path, PluginContext ctx) { /* ... */ }
    @Override public int priority() { return 500; }
}
```

2. Register via ServiceLoader:

```
# META-INF/services/io.vidocq.vauban.classloader.spi.ByteSourcePlugin
com.example.MyPlugin
```

Or in `module-info.java`:
```java
provides io.vidocq.vauban.classloader.spi.ByteSourcePlugin with com.example.MyPlugin;
```

3. Or register programmatically:
```java
VaubanContainer.builder()
    .addByteSourcePlugin(new MyPlugin())
    .pluginContext(myContext)
    .scanClasspath()
    .build();
```

## Maven Plugin Goals

| Goal | Phase | Description |
|------|-------|-------------|
| `vauban:generate` | process-classes | CDI bean discovery + proxy/interceptor generation |
| `vauban:encrypt` | package | Encrypt internal classes in-place (modular JARs) |
| `vauban:dist` | package | Package distribution ZIP with launch scripts + JARs |

## Security Notes

- **AES-256-GCM**: authenticated encryption (confidentiality + integrity)
- Each class has a unique 12-byte IV (never reused)
- Decrypted bytes cached in memory only — never written to disk
- **Limitation**: protects against static analysis of distributed JARs,
  not against runtime memory inspection (jmap, JVMTI agents)
