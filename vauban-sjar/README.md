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
- **Everything else** → encrypted (`.class` → `.class.enc`)

A marker file `META-INF/vauban.encrypted` stores the metadata (algorithm, IVs,
key alias, list of encrypted entries, clear packages).

```
my-library.jar
  module-info.class                      # clear — module system
  META-INF/vauban.encrypted              # metadata
  com/example/api/MyService.class        # clear — exported
  com/example/internal/Impl.class.enc    # encrypted
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
java -m fr.vidocq.vauban.sjar generate-key
# Output: a4b2c1d3e5f6...  (64 hex chars = 256 bits)
```

### 3. Encrypt at build time (Maven plugin)

```xml
<plugin>
    <groupId>fr.vidocq.vauban</groupId>
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
mvn package -Dvauban.sjar.key=a4b2c1d3...
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

### 5. Load encrypted beans at runtime

```java
var container = VaubanContainer.builder()
    .addByteSourcePlugin(new SjarPlugin())
    .pluginContext(SjarKeyProvider.withKey(key))
    .scanSjar(Path.of("mylib.jar"))
    .build();
```

## Key Management

| Source | Configuration | Use case |
|--------|--------------|----------|
| System property | `-Dvauban.sjar.key` (hex) | Maven builds |
| Environment variable | `VAUBAN_SJAR_KEY` (hex) | CI/CD pipelines |
| Java Keystore | `-Dvauban.sjar.keystore=path` | Production |
| Programmatic | `SjarKeyProvider.withKey(key)` | Custom vaults |

## Security Notes

- **AES-256-GCM**: authenticated encryption (confidentiality + integrity)
- Each class has a unique 12-byte IV
- Decrypted bytes cached in memory only — never written to disk
- **Limitation**: protects against static analysis of distributed JARs,
  not against runtime memory inspection (jmap, JVMTI agents)
