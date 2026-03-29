# Vauban

> [English version below](#english)

## Conteneur CDI 4.1 natif Java Modules

Vauban est une implementation de [Jakarta CDI 4.1](https://jakarta.ee/specifications/cdi/4.1/) concue des le depart pour le systeme de modules Java (JPMS). Inspire de l'approche build-time de [Quarkus ArC](https://quarkus.io/guides/cdi-reference), Vauban genere tout le code necessaire (proxies, intercepteurs, factories) a la compilation, sans aucune reflexion a l'execution.

### Philosophie

- **JPMS-first** : chaque composant est un module Java explicite
- **Zero reflexion** : generation de code statique a la compilation via APT et plugins Maven
- **JDK pur** : utilise l'API Class-File du JDK 25 pour la generation de bytecode, pas d'ASM ni ByteBuddy
- **Dependances minimales** : le coeur (`vauban-indexer`) n'a aucune dependance externe
- **Build Compatible Extensions** : supporte le modele d'extensions CDI Lite (pas les Portable Extensions)

### Stack technique

| Composant | Version |
|-----------|---------|
| JDK | 25 (Temurin) |
| Maven | 4.0.0-rc-5 |
| CDI API | 4.1.0 |
| JUnit | 6.0.3 |

### Modules

```
vauban/
├── vauban-indexer        Indexeur de classes (remplace Jandex), zero dependance
├── vauban-api            API publique Vauban
├── vauban-core           Runtime du conteneur CDI
├── vauban-processor      Processeur d'annotations + generation de code
├── vauban-maven-plugin   Plugin Maven (indexation, analyse JPMS)
├── vauban-junit          Extension JUnit 6 pour tests d'integration
├── vauban-tck-runner     Runner CDI TCK 4.1
└── vauban-test-suite     Suite de tests d'integration
```

### Demarrage rapide

```bash
# Prerequis : SDKMAN!
sdk env install

# Build
mvn clean verify
```

### Utilisation (a venir)

```java
@ApplicationScoped
public class MonService {

    @Inject
    MonRepository repository;

    public String traiter(int id) {
        return repository.trouver(id).nom();
    }
}
```

Test d'integration avec JUnit 6 :

```java
@VaubanTest
@AddBeans({MonService.class, MonRepository.class})
class MonServiceTest {

    @Inject
    MonService service;

    @Test
    void devraitTraiterCorrectement() {
        assertNotNull(service.traiter(1));
    }
}
```

### Validation

Le conteneur est valide contre le [CDI TCK 4.1](https://github.com/jakartaee/cdi-tck) officiel.

### Licence

[Apache License 2.0](LICENSE)

---

<a id="english"></a>

## CDI 4.1 Container, Java Modules Native

Vauban is a [Jakarta CDI 4.1](https://jakarta.ee/specifications/cdi/4.1/) implementation designed from the ground up for the Java Platform Module System (JPMS). Inspired by [Quarkus ArC](https://quarkus.io/guides/cdi-reference)'s build-time approach, Vauban generates all required code (proxies, interceptors, factories) at compile time, with zero runtime reflection.

### Philosophy

- **JPMS-first**: every component is an explicit Java module
- **Zero reflection**: static code generation at compile time via APT and Maven plugins
- **Pure JDK**: uses the JDK 25 Class-File API for bytecode generation, no ASM or ByteBuddy
- **Minimal dependencies**: the core (`vauban-indexer`) has zero external dependencies
- **Build Compatible Extensions**: supports the CDI Lite extension model (not Portable Extensions)

### Tech Stack

| Component | Version |
|-----------|---------|
| JDK | 25 (Temurin) |
| Maven | 4.0.0-rc-5 |
| CDI API | 4.1.0 |
| JUnit | 6.0.3 |

### Modules

```
vauban/
├── vauban-indexer        Class indexer (replaces Jandex), zero dependencies
├── vauban-api            Vauban public API
├── vauban-core           CDI container runtime
├── vauban-processor      Annotation processor + code generation
├── vauban-maven-plugin   Maven plugin (indexing, JPMS analysis)
├── vauban-junit          JUnit 6 extension for integration testing
├── vauban-tck-runner     CDI TCK 4.1 runner
└── vauban-test-suite     Integration test suite
```

### Quick Start

```bash
# Prerequisites: SDKMAN!
sdk env install

# Build
mvn clean verify
```

### Usage (coming soon)

```java
@ApplicationScoped
public class MyService {

    @Inject
    MyRepository repository;

    public String process(int id) {
        return repository.find(id).name();
    }
}
```

Integration testing with JUnit 6:

```java
@VaubanTest
@AddBeans({MyService.class, MyRepository.class})
class MyServiceTest {

    @Inject
    MyService service;

    @Test
    void shouldProcessCorrectly() {
        assertNotNull(service.process(1));
    }
}
```

### Validation

The container is validated against the official [CDI TCK 4.1](https://github.com/jakartaee/cdi-tck).

### License

[Apache License 2.0](LICENSE)
