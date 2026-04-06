<p align="center">
  <img src="vauban-logo.png" alt="Vauban" width="300">
</p>

<h1 align="center">Vauban</h1>

<p align="center">
  <strong>Conteneur CDI 4.1 natif Java Modules</strong><br>
  <a href="https://jakarta.ee/specifications/cdi/4.1/">CDI 4.1</a> | JDK 25 | JPMS | Zero reflexion
</p>

<p align="center">
  <img src="https://img.shields.io/badge/CDI_TCK_Lite-100%25-brightgreen" alt="TCK">
  <img src="https://img.shields.io/badge/SonarQube-0_issues-brightgreen" alt="Sonar">
  <img src="https://img.shields.io/badge/JDK-25-orange" alt="JDK">
  <img src="https://img.shields.io/badge/Maven-4.0--rc--5-purple" alt="Maven">
  <img src="https://img.shields.io/badge/license-Apache_2.0-green" alt="License">
</p>

---

> [English version below](#english)

## Qu'est-ce que Vauban ?

Vauban est une implementation de [Jakarta CDI 4.1](https://jakarta.ee/specifications/cdi/4.1/) concue des le depart pour le systeme de modules Java (JPMS). Il genere tout le code necessaire (factories, proxies, intercepteurs) a la compilation via l'API Class-File du JDK 25, sans aucune dependance bytecode externe.

### Pourquoi Vauban ?

| | Weld | ArC (Quarkus) | **Vauban** |
|---|---|---|---|
| Approche | Runtime / reflexion | Build-time / Jandex + ASM | **Build-time / Class-File API** |
| JPMS | Non | Non | **Natif** |
| Dependances bytecode | ASM / ByteBuddy | ASM | **Aucune** (JDK pur) |
| CDI Lite TCK | ~100% | ~100% | **100% (774/774)** |

### Philosophie

- **JPMS-first** : chaque composant est un module Java explicite (`module-info.java`)
- **Zero reflexion** : generation de code statique via l'API Class-File du JDK 25
- **Dependances minimales** : l'indexeur n'a aucune dependance externe
- **Build Compatible Extensions** : modele d'extensions CDI 4.1 Lite

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

### Deux modes d'utilisation

Vauban propose deux approches pour declarer les beans :

| Mode | Quand l'utiliser | Beans declares via |
|------|-----------------|-------------------|
| **Programmatique** | Tests unitaires, microservices, scripts | `addBeanClass()` — chaque bean est liste explicitement |
| **Annotation processor** | Applications Maven classiques | Scan automatique a la compilation via `vauban-processor` |

> **Note** : il n'y a pas de scan automatique du classpath au runtime. Le builder `VaubanContainer.builder()` ne prend que les classes ajoutees explicitement. Le scan "magique" est fait a la compilation par le processeur d'annotations (`vauban-processor`).

### Utilisation programmatique (tests, scripts)

Les beans sont declares un par un — ideal pour les tests unitaires ou l'on controle precisement le perimetre :

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

`@AddBeans` declare explicitement les classes a inclure dans le conteneur de test.
C'est voulu : dans un test unitaire, on maitrise exactement le perimetre d'injection — pas de scan classpath implicite, pas de bean inattendu.

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

### Intercepteurs

```java
@InterceptorBinding
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface Logged {}

@Logged @Interceptor @Priority(1000)
public class LogInterceptor {

    @AroundInvoke
    public Object log(InvocationContext ctx) throws Exception {
        System.out.println(">> " + ctx.getMethod().getName());
        return ctx.proceed();
    }
}

@ApplicationScoped
@Logged
public class MonService {
    public String traiter(int id) { return "OK"; }
}
```

---

## Architecture

```
vauban/
├── vauban-indexer        Indexeur de bytecode (remplace Jandex), zero dependance
├── vauban-api            API publique Vauban
├── vauban-core           Runtime du conteneur CDI 4.1 Lite
├── vauban-processor      Processeur d'annotations + generation de code
├── vauban-maven-plugin   Plugin Maven (indexation, analyse JPMS)
├── vauban-junit          Extension JUnit 6 pour tests CDI
├── vauban-tck-runner     Runner CDI TCK 4.1 (774/774)
└── vauban-test-suite     Suite de tests d'integration
```

---

## Modules

### vauban-indexer

**Role** : Scanner et indexer les fichiers `.class` sans reflexion ni dependances externes.

Equivalent fonctionnel de Jandex, mais utilise exclusivement l'API Class-File du JDK 25.
Produit un `VaubanIndex` immutable contenant les metadonnees de toutes les classes scannees.

| Classe | Role |
|--------|------|
| `ClassFileScanner` | Parse un fichier `.class` JDK 25 en `ClassInfo` |
| `JarScanner` | Scanne un JAR complet |
| `IndexBuilder` | Construit un index incrementalement |
| `VaubanIndex` | Index immutable — requetes par nom, annotation, supertype |
| `ClassInfo` | Metadonnees d'une classe (annotations, champs, methodes, supertypes) |
| `TypeInfo` | Representation des types (class, parameterized, wildcard, type variable, array) |
| `DotName` | Nom qualifie interne (`jakarta.inject.Inject`) |

**Module JPMS** : `fr.vidocq.vauban.indexer` — zero dependance externe.

```java
var index = new IndexBuilder()
    .scan(MyService.class)
    .scan(MyRepository.class)
    .build();

var beans = index.getAnnotatedClasses(DotName.of("jakarta.enterprise.context.ApplicationScoped"));
```

### vauban-api

**Role** : Point d'entree public pour les applications.

Fournit la facade `Vauban` et re-exporte les contrats CDI 4.1.

**Module JPMS** : `fr.vidocq.vauban.api` — depend de Jakarta CDI API (transitif).

### vauban-core

**Role** : Le coeur du conteneur CDI. Gere le cycle de vie des beans, l'injection, les scopes, les evenements, les intercepteurs et les Build Compatible Extensions.

#### Packages et classes cles

| Package | Classes cles | Role |
|---------|-------------|------|
| `container` | `VaubanContainer`, `VaubanBeanManager`, `ManagedBean`, `InstanceImpl` | Bootstrap, gestion des beans, cycle de vie |
| `bean.discovery` | `BeanDiscovery` | Decouverte CDI via l'index |
| `bean.model` | `BeanDescriptor`, `InterceptorDescriptor`, `ObserverDescriptor` | Modeles de metadonnees |
| `bean.resolution` | `BeanResolver`, `QualifierMatcher` | Resolution des beans par type + qualifiers |
| `bean.validation` | `ClassValidator`, `DeploymentValidator` | Validation DefinitionException / DeploymentException |
| `context` | `ApplicationContext`, `RequestContext`, `DependentContext` | Implementations des scopes |
| `event` | `EventDispatcher`, `EventImpl`, `VaubanObserverMethod` | Fire sync/async, matching des observeurs |
| `interceptor` | `InterceptorManager`, `VaubanInvocationContext`, `InterceptorSubclassGenerator` | Resolution, chaines, generation de sous-classes `$$Intercepted` |
| `extensions` | `BceProcessor`, `VaubanBuildServices` | Build Compatible Extensions (phases Discovery → Validation) |
| `langmodel` | `VaubanClassInfo`, `VaubanAnnotationMember` | CDI Language Model (`jakarta.enterprise.lang.model`) |
| `types` | `AssignabilityRules`, `TypeHierarchyResolver` | Regles d'assignabilite CDI 4.1 Section 2.4 |
| `proxy` | `RuntimeClientProxyGenerator` | Generation de client proxies via Class-File API |

#### Sequence de demarrage (`VaubanContainer.builder().build()`)

```
1. Collecte des classes beans (classpath ou programmatique)
2. BeanDiscovery : scan de l'index, decouverte annotated-mode
   ├── Beans manages (scopes, stereotypes)
   ├── Producers (@Produces methodes/champs)
   ├── Observeurs (@Observes / @ObservesAsync)
   └── Intercepteurs (@Interceptor + @InterceptorBinding)
3. ClassValidator : validation DefinitionException
4. BceProcessor : Build Compatible Extensions
   ├── @Discovery → @Enhancement → @Registration → @Synthesis → @Validation
   └── Beans/observeurs synthetiques
5. DeploymentValidator : validation DeploymentException
6. Creation des scopes (ApplicationContext, RequestContext, DependentContext)
7. InterceptorManager : resolution des chaines d'intercepteurs
8. EventDispatcher : enregistrement des observeurs
9. VaubanBeanManager : facade BeanManager CDI
10. Fire @Initialized(ApplicationScoped.class) et @Startup
```

#### Injection

Le `BeanResolver` resout les points d'injection par type + qualifiers :
- Supporte `@Inject` sur champs, constructeurs et methodes
- Qualifiers : `@Named`, `@Default`, `@Any`, qualifiers custom
- `@Typed` pour restreindre les types exposes
- `Instance<T>` pour le lookup programmatique
- `InjectionPoint` pour la metadata d'injection

#### Evenements

`EventDispatcher` gere le fire synchrone et asynchrone :
- Matching par type d'evenement (incluant les generiques) et qualifiers
- Tri par `@Priority` des observeurs
- Support `@Observes(during = IF_EXISTS)` conditionnel
- `fireAsync()` retourne `CompletionStage<U>`
- `EventMetadata` avec type runtime resolu

#### Intercepteurs

Generes via l'API Class-File — zero reflexion a l'execution :
- Sous-classe `BeanClass$$Intercepted` avec methodes overridees
- Bridge `$$super$methodName` pour l'appel a la methode originale
- `@AroundInvoke` et `@AroundConstruct`
- Matching des bindings par nom + valeurs membres
- Support des bindings herites et transitifs (via stereotypes)

**Module JPMS** : `fr.vidocq.vauban.core` — fournit `CDIProvider` et `BuildServices`.

### vauban-processor

**Role** : Processeur d'annotations (APT) qui genere les factories de beans et les client proxies a la compilation.

| Classe | Role |
|--------|------|
| `VaubanProcessor` | Point d'entree APT (`AbstractProcessor`) |
| `ElementScanner` | Convertit les elements APT en `ClassInfo` |
| `BeanFactoryGenerator` | Genere les implementations `BeanFactory<T>` |
| `ClientProxyGenerator` | Genere les client proxies pour les scopes normaux |

**Flux** :
1. APT detecte les annotations CDI
2. Scan des elements → `VaubanIndex`
3. `BeanDiscovery` + `DeploymentValidator`
4. Generation bytecode via Class-File API pour chaque bean manage

**Module JPMS** : `fr.vidocq.vauban.processor` — depend de `java.compiler`.

### vauban-junit

**Role** : Integration JUnit 6 (Jupiter) pour les tests CDI.

| Classe | Role |
|--------|------|
| `VaubanExtension` | Extension JUnit (`BeforeAllCallback`, `AfterAllCallback`, `TestInstancePostProcessor`) |
| `@VaubanTest` | Marque une classe de test pour CDI |
| `@AddBeans` | Declare les classes beans a inclure dans le conteneur de test |

**Fonctionnement** :
1. `@BeforeAll` : cree un `VaubanContainer` avec les classes `@AddBeans`
2. `PostProcessor` : injecte les champs `@Inject` de l'instance de test
3. `@AfterAll` : ferme le conteneur

```java
@VaubanTest
@AddBeans({MonService.class, MonRepository.class})
class MonServiceTest {
    @Inject MonService service;

    @Test
    void test() {
        assertNotNull(service.traiter(1));
    }
}
```

**Module JPMS** : `fr.vidocq.vauban.junit` — depend de `org.junit.jupiter.api`.

### vauban-maven-plugin

**Role** : Plugin Maven pour l'indexation des dependances et l'analyse JPMS au build.

Utilise uniquement `vauban-indexer` pour scanner les JARs de dependances.

### vauban-tck-runner

**Role** : Execute le CDI TCK 4.1 officiel contre Vauban.

**Stack** : Arquillian 1.8 + TestNG 7.9 + ShrinkWrap.

```bash
# Lancer le TCK complet
./run-tck.sh

# Un test specifique
./run-tck.sh -Dtest=EventMetadataTest
```

**Resultat actuel** : **774/774 tests CDI Lite (100%)**

### vauban-test-suite

**Role** : Tests d'integration couvrant les scenarios CDI de bout en bout (injection, scopes, evenements, intercepteurs, producers).

---

## Fonctionnalites CDI 4.1 Lite

| Fonctionnalite | Status |
|---|---|
| Managed beans (`@ApplicationScoped`, `@RequestScoped`, `@Dependent`, `@Singleton`) | ✅ |
| Injection (`@Inject` champs, constructeurs, methodes) | ✅ |
| Qualifiers (`@Named`, `@Default`, `@Any`, custom, `@Nonbinding`) | ✅ |
| Producers (`@Produces` methodes et champs) | ✅ |
| Disposers (`@Disposes`) | ✅ |
| Evenements (`Event<T>`, `@Observes`, `@ObservesAsync`) | ✅ |
| Stereotypes (`@Stereotype`) | ✅ |
| Alternatives (`@Alternative`, `@Priority`) | ✅ |
| `Instance<T>` programmatic lookup | ✅ |
| `InjectionPoint` metadata | ✅ |
| `@Typed` restriction de types | ✅ |
| `@Vetoed` (classe et package) | ✅ |
| Observer priority et conditional (`IF_EXISTS`) | ✅ |
| Intercepteurs (`@AroundInvoke`, `@AroundConstruct`) | ✅ |
| Client proxies (scopes normaux) | ✅ |
| Build Compatible Extensions (BCE) | ✅ |
| `@TransientReference` | ✅ |
| `EventMetadata` | ✅ |

## Validation TCK

Le conteneur est valide contre le [CDI TCK 4.1](https://github.com/jakartaee/cdi-tck) officiel.

```
CDI Lite TCK :  774/774 tests (100%)
CDI Full TCK :  non cible (futur module vauban-full)
```

| Profil | Scope | Status |
|--------|-------|--------|
| **CDI Lite** | Managed beans, injection, events, producers, intercepteurs, BCE | **100% TCK** |
| **CDI Full** | + Portable Extensions, decorators, conversation scope, EL | Futur (`vauban-full`) |

## Qualite

- **SonarQube** : 0 issue (0 bug, 0 vulnerabilite, 0 code smell ouvert)
- **JaCoCo** : couverture via le profil Maven `quality`

```bash
# Analyse qualite
mvn verify -Pquality -pl vauban-core
```

## Licence

[Apache License 2.0](LICENSE)

---

<a id="english"></a>

<p align="center">
  <img src="vauban-logo.png" alt="Vauban" width="200">
</p>

## CDI 4.1 Container, Java Modules Native

Vauban is a [Jakarta CDI 4.1](https://jakarta.ee/specifications/cdi/4.1/) implementation designed from the ground up for the Java Platform Module System (JPMS). It generates all required code (factories, proxies, interceptors) at compile time using the JDK 25 Class-File API, with zero external bytecode dependencies.

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

Managed beans, field/constructor/method injection, qualifiers, producers, disposers, events (sync & async), stereotypes, alternatives, `Instance<T>`, `InjectionPoint`, `@Typed`, `@Vetoed`, observer priority, `@Nonbinding`, interceptors (`@AroundInvoke`, `@AroundConstruct`), client proxies, Build Compatible Extensions, `@TransientReference`, `EventMetadata`.

### TCK Validation

```
CDI Lite TCK:  774/774 tests (100%)
CDI Full TCK:  not targeted (future vauban-full module)
```

### Modules

| Module | Purpose |
|--------|---------|
| `vauban-indexer` | Bytecode scanner and class indexer (replaces Jandex, zero dependencies) |
| `vauban-api` | Public API facade |
| `vauban-core` | CDI 4.1 Lite container runtime |
| `vauban-processor` | Annotation processor + code generation |
| `vauban-maven-plugin` | Maven plugin for dependency indexing |
| `vauban-junit` | JUnit 6 integration for CDI tests |
| `vauban-tck-runner` | CDI TCK 4.1 runner (774/774) |
| `vauban-test-suite` | Integration test suite |

### Architecture

The container boots in this sequence:
1. **Index** — Scan bean classes into `VaubanIndex` (Class-File API, no reflection)
2. **Discover** — `BeanDiscovery` finds beans, producers, observers, interceptors
3. **Validate** — `ClassValidator` (definition errors) + `DeploymentValidator` (deployment errors)
4. **Extend** — `BceProcessor` runs Build Compatible Extensions (@Discovery → @Validation)
5. **Wire** — Create scopes, register observers, resolve interceptor chains
6. **Start** — Fire `@Initialized(ApplicationScoped.class)` and `@Startup`

### License

[Apache License 2.0](LICENSE)
