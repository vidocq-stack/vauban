# Plan : Systeme de ClassLoader a plugins pour Vauban — Support SJAR (Secure JAR)

## Contexte

Vauban est un conteneur CDI 4.1 JPMS-native avec 774/774 TCK. L'objectif est d'introduire un **systeme de plugins classloader** pour supporter des cas d'usage avances, en commencant par les **JARs chiffres** (`.sjar`). Cela permet de distribuer des bibliotheques CDI dont les classes sont protegees par chiffrement AES-256-GCM.

### Points d'entree actuels du classloading

| Fichier | Mecanisme | Role |
|---------|-----------|------|
| `BeanDiscovery.java` | `Class.forName(name, false, cl)` | Chargement des beans decouverts |
| `VaubanContainerBuilder.java` | `scanLocal/scanPackage/scanClasspath` | Decouverte + `Class.forName()` |
| `BceProcessor.java` | `classLoader.loadClass()` | Build Compatible Extensions |
| `InterceptorBeanWrapper.java` | `loadOrDefineClassRobustly()` | Definition proxies/intercepteurs |
| `JarScanner.java` | `JarFile` -> `byte[]` -> `ClassFileScanner` | Indexation bytecode (pas de ClassLoader) |
| `VaubanGenerator.java` | `URLClassLoader` | Generation build-time |

### Decision architecturale cle

**Interception au niveau bytes** plutot que remplacement de ClassLoader :
- `ClassFileScanner.scan(byte[])` travaille deja sur des bytes bruts
- On intercepte la lecture des bytes **avant** le scan et **avant** le `defineClass`
- Un `SjarClassLoader` custom fournit le `defineClass` pour les classes chiffrees

---

## Architecture

### 2 nouveaux modules

#### `vauban-classloader-spi` — Interfaces SPI pures

```
fr.vidocq.vauban.classloader.spi
|-- ByteSourcePlugin.java    — SPI principale (ServiceLoader)
|-- ArchiveReader.java       — Abstraction lecture d'archive
|-- PluginContext.java        — Acces cles + config
```

#### `vauban-sjar` — Implementation chiffrement AES-256-GCM

```
fr.vidocq.vauban.sjar
|-- SjarPlugin.java          — implements ByteSourcePlugin
|-- SjarArchiveReader.java   — Lecture + dechiffrement entries
|-- SjarClassLoader.java     — ClassLoader custom (findClass + getResourceAsStream)
|-- SjarEncryptor.java       — Outil JAR -> SJAR
|-- SjarKeyProvider.java     — Resolution cles (env, keystore, callback)
|-- cli/
    |-- SjarTool.java        — CLI encrypt/verify
```

### Format SJAR

Un `.sjar` est un **ZIP standard** avec :

```
META-INF/
  MANIFEST.MF              # Headers standard + Vauban-Encryption
  SJAR-METADATA.json        # Metadonnees chiffrement (non chiffre)
  SJAR-METADATA.sig          # HMAC-SHA256 du metadata
com/example/MyBean.class.enc  # Classe chiffree [12B IV][ciphertext+GCM tag]
resources/                     # Ressources non chiffrees
```

**SJAR-METADATA.json** :
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

### Gestion des cles (3 niveaux)

1. **Env** : `VAUBAN_SJAR_KEY=<hex-256-bits>` — CI/CD
2. **Keystore** : `-Dvauban.sjar.keystore=path -Dvauban.sjar.keyalias=mykey` — production
3. **Programmatique** : `builder.pluginContext(custom)` — vault (HashiCorp, AWS KMS)

---

## Integration avec le code existant

### 1. `JarScanner` — nouvelle surcharge plugin-aware

```java
// Existant (inchange) :
public static List<ClassInfo> scan(Path jarPath) throws IOException;

// Nouveau :
public static List<ClassInfo> scan(Path archivePath,
    List<ByteSourcePlugin> plugins, PluginContext context) throws IOException;
```

### 2. `VaubanContainerBuilder` — enregistrement plugins

- Nouveau champ `List<ByteSourcePlugin>` + `PluginContext`
- Methodes `addByteSourcePlugin()`, `pluginContext()`
- Auto-discovery via `ServiceLoader.load(ByteSourcePlugin.class)` dans `build()`
- `scanClasspath()` etendu pour detecter les `.sjar` sur le classpath
- Nouveau `scanSjar(Path)` pour ajouter un SJAR explicitement

