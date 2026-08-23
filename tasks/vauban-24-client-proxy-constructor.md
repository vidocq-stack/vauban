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

## 4. Correction sur la spec (2026-08-10)

La première version de ce document affirmait que refuser un bean normal-scoped sans constructeur
no-arg « restreindrait la spec ». C'est **l'inverse** : la section *Unproxyable bean types* de
CDI 4.1 liste explicitement — classe `final`, méthodes `final` non privées, **absence de
constructeur no-arg non privé** — et impose au container d'en faire une **erreur de
déploiement**. Le bean du rapporteur (unique constructeur `@Inject` à paramètres) est donc déjà
non-proxyable au sens strict de la spec : l'erreur franche est la réponse *portable*, et le
constructeur marqueur est une **extension de confort** (comme la construction « relaxed » de
Weld), pas une obligation. C'est cohérent avec le TCK CDI Lite qui passe 774/774 sans couvrir ce
point.

## 5. Plan retenu (2026-08-10, après échange mainteneur)

Trois phases incrémentales ; rien n'est jeté d'une phase à l'autre.

### Phase 0 — diagnostic conforme spec (avec la 0.3.0)

Détecter les beans normal-scoped non-proxyables (pas de constructeur no-arg non privé, classe
`final`, méthode `final` non privée, champ public) et **refuser avec un message clair** : à la
compilation par l'APT quand le bean est dans l'unité courante, au boot sinon. Transforme la NPE
silencieuse d'aujourd'hui en erreur explicite — comportement exigé par la spec. ~1 j.

### Phase 1 — convention `ProxyLink` manuelle, opt-in (avec la 0.3.0)

Le développeur peut déclarer `Bean(ProxyLink)` (corps vide, champs `final` assignés à leurs
défauts) ; le bean devient alors proxyable proprement :

- `vauban-api` : type marqueur `ProxyLink` (non instanciable hors container) ;
- `ClientProxyShapeFromElements`/`ClientProxySourceRenderer` (source) et
  `ClientProxyEmitter`/`RuntimeClientProxyGenerator` (bytecode) : cibler ce constructeur
  quand il existe, au lieu de `super(<défauts>)` ;
- indexeur : exclure le constructeur marqueur des candidats à l'injection ;
- le diagnostic de la phase 0 accepte un bean porteur du marqueur (extension documentée).

100 % APT côté build, aucune transformation de bytecode : le mur n°2 de l'option D tombe dès
lors que le constructeur existe dans la source. TDD : compteur de constructions + bean
`@Inject` déréférençant son paramètre, rouges d'abord, dans `vauban-module-it`. Vérifier au
passage que `@PostConstruct` ne se déclenche pas sur le proxy. ~1–2 j. Débloque Rossignol.

### Phase 2 — automatisation Class-File (chantier A complet, post-0.3.0)

`vauban-maven-plugin`, phase `process-classes` : ajouter le marqueur `ACC_SYNTHETIC` à tout bean
proxyable qui ne le déclare pas (manifeste APT des beans proxyables pour une passe ciblée
O(beans)), et faire pointer le `<init>` du proxy dessus. La convention manuelle de la phase 1
devient inutile mais reste honorée. Beans de jars tiers sans marqueur : diagnostic phase 0
(erreur spec) par défaut ; l'option B (`ReflectionFactory`) reste un repli *opt-in* documenté si
un besoin réel apparaît. ~2 j (réduits par la phase 1). L'option C (proxy `implements` pur
delegate quand le point d'injection est une interface) s'y greffe comme optimisation : pas de
constructeur du tout dans ce cas.

## 6. Garde-fous

- Le TCK CDI Lite (774/774) ne couvre ni la double construction ni la NPE : nos tests de
  non-régression de la phase 1 sont le seul filet — les garder.
- Les phases 0 et 1 sont recommandées **avant la 0.3.0** : petites, conformes spec, et elles
  débloquent le cas Rossignol sans attendre le chantier Class-File.
