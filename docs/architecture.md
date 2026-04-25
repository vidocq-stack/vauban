# Architecture Vauban

> Vue d'ensemble de l'architecture du conteneur CDI 4.1 Vauban.

## Modules

```mermaid
graph TB
    subgraph "Build-time"
        PROC[vauban-processor<br/><i>APT: factories, proxies</i>]
        PLUGIN[vauban-maven-plugin<br/><i>Scan JARs, pre-gen</i>]
    end

    subgraph "Runtime"
        API[vauban-api<br/><i>Facade publique</i>]
        CORE[vauban-core<br/><i>Conteneur CDI 4.1</i>]
        IDX[vauban-indexer<br/><i>Scanner bytecode</i>]
    end

    subgraph "Testing"
        JUNIT[vauban-junit<br/><i>Extension JUnit 6</i>]
        TCK[vauban-tck-runner<br/><i>CDI TCK 4.1</i>]
        SUITE[vauban-test-suite<br/><i>Tests integration</i>]
    end

    subgraph "External"
        CDI[Jakarta CDI 4.1 API]
        JDK[JDK 25<br/><i>Class-File API</i>]
    end

    API --> CORE
    CORE --> IDX
    CORE --> CDI
    CORE --> JDK
    PROC --> CORE
    PROC --> IDX
    PLUGIN --> CORE
    PLUGIN --> IDX
    JUNIT --> CORE
    TCK --> CORE
    SUITE --> CORE
    IDX --> JDK

    style CORE fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    style IDX fill:#f3e5f5,stroke:#7b1fa2
    style API fill:#e8f5e9,stroke:#388e3c
    style PROC fill:#fff3e0,stroke:#f57c00
    style PLUGIN fill:#fff3e0,stroke:#f57c00
```

Chaque module est un **module JPMS explicite** avec son `module-info.java`.

| Module | Dependances externes |
|--------|---------------------|
| `vauban-indexer` | **Aucune** (JDK pur) |
| `vauban-core` | Jakarta CDI API, Jakarta Inject, Jakarta Interceptors |
| `vauban-api` | Jakarta CDI API (transitif) |
| `vauban-processor` | `java.compiler` (APT) |
| `vauban-junit` | JUnit Jupiter |

---

## Packages de vauban-core

```mermaid
graph LR
    subgraph container["container"]
        VC[VaubanContainer]
        VBM[VaubanBeanManager]
        MB[ManagedBean]
        BI[BeanInjector]
        BL[BeanLifecycle]
        IBW[InterceptorBeanWrapper]
        INST[InstanceImpl]
    end

    subgraph discovery["bean.discovery"]
        BD[BeanDiscovery]
    end

    subgraph model["bean.model"]
        DESC[BeanDescriptor]
        IDESC[InterceptorDescriptor]
        ODESC[ObserverDescriptor]
    end

    subgraph resolution["bean.resolution"]
        BR[BeanResolver]
        QM[QualifierMatcher]
    end

    subgraph validation["bean.validation"]
        CV[ClassValidator]
        DV[DeploymentValidator]
    end

    subgraph ctx["context"]
        AC[ApplicationContext]
        RC[RequestContext]
        DC[DependentContext]
    end

    subgraph evt["event"]
        ED[EventDispatcher]
        EI[EventImpl]
    end

    subgraph intc["interceptor"]
        IM[InterceptorManager]
        ISG[InterceptorSubclassGenerator]
        VIC[VaubanInvocationContext]
    end

    subgraph ext["extensions"]
        BCE[BceProcessor]
        VBS[VaubanBuildServices]
    end

    subgraph types["types"]
        AR[AssignabilityRules]
    end

    subgraph proxy["proxy"]
        RCPG[RuntimeClientProxyGenerator]
    end

    VC --> BD
    VC --> BR
    VC --> IM
    VC --> ED
    VC --> BCE
    BD --> DESC
    BR --> QM
    BR --> AR
    IM --> ISG
    IM --> VIC

    style VC fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
```

---

## Pipeline de build complet

Les outils Vauban interviennent a **trois moments distincts** du cycle de build Maven :

