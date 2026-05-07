# Rapport — VAU-PRX-002 : drift entre `ClientProxyGenerator` (APT) et `RuntimeClientProxyGenerator`

**Date** : 2026-05-07
**Statut** : `RESOLVED`
**Périmètre** : `vauban-processor`, `vauban-core`, `vauban/BUG.md`
**Validation finale** : `vidocq-mps-mansart-h2-example` répond 200/201/204 sur tous les endpoints, plus aucun NPE.

---

## 1. Contexte du signalement

L'application `vidocq-mps-mansart-h2-example` démarrait correctement (les 7 extensions Vidocq se chargent, Chappe ouvre le port 8080), mais toute requête HTTP vers une ressource JAX-RS plantait :

```
GRAVE: Cassini dispatch error
java.lang.NullPointerException: Cannot invoke "jakarta.enterprise.inject.Instance.get()"
    because "this.dataSourceInstance" is null
        at io.vidocq.mpserver.examples.mansart.DatabaseInspectorResource.rawProducts(...:37)
        at io.vidocq.cassini.internal.Invoker.invokeInternal(Invoker.java:693)
```

Symétriquement pour `ProductResource.list` : `this.products is null`.

L'exemple jumeau `vidocq-mps-rest-example` (même pattern `@ApplicationScoped @Path` + `@Inject T`) fonctionnait, ce qui rendait le diagnostic non trivial.

## 2. Démarche d'investigation

### 2.1. Cartographie initiale (3 explorations parallèles)

- **Exemple mansart-h2** vs **exemple rest** : comparaison des classes, scopes, `module-info.java`, contenu de `target/classes/META-INF/`.
- **Vauban event resolver** : audit du log parasite `DEBUG EVENT` qui remontait dans la sortie utilisateur.
- **Vauban field injection** : audit du flux `BeanInjector.injectFieldsByReflection` et de la résolution cross-module.

Conclusion intermédiaire : trois pistes contradictoires, pas de cause racine claire. Décision : passer en diagnostic live plutôt que continuer la théorie.

### 2.2. Vérification des tests existants

`vauban-core/src/test/.../NormalScopeFieldInjectionTest.java::Fix4RegressionTest` modélisait déjà — selon son commentaire — `DatabaseInspectorResource + ProductResource combined` (fix VAU-INJ-001 documenté dans `BUG.md`). Lancement isolé : **8 tests passent**. Donc le scénario générique « `@ApplicationScoped` intercepté + `@Inject Instance<T>` + `@Inject @Singleton-intercepted` » ne reproduisait PAS le bug.

### 2.3. Diagnostic runtime ciblé

Ajout de logs `[VAU-INJ]` dans `BeanInjector.injectFieldsByReflection` et `[VAU-SEL]` dans `VaubanContainer.select` (conditionnés par `-Dvauban.debug.inject=true`). Build léger de `vauban-core` (les dépendants prennent automatiquement le snapshot M2).

**Premier run** : aucune trace `[VAU-INJ]` pour `DatabaseInspectorResource` ni `ProductResource`, et **aucune** trace `[VAU-SEL]` non plus. Donc Cassini ne demandait pas ces beans à Vauban — il les instanciait par fallback.

**Second run** (logs au début de `select()` + autour de `getContextualInstance`) : `select-entry` apparaît bien, mais `getContextualInstance` lance :

```
[VAU-SEL] -> EXCEPTION getContextualInstance
   type=...DatabaseInspectorResource
   ex=jakarta.enterprise.inject.spi.DeploymentException
   msg=Failed to create client proxy for normal-scoped bean ...DatabaseInspectorResource
Caused by: java.lang.NoSuchMethodException:
   ...DatabaseInspectorResource_ClientProxy.<init>()
```

### 2.4. Cause racine

`InterceptorBeanWrapper.getOrCreateProxy:235` appelle `proxyClass.getDeclaredConstructor().newInstance()` (ctor no-arg). Le `_ClientProxy.class` chargé n'avait PAS de ctor no-arg.

`javap` comparatif :

```text
# rest-example (OK)
class TodoResource_ClientProxy extends TodoResource {
  private Supplier $$delegate;
  public TodoResource_ClientProxy();              ← ctor no-arg
  public void $$setDelegate(Supplier);
  ... overrides ...
}

# mansart-h2-example (FAIL)
class DatabaseInspectorResource_ClientProxy extends DatabaseInspectorResource {
  private final Supplier delegate;                ← final, autre nom
  public DatabaseInspectorResource_ClientProxy(Supplier);  ← ctor takes Supplier
  ...
}
```

Deux générateurs incompatibles produisaient des `_ClientProxy.class` :

| Générateur | Localisation | Format | Quand utilisé |
|---|---|---|---|
| `ClientProxyGenerator` | `vauban-processor` (APT, compile-time) | ancien (ctor `(Supplier)`, field `delegate` final) | écrit dans `target/classes` |
| `RuntimeClientProxyGenerator` | `vauban-core` (Class-File API runtime) | moderne (ctor `()`, field `$$delegate`, setter `$$setDelegate`) | fallback runtime via `MethodHandles.Lookup.defineClass` |

`InterceptorBeanWrapper.getOrCreateProxy` n'acceptait que le format moderne. `loadOrDefineClassRobustly` chargeait en priorité la classe pré-générée APT — donc le format ancien shadowait toujours le runtime, et l'exception `NoSuchMethodException` était systématique pour tout bean recompilé après le drift.

**Pourquoi rest-example fonctionnait** : son `TodoResource_ClientProxy.class` venait d'une compilation antérieure au drift, pendant laquelle `ClientProxyGenerator` produisait encore le format moderne. mansart-h2-example, recompilé plus récemment, embarquait la version ancienne.

