# Vauban — Bugs reproductibles

Tracker interne. Format minimaliste : id court, date, symptôme, repro,
hypothèse de cause, statut. Mise à jour à chaque investigation.

---

## VAU-BCE-001 — Pipeline BCE : `@Registration` voit zéro IP, `@Synthesis` perd les types paramétrés et les membres de qualifier

**Date** : 2026-05-09
**Statut** : `FIXED` — 2026-05-09 (branche `fix/vau-bce-001-bce-not-invoked`)
**Sévérité** : haute — bloque toute Build Compatible Extension qui (a) parcourt `BeanInfo.injectionPoints()` en `@Registration`, (b) déclare un `SyntheticBean` avec un type paramétré (`Optional<T>`, `List<T>`, `Provider<T>`, …), ou (c) attache un qualifier dont les membres conditionnent la résolution. Cas concret : `ConfigCdiExtension` de Ravel (MicroProfile Config 3.1) — empêche M6 (intégration écosystème Vidocq) tant que la BCE n'est pas reconnue.

### Symptôme

Le pipeline BCE est *entré* (les méthodes `@Registration` / `@Synthesis` sont bien dispatchées par `BceProcessor`), mais à l'arrivée le déploiement échoue avec :

```
jakarta.enterprise.inject.spi.DeploymentException:
CDI deployment validation failed:
  - Unsatisfied dependency: field <Bean>.<field>
      of type ClassType[name=java.lang.String]
      with qualifiers […, @ConfigProperty(name=app.greeting, defaultValue=Hello)]
```

ou, plus en amont, un `IllegalArgumentException` :

```
@Registration error: Class not found in index: org.eclipse.microprofile.config.inject.ConfigProperty
@Registration error: Class not found in index: java.lang.String
```

ou encore une `NullPointerException` muette ramenée en `@Synthesis error: null` quand l'extension construit un `ParameterizedType` via `Types.parameterized(Optional.class, types.of(String.class))`.

### Repro minimal

```java
@jakarta.inject.Qualifier
@Retention(RUNTIME) @Target({FIELD, PARAMETER, METHOD, TYPE})
public @interface Tagged { String value() default ""; }

@Dependent
public static class TargetBean {
    @Inject @Tagged("scalar") public String scalar;
    @Inject @Tagged("optional") public Optional<String> opt;
}

public class TestBce implements BuildCompatibleExtension {
    @Registration(types = Object.class)
    public void registrar(BeanInfo bean) { /* observe bean.injectionPoints() */ }

    @Synthesis
    public void synth(SyntheticComponents components, Types types) {
        components.addBean(String.class)
                .type(String.class).qualifier(taggedLiteral("scalar"))
                .scope(Dependent.class).createWith(ScalarCreator.class);
        components.addBean(Object.class)
                .type(types.parameterized(Optional.class, types.of(String.class)))
                .qualifier(taggedLiteral("optional"))
                .scope(Dependent.class).createWith(OptionalCreator.class);
    }
}

SeContainerInitializer.newInstance()
        .addBeanClasses(TargetBean.class, TestBce.class)
        .initialize();
// → DeploymentException: Unsatisfied dependency on @Tagged("scalar") + @Tagged("optional")
```

Le test couvre exactement la surface API utilisée par `ravel-cdi-vauban/.../ConfigCdiExtension` qui passe **349/349** au TCK MicroProfile Config 3.1 sous Weld 6.0.2 — donc le code Ravel est conforme spec ; le bug est entièrement côté Vauban.

### Cause racine — six défauts cumulés sur le même chemin

| # | Site | Problème |
|---|---|---|
| 1 | `VaubanBceBeanInfo.injectionPoints()` | Stub `return List.of();` — l'extension reçoit zéro IP, son `@Registration` ne collecte rien, `@Synthesis` ne synthétise rien. |
| 2 | `VaubanAnnotationInfo` | Pas d'override de `name()`. Le default API CDI Lite 4.1 (`return declaration().name();`) traverse `lookup.requireClass(<annotation FQN>)` qui crashe pour les annotations hors-index (typique : `@ConfigProperty` qui vit dans `microprofile-config-api`). |
| 3 | `VaubanClassType.declaration()` | `lookup.requireClass(name)` lance `IllegalArgumentException("Class not found in index: java.lang.String")` pour tout type JDK ou tiers absent du scan. Le contrat spec exige que `ClassType.declaration()` réussisse pour tout type du modèle. |
| 4 | `VaubanSyntheticBeanBuilder.type(Type)` | No-op (`return this; // simplified`) — toute `addBean(...).type(<lang-model Type>)` est silencieusement perdue, le bean synthétique n'expose finalement que `Object`. Frappe systématique sur `Optional<T>`, `List<T>`, `Provider<T>`, etc. |
| 5 | `VaubanTypes.ofClass(String name)` | Retourne `null` si la classe n'est pas dans l'index. Provoque une NPE quand l'extension construit `types.parameterized(Optional.class, types.of(String.class))` → `List.of(null, …)`. |
| 6 | `BceProcessor.toBeanDescriptor` | `qualifiers.add(new QualifierInstance(qName, Map.of()));` — les membres du qualifier sont perdus. Conséquence : `@Tagged("scalar")` côté bean synthétique vs `@Tagged("scalar")` côté IP ne se résolvent plus, parce que le qualifier du bean a `members={}` alors que le qualifier de l'IP a `members={value=StringVal[scalar]}`. Le résolveur les considère distincts. |