```mermaid
flowchart TD
    subgraph C["① compile — vauban-processor"]
        C1["Scan annotations CDI"]
        C2["BCE Discovery / Enhancement\n/ Registration / Synthesis / Validation"]
        C3["Genere _Factory.class\n_ClientProxy.class"]
        C4["META-INF/vauban-beans.list\nMETA-INF/vauban-bce-processed\nMETA-INF/vauban-bce-runtime.list\nMETA-INF/vauban-synthetic-metadata.properties"]
        C1 --> C2 --> C3 --> C4
    end

    subgraph PC["② process-classes — vauban:generate"]
        PC1["Scan JARs de dependances\n(skip si vauban-beans.list present)"]
        PC2["BCE Enhancement\nsur JARs sans marqueur"]
        PC3["Pre-genere _ClientProxy.class\n$$Intercepted.class\n(skip si deja sur disque)"]
        PC4["META-INF/vauban-beans.list\n(merge avec connus)"]
        PC1 --> PC2 --> PC3 --> PC4
    end

    subgraph RT["③ runtime — VaubanContainer"]
        RT1["scanClasspath()\nLit tous les vauban-beans.list"]
        RT2{"Source a\nvauban-bce-processed ?"}
        RT3["replayEnhancementForTargets\n(bce-runtime.list)"]
        RT4["processEnhancementOnly\n(fallback runtime)"]
        RT5["Charge synthetic-metadata.properties\nDemarre le conteneur"]
        RT1 --> RT2
        RT2 -->|"Oui"| RT3
        RT2 -->|"Non"| RT4
        RT3 & RT4 --> RT5
    end

    C -->|".class + META-INF/*"| PC
    PC -->|"_ClientProxy.class\n$$Intercepted.class\nvauban-beans.list"| RT

    style C fill:#fff3e0,stroke:#f57c00
    style PC fill:#e8f5e9,stroke:#2e7d32
    style RT fill:#e3f2fd,stroke:#1565c0
```

**Regle d'or** : chaque outil evite le double traitement.
- `vauban-processor` genere uniquement les beans du module courant.
- `vauban:generate` skips les JARs/repertoires qui ont deja un `vauban-beans.list`.
- `VaubanContainer` skips le BCE pour les sources marquees `vauban-bce-processed`.

---

## Processus APT (vauban-processor)

`VaubanProcessor extends AbstractProcessor` est le point d'entree de l'annotation processing.
Il est declenche par `javac` pendant la phase `compile`.

### Detection des BCEs et annotations trigger

```mermaid
sequenceDiagram
    participant javac
    participant VP as VaubanProcessor
    participant SL as ServiceLoader
    participant BCE1 as BuildCompatibleExtension

    javac->>VP: init(ProcessingEnvironment)
    VP->>SL: ServiceLoader.load(BuildCompatibleExtension)
    SL-->>VP: [BCE1, BCE2, ...]
    VP->>BCE1: getDeclaredMethods()
    Note over VP,BCE1: Cherche @Enhancement(withAnnotations=...)
    BCE1-->>VP: [@Enhancement(withAnnotations=Path.class), ...]
    VP->>VP: bceAnnotationTypes += "jakarta.ws.rs.Path"
    VP-->>javac: getSupportedAnnotationTypes()<br/>= CDI_ANNOTATIONS ∪ bceAnnotationTypes
    Note over javac: javac scanne maintenant aussi @Path !
```

**Pourquoi cette etape est critique** : sans extraire les annotations `withAnnotations` des BCEs,
`javac` n'inclurait pas les classes `@Path` dans le `RoundEnvironment`. Les classes
non-CDI enrichies par BCE seraient invisibles au processeur.

### Flux de traitement (methode process)

