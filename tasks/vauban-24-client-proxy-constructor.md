# vauban#24 — réétude : le constructeur du bean s'exécute à la création du client proxy

Statut : **étude, pas de correctif**. Décision demandée avant implémentation.
Date : 2026-08-09. Rapporteur d'origine : Sébastien Blanc (Rossignol).

## 1. Ce qui est établi

Le proxy client étend le bean, et son constructeur sans argument chaîne vers un constructeur du
bean — des deux côtés du codegen :

- source : `ClientProxySourceRenderer` → `public Bean_ClientProxy() { super(<défauts>); }`
- bytecode : `ClientProxyEmitter` → `invokespecial Bean.<init>` avec `pushDefault` par paramètre

Mesuré (sonde jetable sur `RuntimeClientProxyGenerator`, non commitée) :

| Cas | Résultat observé |
|---|---|
| bean `@ApplicationScoped` à constructeur no-arg | **2 constructions** pour une seule instance contextuelle — exactement le rapport |
| bean dont l'unique constructeur est `@Inject Foo(Collaborator c)` **et qui déréférence `c`** | **`NullPointerException`** à la création du proxy : `Cannot invoke "Collaborator.name()" because "collaborator" is null` |

**Le second cas n'était pas dans le rapport et change la nature du problème.** Il ne s'agit pas
seulement d'un effet de bord dupliqué que l'utilisateur pourrait éviter par discipline : l'injection
par constructeur — le style recommandé par la spec CDI — fait **planter** tout bean normal-scoped
dès que son constructeur utilise un de ses paramètres. Le proxy lui passe `null`
(`findSimplestConstructor` + `pushDefault`).

Cela invalide l'option « documenter et recommander `@PostConstruct` » comme réponse suffisante :
aucune discipline sur les effets de bord ne sauve `this.name = collaborator.name()`.

## 2. La contrainte incontournable

La JVM impose que tout `<init>` chaîne vers un `<init>` de sa propre classe ou de sa
**superclasse directe**. Le proxy doit être assignable au type injecté ; quand ce type est une
classe concrète (`@Inject Oidc` chez le rapporteur), le proxy doit l'étendre, donc il ne peut
appeler que `Oidc.<init>`. **Il est impossible d'éviter le constructeur du bean sans agir sur le
bean lui-même.** Toute solution passe par l'une des familles ci-dessous.

## 3. Les familles évaluées

### A. Constructeur marqueur ajouté au bean (post-compile, Class-File API)

`vauban-maven-plugin`, phase `process-classes` : ajouter à chaque bean proxyable un constructeur
synthétique `Bean(VaubanProxyMarker)` au corps vide (chaînage récursif si la superclasse a un
constructeur), puis réécrire l'unique `invokespecial` du `<init>` de `Bean_ClientProxy` — déjà
compilé — pour le cibler.

- ✅ 100 % statique, AOT/GraalVM-safe, zéro réflexion : conforme à la philosophie du projet.
- ✅ Préserve le `new Bean_ClientProxy()` en-module généré dans `_VaubanComponents` (aucun `opens`
  ni réflexion réintroduits).
- ✅ Résout les deux cas du tableau, y compris la NPE.
- ⚠️ Modifie le bytecode du code utilisateur. Le constructeur ajouté doit être `ACC_SYNTHETIC` et
  filtré par l'indexeur, sinon il devient candidat à l'injection par constructeur.
- ⚠️ Les beans venant d'un **jar déjà compilé** ne peuvent pas être patchés par le build courant :
  ils ont besoin du marqueur produit par leur propre build. Acceptable dans l'écosystème Vidocq
  (tout module passe par le plugin), avec repli sur le comportement actuel sinon.
- Infrastructure existante : `ModuleAnalyzer` parse déjà des classes compilées avec
  `ClassFile.of().parse(...)` ; `InterceptedEmitter` en produit. Il manque la transformation.
- Effort estimé : 2–4 j avec les tests d'héritage et de repli.

### B. Instanciation sans appel de `<init>` (voie Weld)

`ReflectionFactory.newConstructorForSerialization(proxyClass, Object::new)` alloue le proxy sans
exécuter aucun constructeur.

- ✅ ~10 lignes, résout tout, y compris la NPE.
- ❌ Réflexion à chaud + `jdk.unsupported` : contraire à la règle « génération statique, pas de
  réflexion runtime » du projet, et demande une configuration GraalVM dédiée.