### 3. `VaubanGenerator` (Maven plugin)

- `Config` record etendu avec `List<ByteSourcePlugin>` + `PluginContext`
- `generate()` utilise la surcharge plugin-aware de `JarScanner`

### 4. Nouveau Mojo `EncryptMojo`

Goal `vauban:encrypt` — prend un JAR en entree, produit un `.sjar`.

---

## Fichiers a creer

| Fichier | Module |
|---------|--------|
| `vauban-classloader-spi/pom.xml` | nouveau |
| `vauban-classloader-spi/src/main/java/module-info.java` | nouveau |
| `vauban-classloader-spi/.../spi/ByteSourcePlugin.java` | nouveau |
| `vauban-classloader-spi/.../spi/ArchiveReader.java` | nouveau |
| `vauban-classloader-spi/.../spi/PluginContext.java` | nouveau |
| `vauban-sjar/pom.xml` | nouveau |
| `vauban-sjar/src/main/java/module-info.java` | nouveau |
| `vauban-sjar/.../sjar/SjarPlugin.java` | nouveau |
| `vauban-sjar/.../sjar/SjarArchiveReader.java` | nouveau |
| `vauban-sjar/.../sjar/SjarClassLoader.java` | nouveau |
| `vauban-sjar/.../sjar/SjarEncryptor.java` | nouveau |
| `vauban-sjar/.../sjar/SjarKeyProvider.java` | nouveau |
| `vauban-sjar/.../sjar/cli/SjarTool.java` | nouveau |
| Tests dans `vauban-sjar/src/test/java/` | nouveau |

## Fichiers a modifier

| Fichier | Changement |
|---------|-----------|
| `pom.xml` (racine) | Ajouter modules + dependencyManagement |
| `vauban-indexer/module-info.java` | `requires static fr.vidocq.vauban.classloader.spi` |
| `vauban-indexer/.../JarScanner.java` | Surcharge plugin-aware |
| `vauban-core/module-info.java` | `requires static fr.vidocq.vauban.classloader.spi`, `uses ByteSourcePlugin` |
| `vauban-core/.../VaubanContainerBuilder.java` | Plugin registration + ServiceLoader |
| `vauban-maven-plugin/.../VaubanGenerator.java` | Config etendu |
| `vauban-maven-plugin/.../GenerateMojo.java` | SJAR dependencies |
| `vauban-maven-plugin/plugin.xml` | Nouveau goal encrypt |

---

## Phases d'implementation

### Phase 1 : SPI + SJAR basique (classpath, non-modulaire)

1. Creer `vauban-classloader-spi` avec les 3 interfaces
2. Creer `vauban-sjar` avec `SjarPlugin`, `SjarArchiveReader`, `SjarEncryptor`, `SjarClassLoader`
3. Modifier `JarScanner` — surcharge plugin-aware
4. Modifier `VaubanContainerBuilder` — plugins ServiceLoader + `scanSjar()`
5. Ajouter `EncryptMojo` au maven plugin
6. Tests : chiffrer un JAR test, scanner, decouvrir beans, executer CDI

### Phase 2 : Integration complete

1. `VaubanGenerator` build-time avec SJAR
2. JPMS `ModuleLayer` pour modules nommes dans SJARs
3. CLI tool `SjarTool`

### Phase 3 : Durcissement

1. Verification signature HMAC du metadata
2. Rotation de cles (multiple aliases par SJAR)
3. Chiffrement selectif (packages specifiques)

---

## Verification

1. **Compilation** : `mvn compile -q` — tous les modules
2. **Tests unitaires** : `mvn test -pl vauban-sjar` — chiffrement/dechiffrement, scan, discovery
3. **Test integration** : creer un SJAR avec un bean CDI, le charger via `VaubanContainer.builder().scanSjar(path).build()`, verifier injection
4. **TCK** : 774/774 inchange (le SJAR est additif, ne casse rien)
5. **Securite** : verifier que les bytes dechiffres ne fuient pas (pas de temp files, cache en memoire uniquement)

## Limites connues

- **Securite relative** : le chiffrement protege contre la lecture statique mais pas contre `jmap` ou agents JVMTI en runtime
- **Phase 1 classpath only** : pas de support ModuleLayer pour les SJAR modulaires (Phase 2)
- **Pas de chiffrement des ressources** en Phase 1 (uniquement les `.class`)
