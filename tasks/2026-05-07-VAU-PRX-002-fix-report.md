# Report — VAU-PRX-002: drift between `ClientProxyGenerator` (APT) and `RuntimeClientProxyGenerator`

**Date**: 2026-05-07
**Status**: `RESOLVED`
**Scope**: `vauban-processor`, `vauban-core`, `vauban/BUG.md`
**Final validation**: `vidocq-runtime-mansart-h2-example` returns 200/201/204 on all endpoints, no more NPE.

---

## 1. Context of the report

The `vidocq-runtime-mansart-h2-example` application started correctly (the 7 Vidocq extensions load, Chappe opens port 8080), but every HTTP request to a JAX-RS resource crashed:

```
GRAVE: Cassini dispatch error
java.lang.NullPointerException: Cannot invoke "jakarta.enterprise.inject.Instance.get()"
    because "this.dataSourceInstance" is null
        at io.vidocq.runtime.examples.mansart.DatabaseInspectorResource.rawProducts(...:37)
        at io.vidocq.cassini.internal.Invoker.invokeInternal(Invoker.java:693)
```

Symmetrically for `ProductResource.list`: `this.products is null`.

The twin example `vidocq-runtime-cassini-rest-example` (same `@ApplicationScoped @Path` + `@Inject T` pattern) worked, which made the diagnosis non-trivial.

## 2. Investigation approach

### 2.1. Initial mapping (3 parallel explorations)

- **mansart-h2 example** vs **rest example**: comparison of classes, scopes, `module-info.java`, and the contents of `target/classes/META-INF/`.
- **Vauban event resolver**: audit of the stray `DEBUG EVENT` log that surfaced in the user-facing output.
- **Vauban field injection**: audit of the `BeanInjector.injectFieldsByReflection` flow and cross-module resolution.

Intermediate conclusion: three contradictory leads, no clear root cause. Decision: switch to live diagnosis instead of continuing with theory.

### 2.2. Checking existing tests

`vauban-core/src/test/.../NormalScopeFieldInjectionTest.java::Fix4RegressionTest` already modeled — according to its comment — `DatabaseInspectorResource + ProductResource combined` (fix VAU-INJ-001 documented in `BUG.md`). Run in isolation: **8 tests pass**. So the generic scenario "intercepted `@ApplicationScoped` + `@Inject Instance<T>` + `@Inject @Singleton-intercepted`" did NOT reproduce the bug.

### 2.3. Targeted runtime diagnosis

Added `[VAU-INJ]` logs in `BeanInjector.injectFieldsByReflection` and `[VAU-SEL]` logs in `VaubanContainer.select` (gated by `-Dvauban.debug.inject=true`). Light build of `vauban-core` (dependents automatically pick up the M2 snapshot).

**First run**: no `[VAU-INJ]` trace for `DatabaseInspectorResource` or `ProductResource`, and **no** `[VAU-SEL]` trace either. So Cassini was not requesting these beans from Vauban — it was instantiating them via a fallback.

**Second run** (logs at the start of `select()` + around `getContextualInstance`): `select-entry` does appear, but `getContextualInstance` throws:

```
[VAU-SEL] -> EXCEPTION getContextualInstance
   type=...DatabaseInspectorResource
   ex=jakarta.enterprise.inject.spi.DeploymentException
   msg=Failed to create client proxy for normal-scoped bean ...DatabaseInspectorResource
Caused by: java.lang.NoSuchMethodException:
   ...DatabaseInspectorResource_ClientProxy.<init>()
```

### 2.4. Root cause

`InterceptorBeanWrapper.getOrCreateProxy:235` calls `proxyClass.getDeclaredConstructor().newInstance()` (no-arg ctor). The loaded `_ClientProxy.class` did NOT have a no-arg ctor.

`javap` comparison:

```text
# rest-example (OK)
class TodoResource_ClientProxy extends TodoResource {
  private Supplier $$delegate;
  public TodoResource_ClientProxy();              ← no-arg ctor
  public void $$setDelegate(Supplier);
  ... overrides ...
}

# mansart-h2-example (FAIL)
class DatabaseInspectorResource_ClientProxy extends DatabaseInspectorResource {
  private final Supplier delegate;                ← final, different name
  public DatabaseInspectorResource_ClientProxy(Supplier);  ← ctor takes Supplier
  ...
}
```

Two incompatible generators were producing `_ClientProxy.class` files:

| Generator | Location | Format | When used |
|---|---|---|---|
| `ClientProxyGenerator` | `vauban-processor` (APT, compile-time) | old (ctor `(Supplier)`, final `delegate` field) | written to `target/classes` |
| `RuntimeClientProxyGenerator` | `vauban-core` (Class-File API runtime) | modern (ctor `()`, `$$delegate` field, `$$setDelegate` setter) | runtime fallback via `MethodHandles.Lookup.defineClass` |

`InterceptorBeanWrapper.getOrCreateProxy` accepted only the modern format. `loadOrDefineClassRobustly` loaded the pre-generated APT class first — so the old format always shadowed the runtime one, and the `NoSuchMethodException` was systematic for any bean recompiled after the drift.