```mermaid
flowchart TD
    START["process(annotations, roundEnv)"] --> SCAN
    SCAN["ElementScanner → IndexBuilder\nScan tous les TypeElement annotes"] --> IDX["VaubanIndex"]

    IDX --> DISC_PHASE["BCE @Discovery\nBceProcessor.processDiscovery()"]
    DISC_PHASE --> META["MetaAnnotations\n(qualifiers, interceptorBindings,\nstereotypes, nonbinding)"]
    DISC_PHASE --> SCANNED["ScannedClasses\n(classes ajoutees a l'index)"]
    DISC_PHASE --> INSTANCES["bceInstances\n(instances BCE pour phases suivantes)"]

    META --> BD["BeanDiscovery\navec qualifiers/stereotypes custom"]
    SCANNED -->|"bytes via ClassLoader"| BD
    BD --> BEANS["BeanDescriptor[]"]

    BEANS --> REMAINING["BceProcessor.process()\n@Enhancement → @Registration\n→ @Synthesis → @Validation"]
    INSTANCES -->|"reutilises"| REMAINING

    REMAINING --> ENH_MODS["enhancementModifications\n(annotations ajoutees/supprimees)"]
    REMAINING --> SYNTH["syntheticBeans\nsyntheticObservers"]
    REMAINING --> ERRORS["definitionErrors\ndeploymentErrors"]

    ERRORS -->|"si non vide"| COMPILE_ERR["Diagnostic.Kind.ERROR\n→ echec compilation"]
    ENH_MODS --> APPLY["applyEnhancements()\nMaj BeanDescriptor"]
    ENH_MODS --> PROMOTE["promoteEnhancedClasses()\nNon-beans → beans si scope ajoute"]
    ENH_MODS --> RUNTIME_LIST["Ecrire\nvauban-bce-runtime.list"]

    APPLY --> VALIDATE["DeploymentValidator"]
    PROMOTE --> VALIDATE
    VALIDATE --> CODEGEN["Generer par bean MANAGED:\n• BeanFactory_T.class\n• _ClientProxy.class (si scope normal)"]

    CODEGEN --> WRITE["Ecrire META-INF/\n• vauban-beans.list\n• vauban-bce-processed\n• vauban-synthetic-metadata.properties"]

    style COMPILE_ERR fill:#ffebee,stroke:#c62828
    style WRITE fill:#e3f2fd,stroke:#1565c0
```

### Matching triple dans @Enhancement

Pour chaque methode `@Enhancement`, `BceProcessor` tente de trouver les classes cibles
par **trois chemins complementaires** :

```mermaid
flowchart LR
    subgraph "Chemin 1 : Beans CDI"
        B1["BeanDescriptor.types()\n→ matchesTypes()"]
    end
    subgraph "Chemin 2 : Archive classes"
        B2["Class.forName() sur index\n→ matchesClass() + matchesAnnotations()"]
    end
    subgraph "Chemin 3 : Index-based"
        B3["ClassInfo.hasAnnotation()\nPour classes en cours de compilation\nnon chargeables via ClassLoader"]
    end

    ALL["processEnhancement()"] --> B1 & B2 & B3
    B1 & B2 & B3 --> DEDUP["Set processedClasses\n(evite le double traitement)"]
    DEDUP --> INVOKE["invokeEnhancement()"]
```

Le chemin 3 (index-based) est essentiel : les classes **en cours de compilation** ne sont
pas encore sur le classpath et ne peuvent pas etre chargees via `Class.forName()`. L'index
`VaubanIndex` construit par `ElementScanner` en phase APT est la seule source de verite.

---

## Plugin Maven (vauban:generate)

Le goal `vauban:generate` s'execute en phase `process-classes` — **apres** `compile`. Les `.class`
du projet sont deja generes (par javac + APT). Le plugin traite les **JARs de dependances**.

