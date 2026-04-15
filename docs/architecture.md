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

    state "Creation" as creation {
        Registered --> Resolving: container.select(Type)
        Resolving --> CtorInjection: Constructeur (@Inject ou default)
        CtorInjection --> FieldInjection: BeanInjector.injectFields()
        FieldInjection --> PostConstruct: @PostConstruct
        PostConstruct --> Ready: Instance disponible
    }

    Ready --> Active: Utilisation normale
    Active --> Ready: Appels methodes

    state "Destruction" as destruction {
        Active --> PreDestroy: Scope ferme ou destroy()
        PreDestroy --> Destroyed: @PreDestroy execute
    }

    Destroyed --> [*]
```

---

## Resolution d'injection

```mermaid
flowchart TD
    IP[Point d'injection<br/><code>@Inject MyService svc</code>] --> TYPE{Type demande}

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
        SV1[ScopedValue&lt;InterceptionState&gt;<br/><i>InterceptorManager</i>]
        SV2[ScopedValue&lt;InjectionPoint&gt;<br/><i>VaubanContainer</i>]
        SV3[ScopedValue&lt;Set&lt;String&gt;&gt;<br/><i>Cycle detection</i>]
        SV4[ScopedValue&lt;Boolean&gt;<br/><i>InterceptorBeanWrapper</i>]
        SV5[ScopedValue&lt;RequestContextState&gt;<br/><i>RequestContext</i>]
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

```mermaid
flowchart LR
    D["@Discovery<br/><i>Decouverte de types</i>"] --> E["@Enhancement<br/><i>Modification annotations</i>"]
    E --> R["@Registration<br/><i>Enregistrement beans</i>"]
    R --> S["@Synthesis<br/><i>Beans synthetiques</i>"]
    S --> V["@Validation<br/><i>Verification finale</i>"]

    D ---|ScannedClasses| E
    E ---|ClassConfig| R
    R ---|BeanInfo| S
    S ---|SyntheticBeanBuilder| V

    style D fill:#e3f2fd,stroke:#1565c0
    style E fill:#e8f5e9,stroke:#2e7d32
    style R fill:#fff3e0,stroke:#ef6c00
    style S fill:#fce4ec,stroke:#c62828
    style V fill:#f3e5f5,stroke:#6a1b9a
```

Les extensions BCE sont executees a **trois niveaux** : compilation (APT), build-time (Maven plugin),
et runtime (fallback). Le marqueur `META-INF/vauban-bce-processed` est **per-source** (JAR/repertoire).

### Decouverte et execution a la compilation (APT)

```mermaid
flowchart TD
    subgraph "init()"
        SL["ServiceLoader<br/>BuildCompatibleExtension"] --> BCE_LIST["Liste des BCEs"]
        BCE_LIST --> EXTRACT["Extraction des<br/>@Enhancement(withAnnotations=...)"]
        EXTRACT --> TYPES["getSupportedAnnotationTypes<br/>CDI annotations + triggers BCE"]
    end

    subgraph "process()"
        SCAN["javac scanne les classes<br/>avec annotations supportees"] --> IDX["IndexBuilder → VaubanIndex"]
        IDX --> DISCOVERY["@Discovery<br/>ScannedClasses, MetaAnnotations"]
        DISCOVERY --> BEAN_DISC["BeanDiscovery"]
        BEAN_DISC --> ALL_PHASES["BceProcessor.process()<br/>@Enhancement → @Registration<br/>→ @Synthesis → @Validation"]
        ALL_PHASES --> PROMOTE["Promouvoir non-beans<br/>enrichis en beans"]
        PROMOTE --> GEN["Generer factories + proxies<br/>+ vauban-beans.list"]
        GEN --> MARKER["Ecrire vauban-bce-processed<br/>+ synthetic-metadata"]
    end

    style EXTRACT fill:#e8f5e9,stroke:#2e7d32
    style ALL_PHASES fill:#fff3e0,stroke:#f57c00
    style MARKER fill:#e3f2fd,stroke:#1565c0
```

**Etape cle** : dans `init()`, le processeur decouvre les BCEs via ServiceLoader et extrait
les annotations trigger de leurs methodes `@Enhancement(withAnnotations=...)`. Ces annotations
(ex: `@Path`) sont ajoutees aux `getSupportedAnnotationTypes()` pour que `javac` scanne les
classes correspondantes. Sans cette etape, les classes `@Path` sans scope CDI seraient invisibles.

**Matching index-based** : les classes en cours de compilation ne sont pas chargeables via
`Class.forName()`. Le `BceProcessor.processEnhancement()` utilise un troisieme chemin de
matching base sur `ClassInfo.hasAnnotation()` dans l'index, en complement du matching
reflection pour les classes deja chargees.

### Execution au build-time (Maven plugin)

Le `VaubanGenerator` decouvre aussi les BCEs via ServiceLoader sur le ClassLoader fourni
et appelle `BceProcessor.processEnhancementOnly()` pour enrichir l'index avant `BeanDiscovery`.
Cela permet de pre-generer les proxies pour les classes `@Path` de JARs externes.

### Execution au runtime (fallback par source)

```mermaid
flowchart TD
    SCAN["scanClasspath()"] --> TRACK["Tracker les sources<br/>avec/sans marqueur<br/>vauban-bce-processed"]
    TRACK --> CHECK{"Toutes les sources<br/>pre-traitees ?"}
    CHECK -->|Oui| SKIP["Skip BCE complet<br/>Charger synthetic metadata"]
    CHECK -->|Non| PARTIAL["processEnhancementOnly<br/>pour les classes<br/>des sources sans marqueur"]
    PARTIAL --> ENRICH["Enrichir l'index<br/>avec scopes synthetiques"]
    ENRICH --> DISC["BeanDiscovery<br/>sur index enrichi"]

    style SKIP fill:#e3f2fd,stroke:#1565c0
    style PARTIAL fill:#fff3e0,stroke:#f57c00
```

Le conteneur verifie **par source** (JAR/repertoire) la presence du marqueur. Les sources
pre-traitees sont skippees ; les sources sans marqueur (JARs tiers) recoivent `@Enhancement`
au runtime. Performance : < 1ms pour des dizaines de beans.

### Avantages

- Erreurs BCE detectees a la compilation (pas au deploiement)
- Demarrage plus rapide (phases deja executees)
- Beans synthetiques pre-calcules et serialises
- JARs tiers sans marqueur enrichis automatiquement au runtime

Les BCEs permettent de modifier les beans, ajouter des beans synthetiques, et valider le deploiement
sans utiliser les Portable Extensions (CDI Full).

---

## Generation de code (Class-File API JDK 25)

```mermaid
flowchart TD
    subgraph "Compile-time (vauban-processor)"
        APT[Annotation Processing] --> EF[BeanFactory&lt;T&gt;]
        APT --> CP[ClientProxy]
        APT --> LIST_APT[vauban-beans.list]
        BCE_APT[BCE @Enhancement] -.->|enrichissement| APT
    end

    subgraph "Build-time (vauban-maven-plugin)"
        SCAN[Scan JARs] --> PRE_CP[Pre-gen ClientProxy]
        SCAN --> PRE_INT[Pre-gen $$Intercepted]
        SCAN --> LIST[vauban-beans.list]
        BCE_MV[BCE @Enhancement] -.->|enrichissement| SCAN
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

**Enrichissement via BCE** : les `@Enhancement` des Build Compatible Extensions promeuvent des
classes non-CDI en beans (ex: `@Path` → `@RequestScoped`). Execute a la compilation (APT) pour
le module courant, et au runtime pour les JARs de dependances sans marqueur.
Voir [docs/configuration.md](configuration.md#enrichissement-de-beans-via-build-compatible-extensions-bce).

---

## Fichiers META-INF generes

| Fichier | Genere par | Contenu | Lu par |
|---------|-----------|---------|--------|
| `vauban-beans.list` | APT + Maven plugin | Noms des beans CDI decouverts (y compris ceux promus par BCE) | `VaubanContainerBuilder.scanClasspath()` |
| `vauban-bce-processed` | APT (si BCEs executees) | Marqueur (per JAR/repertoire) | `VaubanContainerBuilder` — skip BCE pour cette source |
| `vauban-synthetic-metadata.properties` | APT (si @Synthesis) | Beans/observers synthetiques serialises | `VaubanContainerBuilder.build()` |

Les classes promues par BCE `@Enhancement` (ex: `@Path` → `@RequestScoped`) sont
directement incluses dans `vauban-beans.list` car l'APT et le Maven plugin executent
les BCEs avant `BeanDiscovery`. Pas besoin de liste separee.