### Fix

Sept fichiers touchés dans `vauban-core`, tous sur le pipeline BCE / lang-model :

1. `extensions/VaubanBceBeanInfo.injectionPoints()` — délègue à un nouveau wrapper `VaubanBceInjectionPointInfo` qui adapte chaque `BeanDescriptor.InjectionPointInfo` (modèle interne) vers `jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo` avec le type, les qualifiers (membres préservés via `VaubanAnnotationInfo`) et la déclaration (`FieldInfo` quand parsable depuis la description, sinon `ClassInfo` fallback).
2. `langmodel/VaubanAnnotationInfo` — override de `name()` retourne directement `indexAnnotation.name().value()` sans passer par `declaration()`.
3. `langmodel/types/VaubanClassType.declaration()` — fallback synthétique (`io.vidocq.vauban.indexer.model.ClassInfo` avec juste le `name`) quand le type n'est pas indexé.
4. `extensions/VaubanSyntheticBeanBuilder.type(Type)` — convertit le `Type` lang-model en `TypeInfo` via le nouveau helper `LangModelTypeMapper`, stocké dans un nouveau `Set<TypeInfo> indexTypes`. Accessible via `getIndexTypes()`.
5. `extensions/LangModelTypeMapper` (NEW) — inverse de `langmodel.types.TypeMapper` : `jakarta…Type → indexer TypeInfo` (récursif sur primitifs, classes, paramétrés, tableaux, wildcards, type-variables).
6. `extensions/VaubanTypes.ofClass(String)` — retourne toujours un `VaubanClassType`, plus de `null` même si le type n'est pas dans l'index (le fallback de #3 prend le relais).
7. `extensions/BceProcessor.toBeanDescriptor` — fusionne `synBean.getIndexTypes()` dans le set de types, et appelle un nouvel helper `extractAnnotationMembers(Annotation)` (réflexion : chaque méthode du type d'annotation → `AnnotationValue` correspondante, support primitifs / String / Class / Enum / Annotation imbriquée / arrays).

Aucune signature publique modifiée. Pas d'impact sur le chemin pré-processed APT (les `vauban-bce-runtime.list` / `vauban-bce-processed` markers passent par d'autres call-sites).

### Test de régression

`vauban-core/src/test/java/io/vidocq/vauban/core/extensions/BceRegistrationSynthesisTest.java` (3 tests) :

- `registrationExposesInjectionPoints` — vérifie que `BeanInfo.injectionPoints()` expose les 2 IPs avec leur type ET le membre `value` du `@Tagged` qualifier (couvre #1, #2, #6 sur le chemin lecture).
- `syntheticScalarBeanResolves` — `@Synthesis` enregistre un `SyntheticBean<String>` avec qualifier `@Tagged("scalar")`, l'IP `@Inject @Tagged("scalar") String` résout (couvre #6 côté résolution).
- `syntheticParameterizedBeanResolves` — `@Synthesis` avec `type(types.parameterized(Optional.class, types.of(String.class)))`, l'IP `Optional<String>` résout (couvre #3, #4, #5).

`vauban-core` : 269 tests, 0 failure (266 baseline + 3 nouveaux). `ravel-cdi-vauban` : 21 tests, 0 failure ; le test `VaubanContainerIntegrationTest.resolves_config_property_injection_through_bce_pipeline` (jusque-là `@Disabled` pour M6 résiduel) passe désormais en local — peut être réactivé côté ravel après publication d'un snapshot Vauban contenant ce fix.

### Suivi

- `VaubanBceInterceptorInfo.injectionPoints()` reste un stub — `InterceptorDescriptor` ne piste pas encore les IPs ; pas critique pour M6, à ouvrir séparément (VAU-BCE-002 si besoin).
- Les autres call sites de `new QualifierInstance(qName, Map.of())` (BceProcessor lignes 1010, 1071, 1141 ; VaubanContainerBuilder ligne 1172) suivent la même pattern de perte de membres ; à investiguer si un cas non-synthétique le requiert.

---

## VAU-MVN-001 — `VaubanGenerator` propage `NoClassDefFoundError` au lieu de skipper la classe

**Date** : 2026-05-09
**Statut** : `FIXED` — 2026-05-09 (branche `fix/vau-mvn-001-classpath-noclassdef`)
**Sévérité** : moyenne — bloque tout build qui consomme un artefact dont une dépendance transitive « optionnelle » manque sur le classpath fourni au plugin (cas concret : `microprofile-config-api:3.1.1` qui référence `jakarta.activation`).

### Symptôme

Pendant la phase `process-classes` de `vauban-maven-plugin`, la JVM lève une erreur de linkage non rattrapée et la build échoue :

```
java.lang.NoClassDefFoundError: jakarta/activation/DataSource
    at java.base/java.lang.Class.getDeclaredFields0(Native Method)
    at io.vidocq.vauban.maven.generate.VaubanGenerator.loadArchiveClasses(VaubanGenerator.java:352)
    at io.vidocq.vauban.maven.generate.VaubanGenerator.generate(VaubanGenerator.java:141)
    at io.vidocq.vauban.maven.generate.GenerateMojo.execute(GenerateMojo.java:62)
```

Aucun `META-INF/vauban-beans.list` n'est produit pour le module concerné, et tous les modules suivants en dépendance Maven échouent en cascade.

### Repro minimal

Un projet Maven qui :
1. déclare `org.eclipse.microprofile.config:microprofile-config-api:3.1.1` en `compile`,
2. exécute `vauban:generate` (phase `process-classes`).

Le scénario est exactement celui de `ravel-cdi-vauban` packagé via Vauban (cf. `ravel/CLAUDE.md`).

### Cause racine

Deux call sites de `Class.forName(name, false, cl)` dans `VaubanGenerator` :

| Ligne | Contexte | Catch d'origine |
|---|---|---|
| ~211 | génération de proxy / interceptor (dans `generate`) | `ClassNotFoundException` |
| ~352 | `loadArchiveClasses` (chargement réflexif des classes indexées) | `ClassNotFoundException` |

`Class.forName(name, false, cl)` ne déclenche pas l'init statique mais déclenche le **linkage** : la JVM doit résoudre la superclasse, les interfaces et les types des champs/méthodes. Toute classe référencée absente du classloader passé en paramètre lève `NoClassDefFoundError` (sous-classe de `LinkageError`, donc *pas* de `ClassNotFoundException`).

Le plugin construit son classloader via `GenerateMojo.buildClassLoader(...)` à partir de `project.getArtifacts()`. Les dépendances marquées `optional=true` chez l'artefact scanné (typiquement `microprofile-config-api` → `jakarta.activation`) ne remontent pas jusqu'au classpath du plugin → premier call site qui touche un champ/méthode de la classe importée explose en `NoClassDefFoundError`.

### Fix

`vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/generate/VaubanGenerator.java`, **les deux** call sites élargissent leur catch à `NoClassDefFoundError` :

- `loadArchiveClasses` (~352) : ignore silencieusement (la classe ne sera pas chargée pour la suite du pipeline BCE / proxy / interceptor, mais reste disponible côté indexer bytecode).
- Génération proxy (~211) : ajoute le warning existant (« Cannot load class for generation: <fqn> »).

Pas de changement de sémantique vis-à-vis du contrat normal : on remplace simplement un crash dur par une dégradation gracieuse alignée sur le comportement déjà en place pour `ClassNotFoundException`.

### Comment éviter la régression

`VaubanGeneratorTest` passe toujours (9/9). À ajouter en suivi : `shouldSkipClassWithMissingTransitive` qui génère via Class-File API une classe annotée `@ApplicationScoped` étendant un `com.missing.Parent` inexistant, l'embarque dans un JAR scanné, et vérifie que `generate()` retourne sans lever.

### Élargissement éventuel

`Class.forName` peut aussi lever d'autres `LinkageError` (`ClassFormatError`, `IncompatibleClassChangeError`, `UnsupportedClassVersionError`, `VerifyError`). Si on observe l'un de ces cas en production, élargir le catch à `LinkageError` (parent commun). Pour l'instant le fix reste ciblé sur le symptôme observé.

---

## VAU-PRX-002 — Drift entre `ClientProxyGenerator` (compile-time) et `RuntimeClientProxyGenerator` (runtime)

**Date** : 2026-05-07
**Statut** : `FIXED` — 2026-05-07
**Sévérité** : haute (rend tout bean `@ApplicationScoped` injecté via `cassini-cdi-vauban` non utilisable côté JAX-RS quand le `_ClientProxy.class` est pré-généré par APT).

### Symptôme

`InterceptorBeanWrapper.getOrCreateProxy` lève une `DeploymentException("Failed to create client proxy for normal-scoped bean ...")`. Cassini fallback alors sur `cls.getDeclaredConstructor().newInstance()` qui retourne une instance brute non-injectée → NPE à l'invocation (`this.dataSourceInstance is null`, `this.products is null`, etc.).

```
Caused by: java.lang.NoSuchMethodException: …DatabaseInspectorResource_ClientProxy.<init>()
    at java.lang.Class.getDeclaredConstructor(Class.java:2491)
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.lambda$getOrCreateProxy$0(InterceptorBeanWrapper.java:235)
```

### Cause racine

Deux générateurs de client proxy coexistaient avec des contrats incompatibles :

- `vauban-processor/.../ClientProxyGenerator.java` (compile-time, APT) émettait : field `delegate` final + ctor `(Supplier)` + putfield au constructeur.
- `vauban-core/.../RuntimeClientProxyGenerator.java` (runtime fallback) émet : field `$$delegate` non-final + ctor no-arg + setter `$$setDelegate(Supplier)`.

`InterceptorBeanWrapper.getOrCreateProxy` (lignes 235-249) attend exclusivement le second format. `loadOrDefineClassRobustly` charge en priorité le `_ClientProxy.class` pré-généré par APT, donc le format ancien shadows toujours le runtime.

### Pourquoi `vidocq-mps-rest-example` fonctionnait quand même

Son `target/classes/.../TodoResource_ClientProxy.class` venait d'une compilation antérieure faite avec une version du `ClientProxyGenerator` qui produisait déjà le format moderne — il n'avait simplement pas été régénéré depuis le drift.

### Fix

`vauban/vauban-processor/src/main/java/io/vidocq/vauban/processor/codegen/proxy/ClientProxyGenerator.java` : aligné sur le format `RuntimeClientProxyGenerator` :
- Field `$$delegate` (non-final).
- Constructor public no-arg appelant `super()`.
- Méthode `$$setDelegate(Supplier)`.
- Tous les `getfield "delegate"` → `getfield "$$delegate"`.

Tests `ClientProxyGeneratorTest` mis à jour pour utiliser le nouveau contrat (ctor no-arg + setter).

### Comment éviter la régression

`ClientProxyGeneratorTest.shouldDelegateMethodCalls` / `shouldDelegateVoidMethods` invoquent désormais le proxy via `getDeclaredConstructor().newInstance()` puis `getMethod("$$setDelegate", Supplier.class)` — exactement le code que fait `InterceptorBeanWrapper.getOrCreateProxy`. Tout futur drift unilatéral d'un des deux générateurs casse immédiatement ces tests.

---

## VAU-INJ-001 — Field injection résout immédiatement les beans normal-scope (perd le client proxy)

**Date** : 2026-05-07
**Statut** : `FIXED` — 2026-05-07
**Sévérité** : haute (rend `@TransactionScoped` / `@RequestScoped` non utilisable en `@Inject` field direct).

### Symptôme

Quand un bean `@ApplicationScoped` (ou autre bean managé) déclare un `@Inject T`
où `T` est un bean **normal-scope** (`@TransactionScoped`, `@RequestScoped`, …),
l'injection field tente de résoudre le contexte au moment du boot/de la
création de l'instance — donc *hors-scope* — et lève un
`ContextNotActiveException`. Le field reste `null` ; toute invocation suivante
provoque un NPE.

```
INJECTION FAILED FOR audit ON class …ProductResource$$Intercepted :
  @TransactionScoped accessed outside an active transaction
jakarta.enterprise.context.ContextNotActiveException
    at io.vidocq.mansart.transactions.cdi.TransactionScopedContext.requireActiveTx
    at io.vidocq.mansart.transactions.cdi.TransactionScopedContext.get
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.lambda$getOrCreateProxy$0
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.getOrCreateProxy
    at io.vidocq.vauban.core.container.VaubanContainer.getOrCreateProxyForBean
    at io.vidocq.vauban.core.container.VaubanBeanManager.getReference        ← ici
    at io.vidocq.vauban.core.container.BeanInjector.lambda$injectFieldsByReflection$0  ← appelle getReference au boot
```

### Repro minimal

Le projet `vidocq-mps-mansart-h2-example` reproduit en module-path :

```java
@ApplicationScoped
@Path("/products")
public class ProductResource {
    @Inject OperationAudit audit;        // @TransactionScoped → null après injection

    @POST @Transactional
    public Response create(Product input) {
        audit.record("…");                // NPE this.audit is null
    }
}

@TransactionScoped
public class OperationAudit implements Serializable { … }
```

### Hypothèse de cause

`BeanInjector.injectFieldsByReflection` (vauban-core, ligne ~90) :

```java
value = bm.getReference(resolved, fieldType, ctx);
```

Pour un bean normal-scope, `getReference` doit retourner un **client proxy
paresseux** : un objet wrapper qui résout le contexte *à chaque invocation de
méthode* (pas au moment de la création). Aujourd'hui `getReference` →
`getOrCreateProxy` → `ApplicationContext.get` → `ManagedBean.create` → ce qui
tente de créer l'instance immédiatement. Hors-scope, l'`OperationAudit` ne
peut pas être créé et le proxy retourné est null.

Le client proxy `OperationAudit_ClientProxy` existe d'ailleurs (généré par
`ClientProxyGenerator`), avec un constructeur `(Supplier<OperationAudit>)` —
c'est exactement le pattern correct. Mais `BeanInjector` ne l'instancie pas ;
il appelle `getReference` qui résout l'instance, perdant la lazy-resolution
qu'offre le proxy.

### Workaround documenté

Injecter via `Provider<T>` ou `Instance<T>` : `BeanInjector` a une branche
spécifique (lignes 38-53) pour ces types qui crée correctement un wrapper
paresseux.

```java
@Inject Provider<OperationAudit> audit;   // OK
…
audit.get().record("…");                  // résout dans le scope actif
```

### Fix appliqué (2026-05-07)

**Cause racine réelle** : `VaubanContainer.getContextualInstance` appelait
`context.get(contextual)` (sans `CreationalContext`, i.e. "look up existing")
AVANT de vérifier `isNormal()`. Pour un scope inactif au boot
(`@TransactionScoped` hors TX, `@RequestScoped` hors requête), ce `context.get()`
appelle `checkActive()` → `ContextNotActiveException`. Cette exception était
avalée par le catch de `BeanInjector` laissant le field à `null`.

Deuxième vecteur : le catch-all dans `InterceptorBeanWrapper.getOrCreateProxy`
dégradait vers `ctx.get()` eagerment pour tout scope en cas d'exception
pendant la création du proxy.

**Fix 1 — `VaubanContainer.getContextualInstance`** : déplacer le check
`isNormal()` en première instruction, avant tout appel à `context.get()`.
Le proxy est retourné immédiatement sans jamais toucher le contexte.

**Fix 2 — `InterceptorBeanWrapper.getOrCreateProxy` catch block** : pour les
beans `isNormal()`, lancer `DeploymentException` au lieu de tenter `ctx.get()`
eagerly.

**Test TDD ajouté** : `NormalScopeFieldInjectionTest` (4 cas) dans
`vauban-core/src/test/java/io/vidocq/vauban/core/container/`.

262 tests vauban-core — 0 failures, 0 errors après fix.

**Régression TCK découverte et corrigée (2026-05-07)** : le Fix 2 causait 7 failures TCK CDI
(`EventTypesTest`, `MemberLevelInheritanceTest`, `InvokerAssignabilityTest`,
`VarargsMethodInvokerTest`). Cause réelle : `RuntimeClientProxyGenerator.generateProxyMethod`
plantait pour les méthodes ayant des paramètres de type tableau (`Song[]`, `int[]`,
`String...` varargs) car `Class.describeConstable()` retourne `Optional.empty()` pour ces
types et le fallback `ClassDesc.of(type.getName())` utilisait le format descripteur JVM
(ex. `"[Lorg...Song;"`) que `ClassDesc.of()` rejette. La `DeploymentException` du Fix 2
exposait cette erreur de génération qui était auparavant silencieusement ignorée.

**Fix 3 — `RuntimeClientProxyGenerator.classDescOf(Class<?>)`** : helper qui utilise
`ClassDesc.ofDescriptor(type.descriptorString())` comme fallback — format accepté pour
tous les types (tableaux, primitifs, références). Tous les appels
`describeConstable().orElse(ClassDesc.of(...))` remplacés par `classDescOf()`.

TCK CDI **774/774 PASS** — 0 failures après le Fix 3.