```mermaid
flowchart TD
    START["GenerateMojo.execute()"] --> COLLECT["collectDependencyJars()\nproject.getArtifacts() → *.jar + dirs"]
    COLLECT --> CL["buildClassLoader()\nURLClassLoader(jars + projectClasses)"]

    CL --> GEN["VaubanGenerator.generate(config)"]

    subgraph "VaubanGenerator (logique core)"
        GEN --> SCAN_DEPS["Pour chaque JAR/repertoire de dependance :"]
        SCAN_DEPS --> HASLIST{"vauban-beans.list\npresent ?"}
        HASLIST -->|"Oui"| KNOWN["alreadyKnownBeans.addAll()\n(pas de re-scan)"]
        HASLIST -->|"Non"| JAR_SCAN["JarScanner.scan() ou\nscanClassesDirectory()\n→ IndexBuilder"]

        GEN --> SCAN_PROJECT["Repertoire projet :"]
        SCAN_PROJECT --> PROJ_KNOWN{"vauban-beans.list\npresent ? (mis par APT)"}
        PROJ_KNOWN -->|"Oui"| PROJ_SKIP["alreadyKnownBeans.addAll()\n(APT a deja traite)"]
        PROJ_KNOWN -->|"Non"| PROJ_IDX["scanClassesDirectory()"]

        JAR_SCAN --> INDEX["VaubanIndex merge"]
        PROJ_IDX --> INDEX

        INDEX --> BCE_MV["BCE via ServiceLoader\nprocessEnhancementOnly()\nEnrichissement index"]
        BCE_MV --> DISC["BeanDiscovery sur index enrichi"]
        DISC --> NEW_BEANS["nouveaux BeanDescriptors\n(non dans alreadyKnownBeans)"]

        NEW_BEANS --> PROXY_GEN["Pour chaque bean MANAGED :"]
        PROXY_GEN --> CP_EXISTS{"_ClientProxy.class\ndeja sur disque ?"}
        CP_EXISTS -->|"Non"| GEN_CP["RuntimeClientProxyGenerator\n→ ecrire _ClientProxy.class"]
        CP_EXISTS -->|"Oui"| SKIP_CP["Skip (idempotent)"]

        PROXY_GEN --> INT_EXISTS{"$$Intercepted.class\ndeja sur disque ?"}
        INT_EXISTS -->|"Non"| GEN_INT["InterceptorSubclassGenerator\n→ ecrire $$Intercepted.class"]
        INT_EXISTS -->|"Oui"| SKIP_INT["Skip (idempotent)"]

        NEW_BEANS --> MERGE["Fusionner avec alreadyKnownBeans"]
        MERGE --> WRITE_LIST["Ecrire META-INF/vauban-beans.list\n(trie, deduplique)"]
    end

    style KNOWN fill:#e8f5e9,stroke:#2e7d32
    style PROJ_SKIP fill:#e8f5e9,stroke:#2e7d32
    style WRITE_LIST fill:#e3f2fd,stroke:#1565c0
```

**BCE dans le plugin** : `VaubanGenerator` decouvre les BCEs via `ServiceLoader` sur le
`URLClassLoader` construit a partir des JARs du projet. Il appelle
`BceProcessor.processEnhancementOnly()` — uniquement la phase `@Enhancement` —
pour enrichir l'index avant `BeanDiscovery`. Les classes non-CDI (ex: `@Path`) des JARs
sont ainsi promues en beans avant la generation des proxies.

**Le plugin ne genere pas** `vauban-bce-processed` ni `vauban-bce-runtime.list` :
ces fichiers sont la responsabilite de l'APT.

---

## Sequence de demarrage

```mermaid
sequenceDiagram
    participant App as Application
    participant B as Builder
    participant BD as BeanDiscovery
    participant CV as ClassValidator
    participant BCE as BceProcessor
    participant DV as DeploymentValidator
    participant VC as VaubanContainer

    App->>B: VaubanContainer.builder()
    App->>B: scanLocal() / addBeanClass()
    App->>B: build()

    B->>BD: Indexation des classes
    BD->>BD: Scan annotations CDI
    BD-->>B: BeanDescriptor[]<br/>InterceptorDescriptor[]<br/>ObserverDescriptor[]

    B->>CV: Validation definitions
    CV-->>B: DefinitionException?

    B->>BCE: Build Compatible Extensions
    Note over BCE: @Discovery
    Note over BCE: @Enhancement
    Note over BCE: @Registration
    Note over BCE: @Synthesis
    Note over BCE: @Validation
    BCE-->>B: Beans/observers synthetiques

    B->>DV: Validation deploiement
    DV-->>B: DeploymentException?

    B->>VC: Creation conteneur
    VC->>VC: Init scopes (App, Request, Dependent)
    VC->>VC: Init InterceptorManager
    VC->>VC: Init EventDispatcher
    VC->>VC: Fire @Initialized(ApplicationScoped)
    VC->>VC: Instantier @Startup beans
    VC-->>App: VaubanContainer
```

---

## Cycle de vie d'un bean

