# Vauban — APT, plugin Maven, et client proxies CDI

> **Audience** : développeur d'application qui utilise Vauban comme container CDI Lite,
> ou contributeur Vauban qui touche à la chaîne de génération.
>
> **Périmètre** : ce qui est généré au build, où, par qui, comment c'est consommé au
> runtime, et quel contrat doit rester invariant entre les deux mondes.

Vauban est un container CDI 4.1 Lite à zéro réflexion runtime, qui s'appuie sur trois
moteurs de génération de code complémentaires :

| Moteur | Module | Quand | Périmètre |
|---|---|---|---|
| **APT** (Annotation Processor) | `vauban-processor` | À la compilation `javac` | Beans déclarés dans les sources du module en cours de compilation |
| **Plugin Maven** | `vauban-maven-plugin` | Phase `process-classes`, après `javac` | Beans découverts dans les jars de dépendances + bytecode du projet |
| **Runtime** | `vauban-core` (Class-File API) | Au boot du container | Fallback pour les beans non pré-générés |

**Règle d'or** : tout ce qui peut être généré au build l'est. Le runtime est un filet de
sécurité, pas un mode de fonctionnement nominal.

---

## 1. Le moteur APT — `vauban-processor`

### 1.1. Point d'entrée

`io.vidocq.vauban.processor.VaubanProcessor` (extends `AbstractProcessor`).

Annotations écoutées (`@SupportedAnnotationTypes`) :

- Scopes CDI standards : `@ApplicationScoped`, `@RequestScoped`, `@Dependent`,
  `@Singleton`, plus `@Produces`.
- Triggers d'enhancement BCE : annotations déclarées dans le `withAnnotations`
  d'un `@Enhancement(...)` d'une `BuildCompatibleExtension` découverte via
  `ServiceLoader` au démarrage de l'APT.

Le processor accumule les beans **sur toutes les rounds APT** (un `accumulatedIndex`
mutable) et n'exécute la génération qu'au `processingOver()`. Les processeurs compagnons
(p. ex. `mansart-data-processor`) émettent des classes à des rounds postérieurs ; cette
stratégie garantit que tous les types nouvellement émis sont visibles au moment de la
génération finale.

### 1.2. Découverte des beans

`BeanDiscovery.discoverBeans()` applique les règles CDI 4.1 :

- Filtre par `@Vetoed`.
- Détection du scope (annotation portant `@NormalScope` ou `@Scope`) via
  `computeScope(...)`.
- Application des modifications BCE accumulées (`@Enhancement` ajoute / remplace des
  annotations sur des classes existantes).
- Les classes ajoutées par `@Discovery / ScannedClasses.add(...)` viennent du **bytecode
  des jars en classpath**, scanné via `ClassFileScanner`. Elles sont prises en compte
  dans la résolution mais **exclues** de la génération de `_Factory` / `_ClientProxy`
  pour ne pas violer la règle JPMS « pas de split-package ».

### 1.3. Fichiers d'index produits

Tous écrits dans `target/classes/META-INF/` :

| Fichier | Format | Lu par |
|---|---|---|
| `vauban-beans.list` | une FQN par ligne, triée, beans externes exclus | `VaubanContainerBuilder` au boot |
| `vauban-bce-runtime.list` | `<bce-fqn>;<target-class-fqn>` | runtime BCE replay |
| `vauban-bce-processed` | marker vide | signale au runtime « ne ré-exécute pas les phases BCE » |
| `vauban-synthetic-metadata` (binaire propriétaire) | beans + observers synthétiques produits par BCE `@Synthesis` | runtime |

Exemple de `vauban-beans.list` :

```text
# Vauban discovered beans — generated at compile time by APT
io.vidocq.mansart.data.cdi.MansartRuntimeProducer
io.vidocq.runtime.examples.mansart.DatabaseInspectorResource
io.vidocq.runtime.examples.mansart.ProductResource
...
```

### 1.4. Code généré pour chaque bean managé

Pour chaque bean *éligible* (déclaré dans le compilation unit, pas externe), le processor
émet jusqu'à deux classes via la **Class-File API du JDK 25** :

- `<Bean>_Factory.class` — implémente `BeanFactory<Bean>` ; le runtime invoque
  `factory.create()` pour instancier le bean sans réflexion.
- `<Bean>_ClientProxy.class` — généré uniquement si le scope est **normal** (= scope
  CDI annoté `@NormalScope`, p. ex. `@ApplicationScoped`, `@RequestScoped`). Voir §3.