- ❌ Casse le chemin statique actuel : `_VaubanComponents` fait `new Bean_ClientProxy()` en-module
  précisément pour éviter la réflexion. Y renoncer annulerait le gain du chantier BCE static
  metadata (`opens` retirés).

À écarter, sauf comme repli runtime pour les beans de jars non patchables (cf. limite de A).

### C. Proxy par interface

Ne fonctionne que si le point d'injection porte sur une interface. Le cas rapporté
(`@Inject Oidc`, classe concrète) n'est pas couvert. **Ne résout pas le problème**, seulement une
partie ; à ne considérer que comme optimisation ultérieure.

### D. Solution 100 % APT, sans passe Class-File (réétudiée le 2026-08-10 — impossible)

Question légitime : le proxy est déjà généré en source par l'APT (`ClientProxySourceRenderer`),
pourquoi ne pas y régler le problème et éviter la transformation post-compile ? Trois murs :

1. **JSR 269 ne sait que créer, jamais modifier.** L'API officielle (`Filer.createSourceFile`,
   `createClassFile`) produit de *nouveaux* fichiers ; retoucher une unité de compilation
   existante est hors API. Or la contrainte du §2 impose que le constructeur marqueur vive **dans
   la classe du bean** — du code utilisateur, hors de portée de l'APT. Lombok n'y arrive qu'en
   mutant l'AST via les internes de javac (`com.sun.tools.javac.tree.TreeMaker` + agent /
   `--add-opens jdk.compiler`) : non supporté, fragile sous l'encapsulation forte du JDK 25,
   cassé sous ECJ, et contraire à la règle du projet (pas d'API interne, pas de magie cachée).
2. **Même le côté proxy ne peut pas rester en source pur.** Un constructeur écrit en Java appelle
   forcément un constructeur *existant* de la superclasse : `super(new VaubanProxyMarker())` ne
   compile pas tant que le bean n'a pas ce constructeur — et il ne l'aura jamais par APT (mur 1).
   Œuf et poule à l'intérieur du même round de compilation.
3. **Le bytecode lui-même n'offre pas d'échappatoire.** Le vérifieur JVM exige qu'un `<init>`
   chaîne vers un `<init>` de sa classe ou de la superclasse directe ; « ne pas appeler de
   constructeur » n'est exprimable ni en source ni en bytecode vérifiable — seules les voies
   runtime de B (`ReflectionFactory`/`Unsafe`) le contournent, avec les défauts déjà listés.

Ce que l'APT peut en revanche apporter à **A** : émettre à la compilation la liste exacte des
beans proxyables (manifeste), pour que la passe `process-classes` soit une transformation ciblée
O(beans) et non un scan du jar. À intégrer au chantier A.

À noter aussi : la passe Class-File n'est **pas un mécanisme de build nouveau** — le
`GenerateMojo` de `vauban-maven-plugin` tourne déjà en `process-classes` ; A y ajoute une
transformation de classes existantes, rien de plus.

## 4. Recommandation

1. Retenir **A**, avec **B en repli explicite et documenté** pour les beans issus de jars sans
   marqueur — le repli conserve le comportement d'aujourd'hui plutôt que d'échouer.
2. Avant de coder : écrire les deux tests de non-régression (compteur de constructions, et bean à
   constructeur `@Inject` déréférençant son paramètre) dans `vauban-module-it`. Ils sont rouges
   aujourd'hui et constituent la définition de « corrigé ».
3. Vérifier au passage que `@PostConstruct` n'est pas déclenché sur le proxy.
4. Le TCK CDI Lite passe 774/774 avec le comportement actuel : il ne couvre pas ce point. Ne pas
   se fier au TCK comme garde-fou ici.

## 5. Question ouverte pour la décision

La NPE du constructeur `@Inject` est-elle un défaut à traiter **avant** la 0.3.0 (elle rend
l'injection par constructeur inutilisable sur un bean normal-scoped), ou peut-elle attendre le
chantier A complet ? Un correctif partiel existe : refuser à la compilation un bean normal-scoped
dont le seul constructeur a des paramètres, avec un message qui explique — ce serait honnête
plutôt que silencieux, mais restreint la spec (CDI 4.1 exige que ces beans soient proxyables).