```mermaid
stateDiagram-v2
    [*] --> Discovered: BeanDiscovery scan
    Discovered --> Validated: ClassValidator + DeploymentValidator
    Validated --> Registered: ManagedBean cree
    Registered --> Resolving: container.select(Type)
    Resolving --> CtorInjection: constructeur Inject ou default
    CtorInjection --> FieldInjection: BeanInjector.injectFields
    FieldInjection --> PostConstruct: champs injectes
    PostConstruct --> Active: PostConstruct execute
    Active --> Active: Appels methodes
    Active --> PreDestroy: scope ferme ou destroy
    PreDestroy --> [*]: PreDestroy execute
```

---

## Resolution d'injection

```mermaid
flowchart TD
    IP["Point d'injection\n@Inject MyService svc"] --> TYPE{Type demande}

    TYPE -->|Instance/Provider| INSTANCE[InstanceImpl<br/>Lookup programmatique]
    TYPE -->|Event| EVENT[EventImpl<br/>Fire evenements]
    TYPE -->|BeanManager| BM[VaubanBeanManager<br/>Facade CDI]
    TYPE -->|InjectionPoint| IPN[Metadata InjectionPoint]
    TYPE -->|Bean classique| RESOLVE

    RESOLVE[BeanResolver.resolve] --> MATCH{Beans trouves?}
    MATCH -->|0| UNSAT[UnsatisfiedResolutionException]
    MATCH -->|1| FOUND[Bean resolu]
    MATCH -->|2+| AMBIG{Alternatives?}

    AMBIG -->|@Priority| PRIO[Plus haute priorite]
    AMBIG -->|Aucune| AMB_ERR[AmbiguousResolutionException]
    PRIO --> FOUND

    FOUND --> SCOPE{Scope?}
    SCOPE -->|@Dependent| DIRECT[Instance directe]
    SCOPE -->|@ApplicationScoped| PROXY[Client proxy]
    SCOPE -->|@RequestScoped| PROXY
    PROXY --> CTX[Context.get<br/>ou create]
    DIRECT --> CTX

    style IP fill:#e1f5fe,stroke:#0288d1
    style FOUND fill:#e8f5e9,stroke:#388e3c
    style UNSAT fill:#ffebee,stroke:#c62828
    style AMB_ERR fill:#ffebee,stroke:#c62828
```

---

## Evenements asynchrones

```mermaid
sequenceDiagram
    participant App as Application
    participant E as Event
    participant ED as EventDispatcher
    participant VT as Virtual Thread
    participant O1 as Observer 1
    participant O2 as Observer 2

    App->>E: fireAsync(event)
    E->>ED: fireAsync(event, null)
    ED->>VT: supplyAsync(task, virtualThreadExecutor)
    activate VT
    Note over VT: Virtual thread demarre
    VT->>O1: invokeObserver()
    O1-->>VT: ok
    VT->>O2: invokeObserver()
    O2-->>VT: ok
    VT-->>App: CompletionStage
    deactivate VT
```

Par defaut, les observers `@ObservesAsync` sont executes sur un **virtual thread** via
`Executors.newVirtualThreadPerTaskExecutor()`. Ce comportement est configurable — voir
[docs/configuration.md](configuration.md).

**Priorite de l'executor :**
1. `NotificationOptions.ofExecutor(customExecutor)` — passe par l'utilisateur
2. Virtual thread executor — defaut Vauban
3. `ForkJoinPool.commonPool()` — si `VaubanUsePlatformThreadsForAsyncEvents=true`

---

## Chaine d'interception

```mermaid
sequenceDiagram
    participant C as Client
    participant P as Proxy $$Intercepted
    participant IM as InterceptorManager
    participant I1 as Interceptor 1
    participant I2 as Interceptor 2
    participant B as Bean (super)

    C->>P: service.methode(args)
    P->>IM: resolveChainForMethod()
    IM-->>P: [Invocation1, Invocation2]
    P->>P: new VaubanInvocationContext

    P->>I1: @AroundInvoke
    I1->>I1: pre-processing
    I1->>P: ctx.proceed()

    P->>I2: @AroundInvoke
    I2->>I2: pre-processing
    I2->>P: ctx.proceed()

    P->>B: $$super$methode(args)
    B-->>P: resultat
    P-->>I2: resultat
    I2->>I2: post-processing
    I2-->>I1: resultat
    I1->>I1: post-processing
    I1-->>C: resultat
```

