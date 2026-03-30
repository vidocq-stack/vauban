<p align="center">
  <img src="vauban-logo.png" alt="Vauban" width="300">
</p>

<h1 align="center">Vauban</h1>

<p align="center">
  <strong>Conteneur CDI 4.1 natif Java Modules</strong><br>
  <a href="https://jakarta.ee/specifications/cdi/4.1/">CDI 4.1</a> | JDK 25 | JPMS | Zero reflexion
</p>

<p align="center">
  <img src="https://img.shields.io/badge/CDI_TCK_Lite-50%25-blue" alt="TCK">
  <img src="https://img.shields.io/badge/JDK-25-orange" alt="JDK">
  <img src="https://img.shields.io/badge/Maven-4.0--rc--5-purple" alt="Maven">
  <img src="https://img.shields.io/badge/license-Apache_2.0-green" alt="License">
</p>

---

> [English version below](#english)

## Qu'est-ce que Vauban ?

Vauban est une implementation de [Jakarta CDI 4.1](https://jakarta.ee/specifications/cdi/4.1/) concue des le depart pour le systeme de modules Java (JPMS). Il genere tout le code necessaire (factories, proxies) a la compilation via l'API Class-File du JDK 25, sans aucune reflexion a l'execution.

### Pourquoi Vauban ?

| | Weld | ArC (Quarkus) | **Vauban** |
|---|---|---|---|
| Approche | Runtime / reflexion | Build-time / Jandex + ASM | **Build-time / Class-File API** |
| JPMS | Non | Non | **Natif** |
| Dependances bytecode | ASM / ByteBuddy | ASM | **Aucune** (JDK pur) |
| CDI Lite | Oui | Oui | **Oui (50% TCK)** |

### Philosophie

- **JPMS-first** : chaque composant est un module Java explicite
- **Zero reflexion** : generation de code statique via l'API Class-File du JDK 25
- **Dependances minimales** : le coeur n'a aucune dependance externe
- **Build Compatible Extensions** : modele d'extensions CDI Lite

## Demarrage rapide

### Prerequis

- JDK 25 (Temurin)
- Maven 4.0.0-rc-5

```bash
# Avec SDKMAN!
sdk env install
```

### Build

```bash
mvn clean verify
```

### Utilisation programmatique

```java
import fr.vidocq.vauban.core.container.VaubanContainer;

var container = VaubanContainer.builder()
    .addBeanClass(MonService.class)
    .addBeanClass(MonRepository.class)
    .build();

var service = container.select(MonService.class);
service.traiter(42);

container.close();
```

### Beans CDI

```java
@ApplicationScoped
public class MonService {

    @Inject
    MonRepository repository;

    public String traiter(int id) {
        return repository.trouver(id).nom();
    }
}

@Dependent
public class MonRepository {
    public Record trouver(int id) {
        return new Record(id, "Item " + id);
    }

    public record Record(int id, String nom) {}
}
```

### Tests avec JUnit 6

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

### Evenements CDI

```java
@ApplicationScoped
public class NotificationService {

    @Inject
    Event<Commande> commandeEvent;

    public void passer(Commande cmd) {
        commandeEvent.fire(cmd);
    }
}

@ApplicationScoped
public class AuditService {

    void onCommande(@Observes Commande cmd) {
        System.out.println("Commande recue: " + cmd);
    }
}
```

## Architecture

```
vauban/
├── vauban-indexer        Indexeur de classes (remplace Jandex), zero dependance
├── vauban-api            API publique Vauban
├── vauban-core           Runtime du conteneur CDI Lite
├── vauban-processor      Processeur d'annotations + generation de code
├── vauban-maven-plugin   Plugin Maven (indexation, analyse JPMS)
├── vauban-junit          Extension JUnit 6 pour tests d'integration
├── vauban-tck-runner     Runner CDI TCK 4.1
└── vauban-test-suite     Suite de tests d'integration
```

### Fonctionnalites CDI Lite supportees

| Fonctionnalite | Status |
|---|---|
| Managed beans (`@ApplicationScoped`, `@RequestScoped`, `@Dependent`, `@Singleton`) | OK |
| Injection (`@Inject` champs, constructeurs, methodes) | OK |
| Qualifiers (`@Named`, `@Default`, `@Any`, custom) | OK |
| Producers (`@Produces` methodes et champs) | OK |
| Disposers (`@Disposes`) | OK |
| Evenements (`Event<T>`, `@Observes`, `@ObservesAsync`) | OK |
| Stereotypes (`@Stereotype`) | OK |
| Alternatives (`@Alternative`, `@Priority`) | OK |
| `Instance<T>` programmatic lookup | OK |
| `InjectionPoint` metadata | OK |
| `@Typed` restriction de types | OK |
| `@Vetoed` (classe et package) | OK |
| Observer priority et conditional (`IF_EXISTS`) | OK |
| `@Nonbinding` dans les qualifiers | OK |
| Intercepteurs runtime | En cours |
| Build Compatible Extensions (BCE) | En cours |

### Validation TCK

Le conteneur est valide contre le [CDI TCK 4.1](https://github.com/jakartaee/cdi-tck) officiel.
Vauban cible **CDI Lite** (le profil standard pour les environnements non-EE).

```
CDI Lite TCK :  385/769 tests (50%)
CDI Full TCK :  non cible (futur module vauban-full)
```

| Profil | Scope | Status |
|--------|-------|--------|
| **CDI Lite** | Managed beans, injection, events, producers, stereotypes, alternatives | **50% TCK** |
| **CDI Full** | + Portable Extensions, decorators, conversation scope, EL | Futur (`vauban-full`) |

Les 50% restants du TCK Lite sont principalement : intercepteurs runtime (~70 tests),
matching de types parametres (~30), client proxies (~15), Build Compatible Extensions (~10).

```bash
# Lancer le TCK Lite
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```

## Licence

[Apache License 2.0](LICENSE)

---

<a id="english"></a>

<p align="center">
  <img src="vauban-logo.png" alt="Vauban" width="200">
</p>

## CDI 4.1 Container, Java Modules Native

Vauban is a [Jakarta CDI 4.1](https://jakarta.ee/specifications/cdi/4.1/) implementation designed from the ground up for the Java Platform Module System (JPMS). It generates all required code (factories, proxies) at compile time using the JDK 25 Class-File API, with zero runtime reflection.

### Quick Start

```bash
# Prerequisites: JDK 25 + Maven 4.0.0-rc-5
sdk env install
mvn clean verify
```

### Programmatic Usage

```java
var container = VaubanContainer.builder()
    .addBeanClass(MyService.class)
    .addBeanClass(MyRepository.class)
    .build();

var service = container.select(MyService.class);
container.close();
```

### Testing with JUnit 6

```java
@VaubanTest
@AddBeans({MyService.class, MyRepository.class})
class MyServiceTest {

    @Inject MyService service;

    @Test
    void shouldWork() {
        assertNotNull(service.process(1));
    }
}
```

### CDI Lite Features

Managed beans, field/constructor/method injection, qualifiers, producers, disposers, events, stereotypes, alternatives, `Instance<T>`, `InjectionPoint`, `@Typed`, `@Vetoed`, observer priority, `@Nonbinding`.

### TCK Validation

```
CDI Lite TCK:  385/769 tests (50%)
CDI Full TCK:  not targeted (future vauban-full module)
```

Remaining 50% of CDI Lite: runtime interceptors (~70), parameterized type matching (~30),
client proxies (~15), Build Compatible Extensions (~10).

### License

[Apache License 2.0](LICENSE)