## 3. Fix appliqué

### 3.1. `vauban-processor/.../codegen/proxy/ClientProxyGenerator.java`

Aligné sur le contrat de `RuntimeClientProxyGenerator` :

```text
- private final Supplier delegate;          → private Supplier $$delegate;
- public Proxy(Supplier d) { ...putfield }  → public Proxy() { super(); }
+ public void $$setDelegate(Supplier d) { this.$$delegate = d; }
- getfield "delegate"                       → getfield "$$delegate"   (3 emplacements)
```

### 3.2. `vauban-processor/.../ClientProxyGeneratorTest.java`

Les tests instanciaient le proxy via `getDeclaredConstructor(Supplier.class).newInstance(supplier)`. Migration vers le contrat utilisé par le runtime :

```java
var proxy = (T) proxyClass.getDeclaredConstructor().newInstance();
proxyClass.getMethod("$$setDelegate", Supplier.class).invoke(proxy, supplier);
```

C'est exactement le code que `InterceptorBeanWrapper.getOrCreateProxy:235-249` exécute. Tout drift unilatéral d'un des deux générateurs casse désormais ces tests à compile-time du module `vauban-processor`.

### 3.3. Nettoyage logs parasites — `vauban-core/.../event/EventDispatcher.java`

7 occurrences de `System.out.println("DEBUG EVENT: ...")` (chemins `Checking`, `Mismatch on eventType`, `MATCHED`, `Mismatch on qualifiers`, `invoking`, `invoke successful`, `findMethod returned null`) — toutes supprimées. Logs de production silencieux à nouveau.

### 3.4. Trace de diagnostic temporaire

Les `[VAU-INJ]` / `[VAU-SEL]` ajoutés pour le diagnostic ont été retirés de `BeanInjector.java` et `VaubanContainer.java` après confirmation du fix.

### 3.5. Documentation — `vauban/BUG.md`

Nouvelle entrée `VAU-PRX-002` (statut `FIXED`, 2026-05-07) en tête du tracker, avant `VAU-INJ-001`.

## 4. Validation

### 4.1. Tests unitaires

```text
vauban-core    : 266 tests, 0 failures, 0 errors  (incl. NormalScopeFieldInjectionTest 8/8)
vauban-processor : 4 ClientProxyGenerator tests OK après migration du contrat
Reactor vauban : BUILD SUCCESS (full install)
Reactor vidocq  : BUILD SUCCESS (clean install)
```

### 4.2. E2E `vidocq-mps-mansart-h2-example`

Après `clean install` cascade (pour régénérer les `_ClientProxy.class` au format moderne) :

| Endpoint | Verbe | Code | Réponse |
|---|---|---|---|
| `/api/db/products` | GET | 200 | `[Espresso, Cappuccino, Latte]` |
| `/api/products` | GET | 200 | idem (via repository Mansart Data) |
| `/api/products` | POST `{"name":"Mocha","price":4.5}` | 201 | `{id:4, name:"Mocha", ...}` |
| `/api/products/1` | DELETE | 204 | — |
| `/api/products/count` | GET | 200 | `3` |

Aucune ligne `GRAVE` / `Exception` / `NPE` / `DEBUG EVENT` dans les logs.

## 5. Bug secondaire identifié (non corrigé ici)

Pendant le diagnostic, une autre anomalie est apparue : `vauban-indexer/ClassFileScanner.java:99` extrait le descriptor brut du paramètre annoté `@Observes`. Pour `void onStart(@Observes Startup event)`, le runtime indexe `eventType=ClassType[name=java.lang.Object]` au lieu de `Startup`. Conséquence : l'observer matche par hasard via `Object` (super-type universel) — pas bloquant fonctionnellement, mais conceptuellement faux.

Tâche dédiée créée pour parser l'attribut `Signature` du bytecode quand pertinent. Voir `BUG.md` à mettre à jour si on veut tracker formellement cette anomalie.

## 6. Leçons retenues

1. **Deux générateurs avec contrats divergents = bombe à retardement**. Le test `ClientProxyGeneratorTest` doit appeler le proxy comme le runtime l'appelle (même API, même pattern d'instanciation), pas via une API privée connue de seuls les auteurs du test.
2. **Les `_ClientProxy.class` survivent aux modifications de générateur**. Tant qu'on ne fait pas `mvn clean`, on travaille avec d'anciens artefacts. C'est la raison pour laquelle rest-example masquait le bug et mansart-h2 le révélait.
3. **Diagnostic live plutôt que théorie répétée**. Trois explorations consécutives n'avaient pas convergé ; un seul aller-retour `ajouter trace → build léger → relancer → grep` a mis le bug en évidence en moins de 2 minutes.
4. **Le format `Causé par` est plus informatif que la classe de l'exception wrappante**. `DeploymentException("Failed to create client proxy ...")` était le symptôme ; `Caused by NoSuchMethodException: ...<init>()` était la cause.

## 7. Fichiers touchés

```text
vauban/vauban-processor/src/main/java/io/vidocq/vauban/processor/codegen/proxy/ClientProxyGenerator.java
vauban/vauban-processor/src/test/java/io/vidocq/vauban/processor/codegen/proxy/ClientProxyGeneratorTest.java
vauban/vauban-core/src/main/java/io/vidocq/vauban/core/event/EventDispatcher.java
vauban/BUG.md
vauban/docs/apt-and-proxies.md       (créé en parallèle, voir doc utilisateur)
vauban/tasks/2026-05-07-VAU-PRX-002-fix-report.md   (ce rapport)
```
