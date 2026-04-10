# Vauban SJAR — Secure JAR Encryption

SJAR (Secure JAR) provides AES-256-GCM encryption for Java class files, allowing
distribution of CDI bean libraries with protected bytecode.

## How It Works

An `.sjar` file is a standard ZIP archive where `.class` entries are individually
encrypted with AES-256-GCM. Non-class resources (META-INF/*, etc.) remain unencrypted.
A `META-INF/SJAR-METADATA.json` file stores encryption metadata (algorithm, IVs, key alias).

```
my-library.sjar
  META-INF/
    MANIFEST.MF                     # Standard manifest
    SJAR-METADATA.json              # Encryption metadata (unencrypted)
  com/example/MyBean.class.enc      # Encrypted class [12B IV][ciphertext+GCM tag]
  META-INF/beans.xml                # Resources preserved as-is
```

## Quick Start

### 1. Generate a key

```bash
# Using the CLI tool
java -m fr.vidocq.vauban.sjar generate-key
# Output: a4b2c1d3e5f6...  (64 hex chars = 256 bits)

# Or programmatically
SecretKey key = SjarKeyProvider.generateKey();
```

### 2. Encrypt a JAR at build time (Maven plugin)

Add to your `pom.xml`:

```xml
<plugin>
    <groupId>fr.vidocq.vauban</groupId>
    <artifactId>vauban-maven-plugin</artifactId>
    <version>${vauban.version}</version>
    <executions>
        <execution>
            <goals><goal>encrypt</goal></goals>
            <configuration>
                <keyAlias>my-app-key</keyAlias>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Then build with the key:

```bash
export VAUBAN_SJAR_KEY=a4b2c1d3e5f6...
mvn package
# Produces: target/my-library-1.0.sjar (attached as classifier "encrypted")
```

The key can also be passed as a Maven property: `-Dvauban.sjar.key=a4b2c1d3...`

### 3. Encrypt programmatically

```java
SecretKey key = SjarKeyProvider.generateKey();
SjarEncryptor.encrypt(
    Path.of("my-library.jar"),
    Path.of("my-library.sjar"),
    key,
    "my-key-alias"
);
```

### 4. Load encrypted beans at runtime

```java
var container = VaubanContainer.builder()
    .addByteSourcePlugin(new SjarPlugin())
    .pluginContext(SjarKeyProvider.withKey(key))
    .scanSjar(Path.of("my-library.sjar"))
    .build();

// Beans from the SJAR are available like regular CDI beans
var myBean = container.select(MyBean.class);
```

If `vauban-sjar` is on the module path, the `SjarPlugin` is auto-discovered via
`ServiceLoader` and `addByteSourcePlugin()` is not needed.

## Key Management

Keys are resolved in this order:

| Source | Configuration | Use case |
|--------|--------------|----------|
| Environment variable | `VAUBAN_SJAR_KEY` (hex) | CI/CD pipelines |
| System property | `-Dvauban.sjar.key` (hex) | Maven builds |
| Java Keystore | `-Dvauban.sjar.keystore=path -Dvauban.sjar.keypassword=pass` | Production |
| Programmatic | `SjarKeyProvider.withKey(secretKey)` | Custom vaults (HashiCorp, AWS KMS) |

## CLI Tool

```bash
# Encrypt a JAR
export VAUBAN_SJAR_KEY=<64-hex-chars>
java -m fr.vidocq.vauban.sjar encrypt --input app.jar --output app.sjar --key-alias mykey

# Generate a random AES-256 key
java -m fr.vidocq.vauban.sjar generate-key
```

## SPI Plugin Architecture

SJAR is built on Vauban's classloader plugin SPI (`vauban-classloader-spi`).
Custom archive formats can be implemented by providing:

- `ByteSourcePlugin` — declares what file extensions it handles
- `ArchiveReader` — reads and decrypts class entries
- `PluginContext` — provides keys and configuration

## Security Notes

- **AES-256-GCM** provides both confidentiality and integrity (authenticated encryption)
- Each class entry has a unique 12-byte IV (never reused)
- Decrypted bytes are cached **in memory only** — never written to disk
- **Limitation**: once loaded by the JVM, class bytecode is accessible via `jmap`,
  Java agents (JVMTI), or heap dumps. SJAR protects against static analysis of
  distributed artifacts, not runtime memory inspection.