**Why rest-example worked**: its `TodoResource_ClientProxy.class` came from a compilation prior to the drift, during which `ClientProxyGenerator` still produced the modern format. mansart-h2-example, recompiled more recently, shipped the old version.

## 3. Fix applied

### 3.1. `vauban-processor/.../codegen/proxy/ClientProxyGenerator.java`

Aligned with the `RuntimeClientProxyGenerator` contract:

```text
- private final Supplier delegate;          → private Supplier $$delegate;
- public Proxy(Supplier d) { ...putfield }  → public Proxy() { super(); }
+ public void $$setDelegate(Supplier d) { this.$$delegate = d; }
- getfield "delegate"                       → getfield "$$delegate"   (3 locations)
```

### 3.2. `vauban-processor/.../ClientProxyGeneratorTest.java`

The tests instantiated the proxy via `getDeclaredConstructor(Supplier.class).newInstance(supplier)`. Migrated to the contract used by the runtime:

```java
var proxy = (T) proxyClass.getDeclaredConstructor().newInstance();
proxyClass.getMethod("$$setDelegate", Supplier.class).invoke(proxy, supplier);
```

This is exactly the code that `InterceptorBeanWrapper.getOrCreateProxy:235-249` executes. Any unilateral drift of either generator now breaks these tests at compile time of the `vauban-processor` module.

### 3.3. Stray log cleanup — `vauban-core/.../event/EventDispatcher.java`

7 occurrences of `System.out.println("DEBUG EVENT: ...")` (the `Checking`, `Mismatch on eventType`, `MATCHED`, `Mismatch on qualifiers`, `invoking`, `invoke successful`, `findMethod returned null` paths) — all removed. Production logs silent again.

### 3.4. Temporary diagnostic trace

The `[VAU-INJ]` / `[VAU-SEL]` logs added for diagnosis were removed from `BeanInjector.java` and `VaubanContainer.java` after confirming the fix.

### 3.5. Documentation — `vauban/BUG.md`

New `VAU-PRX-002` entry (status `FIXED`, 2026-05-07) at the top of the tracker, before `VAU-INJ-001`.

## 4. Validation

### 4.1. Unit tests

```text
vauban-core    : 266 tests, 0 failures, 0 errors  (incl. NormalScopeFieldInjectionTest 8/8)
vauban-processor : 4 ClientProxyGenerator tests OK after contract migration
Reactor vauban : BUILD SUCCESS (full install)
Reactor vidocq  : BUILD SUCCESS (clean install)
```

### 4.2. E2E `vidocq-runtime-mansart-h2-example`

After a cascading `clean install` (to regenerate the `_ClientProxy.class` files in the modern format):

| Endpoint | Verb | Code | Response |
|---|---|---|---|
| `/api/db/products` | GET | 200 | `[Espresso, Cappuccino, Latte]` |
| `/api/products` | GET | 200 | same (via Mansart Data repository) |
| `/api/products` | POST `{"name":"Mocha","price":4.5}` | 201 | `{id:4, name:"Mocha", ...}` |
| `/api/products/1` | DELETE | 204 | — |
| `/api/products/count` | GET | 200 | `3` |

No `GRAVE` / `Exception` / `NPE` / `DEBUG EVENT` line in the logs.

## 5. Secondary bug identified (not fixed here)

During diagnosis, another anomaly appeared: `vauban-indexer/ClassFileScanner.java:99` extracts the raw descriptor of the parameter annotated `@Observes`. For `void onStart(@Observes Startup event)`, the runtime indexes `eventType=ClassType[name=java.lang.Object]` instead of `Startup`. Consequence: the observer matches by accident via `Object` (the universal supertype) — not functionally blocking, but conceptually wrong.

A dedicated task was created to parse the `Signature` bytecode attribute when relevant. See `BUG.md`, to be updated if we want to formally track this anomaly.

## 6. Lessons learned

1. **Two generators with divergent contracts = time bomb**. The `ClientProxyGeneratorTest` test must call the proxy the way the runtime calls it (same API, same instantiation pattern), not via a private API known only to the test authors.
2. **The `_ClientProxy.class` files survive generator changes**. As long as you don't run `mvn clean`, you work with old artifacts. This is why rest-example hid the bug and mansart-h2 revealed it.
3. **Live diagnosis rather than repeated theorizing**. Three consecutive explorations had not converged; a single round-trip of `add trace → light build → re-run → grep` exposed the bug in less than 2 minutes.
4. **The `Caused by` format is more informative than the wrapping exception's class**. `DeploymentException("Failed to create client proxy ...")` was the symptom; `Caused by NoSuchMethodException: ...<init>()` was the cause.

## 7. Files touched

```text
vauban/vauban-processor/src/main/java/io/vidocq/vauban/processor/codegen/proxy/ClientProxyGenerator.java
vauban/vauban-processor/src/test/java/io/vidocq/vauban/processor/codegen/proxy/ClientProxyGeneratorTest.java
vauban/vauban-core/src/main/java/io/vidocq/vauban/core/event/EventDispatcher.java
vauban/BUG.md
vauban/docs/apt-and-proxies.md       (created in parallel, see user doc)
vauban/tasks/2026-05-07-VAU-PRX-002-fix-report.md   (this report)
```