Le code genere par `InterceptorSubclassGenerator` via l'API Class-File du JDK 25 :

```
BeanClass$$Intercepted extends BeanClass
├── methode()        → resolve chain → VaubanInvocationContext.proceed()
├── $$super$methode() → super.methode()  (bridge vers la methode originale)
└── $$manager        → reference InterceptorManager
```

---

## Gestion des scopes avec ScopedValue

```mermaid
flowchart TB
    subgraph "Virtual Thread compatible"
        SV1["ScopedValue[InterceptionState]\nInterceptorManager"]
        SV2["ScopedValue[InjectionPoint]\nVaubanContainer"]
        SV3["ScopedValue[Set[String]]\nCycle detection"]
        SV4["ScopedValue[Boolean]\nInterceptorBeanWrapper"]
        SV5["ScopedValue[RequestContextState]\nRequestContext"]
    end

    subgraph "Scopes CDI"
        APP[ApplicationContext<br/>ConcurrentHashMap<br/><i>1 instance / conteneur</i>]
        REQ[RequestContext<br/>ScopedValue + fallback<br/><i>1 instance / requete</i>]
        DEP[DependentContext<br/>Pas de stockage<br/><i>1 instance / injection</i>]
    end

    SV5 -.->|state| REQ

    style SV1 fill:#e8eaf6,stroke:#3f51b5
    style SV2 fill:#e8eaf6,stroke:#3f51b5
    style SV3 fill:#e8eaf6,stroke:#3f51b5
    style SV4 fill:#e8eaf6,stroke:#3f51b5
    style SV5 fill:#e8eaf6,stroke:#3f51b5
    style APP fill:#c8e6c9,stroke:#2e7d32
    style REQ fill:#fff9c4,stroke:#f9a825
    style DEP fill:#ffccbc,stroke:#bf360c
```

**Pourquoi ScopedValue ?**

- Compatible virtual threads (JDK 25, JEP 487 finalise)
- Pas de fuite memoire (lie au scope, pas au thread)
- Herite automatiquement dans les child scopes
- Immutable par design (pas de `set()`, seulement `where().run()`)

---

## Build Compatible Extensions (BCE)

### Phases du cycle de vie

```mermaid
flowchart LR
    D["@Discovery<br/><i>Decouverte de types</i>"] --> E["@Enhancement<br/><i>Modification annotations</i>"]
    E --> R["@Registration<br/><i>Enregistrement beans</i>"]
    R --> S["@Synthesis<br/><i>Beans synthetiques</i>"]
    S --> V["@Validation<br/><i>Verification finale</i>"]

    D ---|ScannedClasses\nMetaAnnotations| E
    E ---|ClassConfig\nFieldConfig\nMethodConfig| R
    R ---|BeanInfo\nInterceptorInfo\nObserverInfo| S
    S ---|SyntheticBeanBuilder\nSyntheticObserverBuilder| V

    style D fill:#e3f2fd,stroke:#1565c0
    style E fill:#e8f5e9,stroke:#2e7d32
    style R fill:#fff3e0,stroke:#ef6c00
    style S fill:#fce4ec,stroke:#c62828
    style V fill:#f3e5f5,stroke:#6a1b9a
```

### Detection des BCEs

Les BCEs sont decouvertes via **Java ServiceLoader** :

```
META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
```

Chaque ligne du fichier de services est le nom qualifie d'une classe implementant `BuildCompatibleExtension`.
L'APT (`VaubanProcessor.init()`), le plugin Maven (`VaubanGenerator`) et le runtime
(`VaubanContainer`) appellent tous `ServiceLoader.load(BuildCompatibleExtension.class, classLoader)`
au demarrage de leur phase respective.