Pas de `_Subclass`, pas de `_Producer` — la chaîne d'interception est intégrée différemment
(voir §4 sur `$$Intercepted` côté runtime).

### 1.5. Phases BCE exécutées au build

`BceProcessor` lance les phases d'une `BuildCompatibleExtension` à compile-time, dans
l'ordre de la spec CDI 4.1 :

1. **`@Discovery`** — peut ajouter des classes à scanner via `ScannedClasses.add(...)`.
2. **`@Enhancement`** — peut modifier les annotations d'une classe (ajout, remplacement).
   Les modifications sont stockées dans `enhancementModifications` et persistées dans
   l'index pour que le runtime les voie.
3. **`@Registration`** — pas d'effet de bord persistant ; check uniquement.
4. **`@Synthesis`** — produit des `syntheticBeans` et `syntheticObservers`, sérialisés
   dans `vauban-synthetic-metadata`.
5. **`@Validation`** — peut faire échouer la compilation.

Le marker `vauban-bce-processed` est écrit en fin de cycle. Au boot, si le marker est
présent, le container **n'exécute pas** ces phases à nouveau — il consomme directement
les artefacts générés. Les BCE sont donc *idempotentes au build*, pas re-jouées au
démarrage.

### 1.6. Limitations connues de l'APT

- **CDI 4.1 — pas de no-arg ctor** : si le bean n'a qu'un constructeur paramétré
  (autorisé par CDI 4.1 sur les `@Inject` ctors), le proxy généré au compile-time
  appelle `super()` et échouera au load. Dans ce cas, le `RuntimeClientProxyGenerator`
  prend le relais (il sait pousser des valeurs par défaut sur les paramètres du super
  ctor — voir `findSimplestConstructor`).
- **Génériques erasés** : `TypeVariable` et wildcards résolvent vers `Object` dans le
  bytecode généré.
- **Classes externes (jars)** : pas de `_Factory` / `_ClientProxy` généré, pour
  préserver la règle JPMS « pas de split-package ». Le plugin Maven prend le relais
  si on veut pré-générer ces proxies — voir §2.
- **Pas de hierarchy traversal** dans les overrides du proxy (méthodes héritées non
  surchargées). Le code est en place dans le générateur (champs `$$mh_*` dormants) mais
  désactivé.

---

## 2. Le plugin Maven — `vauban-maven-plugin`

### 2.1. À quoi il sert

L'APT ne voit que les sources du module en cours de compilation. Si une dépendance
contient des beans CDI déjà compilés (typique pour une application qui assemble des
libraries CDI tierces), l'APT les ignore.

Le plugin Maven complète ce cas : il scanne le **bytecode** (jars de dépendances +
`target/classes` du projet) à la phase `process-classes`, et applique la même chaîne
de génération que l'APT, avec deux différences clés :

1. Il a accès à **tout le classpath compilé**, donc aux beans externes.
2. Il s'exécute **après** `javac`, donc voit aussi les classes émises par les autres
   processeurs APT (utile pour AOT GraalVM).

### 2.2. Goals

| Goal | Phase | Rôle |
|---|---|---|
| `generate` | `process-classes` | Découverte beans + génération `_Factory`/`_ClientProxy` + écriture `vauban-beans.list` |
| `dist` | `package` | Crée une distribution ZIP avec `bin/run.sh`/`run.cmd` et `lib/*.jar` |
| `encrypt` | `package` | Chiffre AES-256-GCM les classes internes du jar (hors `exports`/`opens` du `module-info.java`) |

### 2.3. Configuration minimale

```xml
<plugin>
  <groupId>io.vidocq.vauban</groupId>
  <artifactId>vauban-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
</plugin>
```

### 2.4. Coordination APT ↔ plugin (no double work)

Le plugin lit le `vauban-beans.list` déjà écrit par l'APT (s'il existe) et **ne re-scanne
que ce qui n'y figure pas**, c.-à-d. les classes externes. Les classes locales déjà
indexées et déjà accompagnées de leur `_Factory` / `_ClientProxy` sont laissées en place.

**Quand utiliser quoi :**

- **APT seul suffit** : monomodule, beans déclarés localement, dépendances passives.
- **Plugin nécessaire** : application qui consomme des beans CDI exposés par une
  bibliothèque tierce déjà compilée ; assemblage AOT (GraalVM) où la couverture
  bytecode totale est requise.

