# Vauban — Bugs reproductibles

Tracker interne. Format minimaliste : id court, date, symptôme, repro,
hypothèse de cause, statut. Mise à jour à chaque investigation.

---

## VAU-INJ-001 — Field injection résout immédiatement les beans normal-scope (perd le client proxy)

**Date** : 2026-05-07
**Statut** : `OPEN`
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

### Fix proposé (à valider)

Dans `BeanInjector.injectFieldsByReflection`, après la résolution du bean
(ligne 79) : si `resolved.getScope().isNormal()`, instancier le client proxy
généré (`<bean>_ClientProxy`) avec un `Supplier` qui appelle `bm.getReference()`
*lazy*, et l'injecter directement — sans matérialiser l'instance au boot.

C'est exactement ce que fait Weld/OpenWebBeans côté CDI complet. Vauban a
déjà toute l'infrastructure (`ClientProxyGenerator`, `Supplier` constructor),
il suffit de la brancher côté injection.