```mermaid
flowchart TD
    SVC["META-INF/services/\njakarta.enterprise.inject.build.compatible.spi\n.BuildCompatibleExtension"]
    SVC --> SL["ServiceLoader.load(...)"]
    SL --> BCE_LIST["[RestBce.class, SecurityBce.class, ...]"]
    BCE_LIST --> INSPECT["Pour chaque BCE :\ngetDeclaredMethods()\nfiltrer @Enhancement\nextrait withAnnotations=..."]
    INSPECT --> TRIGGERS["Annotations trigger\n[@Path, @Transactional, ...]"]

    subgraph "APT (init)"
        TRIGGERS --> SUPPORTED["getSupportedAnnotationTypes()\n+= triggers"]
        SUPPORTED --> JAVAC["javac inclut les classes\nannotees par ces triggers"]
    end

    subgraph "Maven plugin"
        BCE_LIST --> ENH_ONLY["processEnhancementOnly()\nSur classes JARs de dependances"]
    end

    subgraph "Runtime"
        BCE_LIST --> RUNTIME_BCE["processEnhancementOnly()\nou replayEnhancementForTargets()"]
    end

    style SVC fill:#fff3e0,stroke:#f57c00
    style SUPPORTED fill:#e3f2fd,stroke:#1565c0
```

### Execution a trois niveaux

```mermaid
flowchart TD
    subgraph A["① Compile-time (APT)"]
        A1["ServiceLoader → BCEs\ninit() de VaubanProcessor"]
        A2["Toutes les phases\n@Discovery → @Validation"]
        A3["Ecrit vauban-bce-processed\n+ vauban-bce-runtime.list\n+ vauban-synthetic-metadata.properties"]
        A1 --> A2 --> A3
    end

    subgraph B["② Build-time (vauban:generate)"]
        B1["ServiceLoader → BCEs\nVaubanGenerator"]
        B2["Phase @Enhancement uniquement\nprocessEnhancementOnly()"]
        B3["Enrichit l'index avant BeanDiscovery\nPas de marqueur ecrit"]
        B1 --> B2 --> B3
    end

    subgraph C["③ Runtime (VaubanContainer)"]
        C1["scanClasspath() lit toutes sources"]
        C2{"Source a\nvauban-bce-processed ?"}
        C3["Source pre-traitee :\nreplayEnhancementForTargets()\n(bce-runtime.list)"]
        C4["Source non traitee :\nprocessEnhancementOnly()\n(runtime fallback)"]
        C1 --> C2
        C2 -->|"Oui"| C3
        C2 -->|"Non"| C4
    end

    A -->|".class + META-INF/*"| B
    B -->|"_ClientProxy.class\n$$Intercepted.class"| C

    style A fill:#fff3e0,stroke:#f57c00
    style B fill:#e8f5e9,stroke:#2e7d32
    style C fill:#e3f2fd,stroke:#1565c0
```

### Le fichier vauban-bce-runtime.list en detail

Ce fichier resout un probleme de coherence : l'APT a determine quelles classes correspondent
aux filtres `@Enhancement`, mais le bytecode de ces classes **n'est pas reecrit** pour
encoder cette information. Au runtime, le conteneur doit pouvoir rejouer exactement les memes
associations (BCE, classe cible) sans refaire le matching.

Format (une paire par ligne) :
```
# Vauban BCE runtime replay list — generated at compile time by APT
com.example.RestBce;com.example.HelloResource
com.example.RestBce;com.example.UserResource
com.example.SecurityBce;com.example.AdminResource
```

Chargement runtime :
```
META-INF/vauban-bce-runtime.list
  ligne : "bceFqn;targetFqn"
  → Class.forName(bceFqn), Class.forName(targetFqn)
  → BceProcessor.replayEnhancementForTargets(pairs, index)
```

`replayEnhancementForTargets()` bypass tout le matching de types/annotations : les paires
sont invoquees directement. Performance constante, O(n) en nombre de paires.

---

## Generation de code (Class-File API JDK 25)

```mermaid
flowchart TD
    subgraph "Compile-time (vauban-processor)"
        APT[Annotation Processing] --> EF["BeanFactory[T]"]
        APT --> CP[ClientProxy]
        APT --> LIST_APT[vauban-beans.list]
        BCE_APT["BCE Enhancement"] -.->|enrichissement| APT
    end

    subgraph "Build-time (vauban-maven-plugin)"
        SCAN[Scan JARs] --> PRE_CP[Pre-gen ClientProxy]
        SCAN --> PRE_INT[Pre-gen $$Intercepted]
        SCAN --> LIST[vauban-beans.list]
        BCE_MV["BCE Enhancement"] -.->|enrichissement| SCAN
    end

    subgraph "Runtime (vauban-core)"
        RT_CP[RuntimeClientProxyGenerator]
        RT_INT[InterceptorSubclassGenerator]
    end

    subgraph "JDK 25"
        CF[java.lang.classfile<br/>ClassFile.of.build]
    end

    EF --> CF
    CP --> CF
    PRE_CP --> CF
    PRE_INT --> CF
    RT_CP --> CF
    RT_INT --> CF

    style CF fill:#fff3e0,stroke:#e65100,stroke-width:2px
```