Les deux peuvent cohabiter sans conflit.

---

## 3. Les classes `_ClientProxy` — contrat invariant

C'est ici que la chaîne build/runtime échoue le plus facilement (voir l'incident
`VAU-PRX-002` documenté dans `BUG.md`). Trois acteurs doivent rester strictement alignés.

### 3.1. Forme exacte du proxy attendue

```java
public class <Bean>_ClientProxy extends <Bean> {
    private java.util.function.Supplier $$delegate;

    public <Bean>_ClientProxy() {
        super();   // ou super(<defaults>) si pas de no-arg ctor super
    }

    public void $$setDelegate(java.util.function.Supplier d) {
        this.$$delegate = d;
    }

    // Pour chaque méthode publique non-final non-static non-synthétique :
    public <ret> <method>(<args>) {
        return ((<Bean>) this.$$delegate.get()).<method>(<args>);
    }
}
```

Quatre invariants critiques :

1. **Constructeur public no-arg.** Le runtime fait
   `proxyClass.getDeclaredConstructor().newInstance()`.
2. **Méthode `$$setDelegate(Supplier)` publique.** Le runtime fait
   `proxyClass.getMethod("$$setDelegate", Supplier.class).invoke(proxy, delegate)`.
3. **Field `$$delegate` non-final** (sera réécrit par `$$setDelegate`).
4. **Override par virtual dispatch** : aucun appel reflectif depuis l'application ne
   doit court-circuiter cet override (en particulier, pas d'`invokespecial` sur la
   méthode de la superclass).

### 3.2. Les deux générateurs et leur invariant commun

- **`vauban-processor/.../codegen/proxy/ClientProxyGenerator`** — génère les `.class`
  pré-générés visibles à `javac`.
- **`vauban-core/.../proxy/RuntimeClientProxyGenerator`** — génère le proxy *à la volée*
  via `MethodHandles.Lookup.defineClass` quand aucun proxy pré-généré n'a été trouvé.

Les deux **doivent** produire un bytecode qui satisfait les invariants ci-dessus. Tout
drift unilatéral provoque une `NoSuchMethodException` ou un `ClassCastException` au boot,
catchée et wrappée en `DeploymentException("Failed to create client proxy for normal-scoped bean ...")`
par `InterceptorBeanWrapper.getOrCreateProxy`.

### 3.3. Test de garde-fou

`vauban-processor/src/test/.../codegen/proxy/ClientProxyGeneratorTest` instancie le proxy
exactement comme le runtime le ferait :

```java
var proxy = (T) proxyClass.getDeclaredConstructor().newInstance();
proxyClass.getMethod("$$setDelegate", Supplier.class).invoke(proxy, supplier);
```

C'est ce test qui rend tout drift visible immédiatement à compile-time du module
`vauban-processor`. **Ne pas changer ce pattern d'instanciation** sans en discuter — il
est intentionnellement couplé au contrat runtime.

### 3.4. Sélection compile-time vs runtime au boot

`InterceptorBeanWrapper.loadOrDefineClassRobustly(targetClass, name, bytecode)` :

```java
try {
    return targetClass.getClassLoader().loadClass(name);  // (1)
} catch (ClassNotFoundException cnfe) {
    return vaubanLookup.lookupFor(targetClass).defineClass(bytecode);  // (2)
}
```

- Chemin (1) : la classe `_ClientProxy` pré-générée a été trouvée → on la charge telle
  quelle. Le bytecode généré au runtime n'est **pas** utilisé.
- Chemin (2) : aucun pré-généré → fallback runtime via Class-File API + `Lookup.defineClass`.

C'est pourquoi un `mvn clean` est obligatoire après une modification du
`ClientProxyGenerator` (compile-time). Sans clean, les `.class` du précédent format
restent dans `target/classes` et sont chargés via le chemin (1).

---

## 4. Cycle de vie au runtime — chorégraphie

### 4.1. Bootstrap (`VaubanContainer.builder().build()`)

1. **Indexation** — `ClassFileScanner.scan(...)` reconstruit en mémoire un index
   bytecode-compatible Jandex de toutes les classes connues (issue de
   `vauban-beans.list` + paquets explicitement ajoutés via `addBeanClass(...)` ou
   `scanPackage(...)`).
2. **Discovery** — `BeanDiscovery.discoverBeans(index)` produit la liste de
   `BeanDescriptor`.