**Trois niveaux de generation :**

1. **Compile-time** (`vauban-processor`) : APT genere factories, proxies et `vauban-beans.list` pour les beans du module courant
2. **Build-time** (`vauban-maven-plugin`) : pre-genere proxies et intercepteurs pour les beans des JARs de dependances
3. **Runtime** (`vauban-core`) : genere a la volee si pas pre-genere (fallback)

Tous utilisent `java.lang.classfile.ClassFile` — **zero dependance bytecode externe** (pas d'ASM, pas de ByteBuddy).

Les classes generees ont des noms deterministes :

| Type | Naming | Exemple |
|------|--------|---------|
| Factory | `<BeanClass>_Factory` | `com.example.MyService_Factory` |
| Client proxy | `<BeanClass>_ClientProxy` | `com.example.MyService_ClientProxy` |
| Sous-classe interceptee | `<BeanClass>$$Intercepted` | `com.example.MyService$$Intercepted` |

---

## Fichiers META-INF generes par Vauban

| Fichier | Genere par | Moment | Contenu | Lu par |
|---------|-----------|--------|---------|--------|
| `META-INF/vauban-beans.list` | APT + Maven plugin | compile / process-classes | Noms de classes CDI decouverts (tries, un par ligne), incluant les classes promues par BCE | `VaubanContainerBuilder.scanClasspath()` |
| `META-INF/vauban-bce-processed` | APT (si BCEs presentes) | compile | Marqueur texte : `# BCE phases executed at compile time` | `VaubanContainerBuilder` : skip BCE pour cette source |
| `META-INF/vauban-bce-runtime.list` | APT (si `@Enhancement` modifie des classes) | compile | Paires `bceFqn;targetFqn` : associations BCE→classe decidees a la compilation | `VaubanContainerBuilder` : rejoue exactement ces paires via `replayEnhancementForTargets()` |
| `META-INF/vauban-synthetic-metadata.properties` | APT (si `@Synthesis`) | compile | Beans synthetiques et observers synthetiques serialises | `VaubanContainerBuilder.build()` : re-cree les beans/observers sans relancer BCE |

### Semantique per-source

Chaque fichier est **par JAR** (ou repertoire de classes). Un JAR de dependance peut avoir
`vauban-bce-processed` (APT a tourne sur ce module) pendant que le projet applicatif ne l'a pas
(pas d'APT configure). Le conteneur traite chaque source independamment :

```mermaid
flowchart LR
    subgraph "lib-domain.jar"
        LB1["vauban-beans.list ✓"]
        LB2["vauban-bce-processed ✓"]
        LB3["vauban-bce-runtime.list ✓"]
    end
    subgraph "app-classes/"
        AB1["vauban-beans.list ✗"]
        AB2["vauban-bce-processed ✗"]
    end

    LB2 -->|"replayEnhancement\n(bce-runtime.list)"| CONT["VaubanContainer"]
    AB2 -->|"processEnhancementOnly\n(runtime fallback)"| CONT
    LB1 -->|"beans connus"| CONT
    AB1 -->|"BeanDiscovery\nnormale"| CONT
```

### Regles d'idempotence

- `vauban-beans.list` : `VaubanGenerator` collecte d'abord les listes existantes (`alreadyKnownBeans`), ne rescanne pas les sources qui en ont deja une.
- `_ClientProxy.class` / `$$Intercepted.class` : `VaubanGenerator` verifie l'existence sur disque avant de generer (idempotent).
- `vauban-bce-processed` : presence = toutes les phases BCE ont ete executees a la compilation. Absence = les phases BCE doivent etre (partiellement) rejouees au runtime.