3. **Validation** — `ClassValidator` vérifie ambiguïtés, dépendances circulaires,
   compatibilité de scopes.
4. **Wiring injectors** — pour chaque `ManagedBean`, on enregistre un `Injector` qui
   appelle `injectFields(...)`. C'est ici que `BeanInjector.injectFieldsByReflection`
   sera invoqué quand le bean sera créé pour la première fois dans son scope.
5. **Wrapping intercepted beans** — `InterceptorBeanWrapper.wrapInterceptedBeans` génère
   et enregistre les sous-classes `$$Intercepted` pour les beans qui ont des
   `@InterceptorBinding`.
6. **Lifecycle events** — fire `@Initialized(ApplicationScoped.class)` puis
   `jakarta.enterprise.event.Startup`. Les extensions externes (p. ex.
   `mansart-transactions-cdi`) accrochent ici l'enregistrement de leurs `Context`
   custom (p. ex. `@TransactionScoped`).

### 4.2. `select(Class<T>)` — chemin utilisateur

```text
container.select(MyService.class)
   └─ resolver.resolve(typeInfo, qualifiers) → BeanDescriptor
   └─ beans.get(descriptor.id())             → ManagedBean
   └─ getContextualInstance(bean)
        ├─ if scope.isNormal()  → return interceptorWrapper.getOrCreateProxy(bean)
        └─ else (Singleton/Dependent)
              └─ context.get(bean, new CreationalContext)
```

**Pour un scope normal** (`@ApplicationScoped`, `@RequestScoped`, `@TransactionScoped`,
etc.), `select` retourne **immédiatement** le client proxy, sans matérialiser l'instance
contextuelle. Important : le contexte peut être inactif au moment du lookup (p. ex.
résolution d'un `@Inject @RequestScoped` au boot, hors requête) ; toucher au contexte
plus tôt lèverait `ContextNotActiveException` — c'est la cause racine du fix `VAU-INJ-001`.

**Pour un pseudo-scope** (`@Singleton`, `@Dependent`), `getContextualInstance` matérialise
immédiatement l'instance via le contexte correspondant.

### 4.3. `getOrCreateProxy(bean)` — création du proxy

```text
proxyCache.computeIfAbsent(bean.id, _ -> {
   var beanClass = resolveProxyTargetClass(bean);  // gère $$Intercepted
   // — Validations CDI —
   if (Modifier.isFinal(beanClass)) throw UnproxyableResolutionException;
   if (any final non-private non-static method) throw UnproxyableResolutionException;
   if (only private no-arg ctor) throw UnproxyableResolutionException;

   // — Génération + chargement —
   var generated = RuntimeClientProxyGenerator.generate(beanClass);
   var proxyClass = loadOrDefineClassRobustly(beanClass, generated.name, generated.bytecode);

   // — Instanciation + delegate —
   var proxy = proxyClass.getDeclaredConstructor().newInstance();        (*)
   var setter = proxyClass.getMethod("$$setDelegate", Supplier.class);   (*)
   Supplier<Object> delegate = () -> {
       var ctx = container.getFirstContext(bean.scope());
       return ctx.get(bean, new CreationalContext());
   };
   setter.invoke(proxy, delegate);                                        (*)

   return proxy;
});
```

Les lignes `(*)` matérialisent le contrat documenté en §3.1. Si l'une échoue, le catch
re-throw en `DeploymentException` (pour les scopes normaux) — pour ne **jamais**
dégrader silencieusement vers `ctx.get()` eager (cause racine de `VAU-INJ-001`).

### 4.4. Création de l'instance contextuelle (lazy)

Quand le proxy est invoqué pour la première fois — `proxy.someMethod(args)` — le bytecode
généré fait `((Bean) $$delegate.get()).someMethod(args)`. Le `delegate.get()` :

1. Récupère le `Context` correspondant au scope du bean.
2. Si l'instance n'existe pas dans ce contexte, le contexte appelle
   `bean.create(creationalContext)`.
3. `ManagedBean.create` :
   1. `factory.create()` instancie la classe (ou la sous-classe `$$Intercepted` si le
      bean a des interceptors).
   2. **Field injection** — `BeanInjector.injectFieldsByReflection(instance, ...)` :
      - `Instance<T>` → `new InstanceImpl<>(container, T, qualifiers, ip)` (jamais null).
      - `Event<T>` → `new EventImpl<>(...)`.
      - `BeanManager` / `BeanContainer` / `InjectionPoint` → cas spéciaux.
      - autres : `bm.getBeans(type, quals).first` puis `bm.getReference(...)`.
   3. **Initializer methods** — méthodes `@Inject` annotées sur `void m(deps)`.
   4. **PostConstruct** — méthodes annotées `@PostConstruct`.
   5. **Eager interceptor instances** — création des intercepteurs liés au bean.
4. L'instance contextuelle est cachée dans le contexte.
5. Le proxy reçoit l'instance et fait virtual dispatch sur la méthode appelée.

### 4.5. Interception (`$$Intercepted`)

Pour un bean qui a au moins une méthode portant un `@InterceptorBinding` (ou un binding
au niveau classe), `wrapInterceptedBeans` génère une sous-classe :

```java
class <Bean>$$Intercepted extends <Bean> {
    @Override <ret> someMethod(<args>) {
        return new VaubanInvocationContext(super::someMethod, args, interceptors)
                  .proceed();
    }
}
```

Cette sous-classe **remplace** la `beanClass` enregistrée dans le `ManagedBean`. Le
`_ClientProxy` étend alors `<Bean>$$Intercepted` (et non `<Bean>` directement). Quand
le proxy fait `$$delegate.get()`, il reçoit une instance `$$Intercepted`, et l'override
de la méthode appelle la chaîne d'interception avant le code utilisateur.

L'utilisateur écrit `@Transactional public Response create(...)` ; il ne voit jamais ni
le proxy, ni la sous-classe interceptée, ni la chaîne `VaubanInvocationContext`.

---

## 5. Fichiers clés à connaître

```text
vauban-processor/src/main/java/io/vidocq/vauban/processor/
├── VaubanProcessor.java                    # entry point APT, orchestrateur des rounds
├── apt/ElementScanner.java                 # convertit le modèle JSR 199 → modèle interne
├── codegen/factory/BeanFactoryGenerator.java
└── codegen/proxy/ClientProxyGenerator.java # client proxy compile-time

vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/
├── generate/GenerateMojo.java              # goal `generate`
├── generate/DistMojo.java                  # goal `dist`
├── generate/EncryptMojo.java               # goal `encrypt`
├── generate/VaubanGenerator.java           # logique partagée scan + génération
└── module/ModuleAnalyzer.java              # analyse module-info.java

vauban-core/src/main/java/io/vidocq/vauban/core/
├── container/VaubanContainerBuilder.java   # bootstrap, phase build()
├── container/VaubanContainer.java          # select(), getContextualInstance()
├── container/BeanInjector.java             # injectFieldsByReflection
├── container/InterceptorBeanWrapper.java   # getOrCreateProxy + wrapInterceptedBeans
├── container/ManagedBean.java              # bean lifecycle (factory + injector + scope)
├── proxy/RuntimeClientProxyGenerator.java  # client proxy runtime fallback
├── bean/discovery/BeanDiscovery.java       # discoverBeans
└── bean/resolution/BeanResolver.java       # CDI type+qualifier resolution

vauban-indexer/src/main/java/io/vidocq/vauban/indexer/
└── scanner/ClassFileScanner.java           # scan bytecode JDK 25 Class-File API
```

---

## 6. Référence rapide — invariants à ne JAMAIS casser

1. **`_ClientProxy` doit avoir un ctor no-arg public.** Tous les générateurs.
2. **`_ClientProxy` doit avoir `void $$setDelegate(Supplier)` public.** Tous les générateurs.
3. **`$$delegate` n'est pas final.** Il est réécrit après instanciation.
4. **Le test `ClientProxyGeneratorTest` instancie via le contrat runtime.** Si vous le
   modifiez pour utiliser une API privée, vous tuez le garde-fou anti-drift.
5. **Pour les beans normal-scope, `getContextualInstance` retourne le proxy en
   première instruction**, avant tout accès au contexte. Voir `VAU-INJ-001`.
6. **`_ClientProxy.class` survit aux modifs des générateurs**. Toujours `mvn clean`
   après changement du `ClientProxyGenerator` ou du `RuntimeClientProxyGenerator`.

---

## 7. Pour aller plus loin

- `vauban/BUG.md` — les incidents documentés (`VAU-INJ-001`, `VAU-PRX-002`).
- `vauban/docs/architecture.md` — vue d'ensemble du module.
- `vauban/tasks/lessons.md` — leçons cumulées sur la maintenance du container.
- Spec CDI 4.1 — § client proxies, § normal scopes vs pseudo-scopes.
