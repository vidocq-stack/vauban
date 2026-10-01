# Codegen coverage: which bean runs generated code, which falls back to reflection

- **Date:** 2026-10-01
- **Status:** design approved, spec under review
- **Repositories:** `vauban` (SPI, generators, container), then `vidocq` (dev console)
- **Related:** vauban#109 (interceptor methods still called by `Method.invoke`), vauban#93 (superseded by #109)

## Context

Vauban runs every bean operation through the package's generated `_VaubanComponents` provider first, and falls
back to reflection (or `MethodHandle` lookups, or runtime class generation) when no provider owns it:

| Operation | Generated path | Fallback |
|---|---|---|
| Instantiation | `create(className[, args])` | `VaubanLookup.newInstance` (`VaubanContainer.java:672`) |
| Intercepted subclass | pre-generated `<bean>$$Intercepted` found by `Class.forName` | `InterceptorSubclassGenerator` + define at runtime (`InterceptorBeanWrapper.java:634`) |
| Field injection | `injectField(...)` | reflection (`BeanInjector.java:282`) |
| Method call (initializer, lifecycle, producer, disposer, observer) | `invoke(...)` | `MethodHandle` (`VaubanLookup.java:181`) |
| Client proxy | `createClientProxy(...)` | `RuntimeClientProxyGenerator` + define, or `java.lang.reflect.Proxy` for an interface-typed producer (`InterceptorBeanWrapper.java:251/330`) |
| Producer field read | none | `VaubanLookup.getField` |
| Interceptor method (`@AroundInvoke`, ...) | none (vauban#109) | `Method.invoke` (`VaubanInvocationContext.java:356`) |

Coverage is partial by design: `ComponentProviderClassGenerator` lists "only public, top-level beans whose
constructor parameters are all nameable reference types", and `injectField`/`invoke` need the member to be in the
provider's own package. Nothing tells a developer today which of their beans actually run generated code.

The Vidocq dev console lists every bean, interceptor and observer (`CdiInventory`). This design adds, per row,
which code generator covers it and what still goes through reflection.

## Goals

1. Each generated provider **declares** what it covers, from the same lists that feed its dispatch switches.
2. The container answers, for a bean, an observer or an interceptor, **which generator covers each operation it
   needs** and which operations fall back.
3. The dev console shows it in all three tables, plus a count per table in the panel summary.
4. The `from` column says `library` instead of `runtime`: everything that is not the application.

## Non-goals

- No change to runtime dispatch: the container keeps calling providers exactly as today.
- No record of what actually happened at run time (observed fallbacks). The column says what the build produced.
- No test or CI guard and no startup-report line outside the dev console. The container API stays minimal and can
  widen later.
- Synthetic beans, synthetic observers and built-in beans are reported `n/a`, not evaluated.
- The annotation methods of vauban#70 (`annotationMetadata`, `readAnnotation`, `annotationLiteral`) are not
  per-bean operations and stay out of scope.
- Generating code for interceptor methods: that is vauban#109.

## Design

### 1. SPI — `vauban-api`

New record `io.vidocq.vauban.api.GeneratedCoverage`:

```java
public record GeneratedCoverage(Generator generator,
        Set<String> instantiated,    // class names create(...) accepts, including <bean>$$Intercepted and interceptors
        Set<String> injectedFields,  // "<declaring class binary name>#<field name>"
        Set<String> invokedMethods,  // "<declaring class binary name>#<methodId>", methodId as invoke(...) receives it
        Set<String> clientProxies) { // keys createClientProxy(...) accepts
    public enum Generator { APT, CLASS_FILE }
    // compact constructor: Set.copyOf on each set, generator non-null
}
```

New default method on `VaubanComponentProvider`:

```java
/**
 * What this provider runs in-module, for diagnostics only; the container never dispatches on it.
 * {@code null} when the provider predates this method.
 */
default GeneratedCoverage coverage() { return null; }
```

Keys reuse the existing formats: `methodId` is `name(paramErasure,…)` exactly as `MethodInvoke.methodId()` and
`VaubanLookup.methodId(Method)` build it; a producer proxy contributes its lookup **key**
(`<produced type>_ClientProxy`), not its `proxyFqn`.

The sets are built on each call — no static field — so a production run that never calls `coverage()` holds
nothing.

### 2. Generators

- `ComponentProviderGenerator` (APT, source, `vauban-processor`) renders `coverage()` with `Generator.APT`, from
  the same `components`, `fieldInjects`, `methodInvokes`, `clientProxyFqns` and `producerProxies` lists that feed
  `create`, `injectField`, `invoke` and `createClientProxy`.
- `ComponentProviderClassGenerator` (Class-File, `vauban-core/provider`) emits the same method in bytecode with
  `Generator.CLASS_FILE`. It serves `vauban:generate`, `enhance-dependencies`, and `vidocq:generate`'s patches.
- Rendering `coverage()` from the very lists the switches are rendered from is what keeps the declaration and the
  dispatch from diverging.
- Size: one `ldc` + `aastore` per key. A provider is per package; a method-size overflow would need thousands of keys
  in one provider. Past `MAX_COVERAGE_KEYS` (7000), the Class-File generator emits no `coverage()` — the provider is
  then reported unknown — and keeps its dispatch: a diagnostic never costs the container its generated code (a
  review finding: `vauban:generate` swallowed a thrown limit and dropped the whole provider). On the APT side,
  javac's own "code too large" already fails the build.

### 3. Container — `vauban-core`

New public class `io.vidocq.vauban.core.container.CodegenCoverage`, obtained from
`VaubanContainer.codegenCoverage()`, computed on each call, not cached:

```java
public Coverage of(Bean<?> bean);
public Coverage of(ObserverDescriptor observer);
public Coverage of(InterceptorDescriptor interceptor);

public record Coverage(Verdict verdict, List<String> byReflection) {}
public enum Verdict { APT, CLASS_FILE, APT_AND_CLASS_FILE, PARTIAL, REFLECTION, UNKNOWN, NOT_APPLICABLE }
```

#### Operations each row needs

Each operation carries a key, computed with the helpers the runtime uses (`VaubanLookup.methodId`,
`RuntimeClientProxyGenerator.proxyClassName`, `InterceptedShape.SUBCLASS_SUFFIX`), and a label for the
`by reflection` column.

| Row | Operations (label) |
|---|---|
| Managed bean | `constructor` — key `<bean>`, or `<bean>$$Intercepted` when the bean is intercepted; `intercepted subclass` when intercepted — covered when the pre-generated class is found by `Class.forName(name, false, loader)`; `field <name>` per `@Inject` field of the hierarchy; `initializer <m>()`, `@PostConstruct <m>()`, `@PreDestroy <m>()`; `client proxy` when normal-scoped |
| Producer method | `producer <m>()`; `disposer <m>()` when one exists; `client proxy` when normal-scoped (interface type: not covered means `java.lang.reflect.Proxy`) |
| Producer field | `producer field <f>` — never covered: no SPI operation reads a field |
| Observer | `observer <m>()` |
| Interceptor | `constructor`, `field <name>`, own `@PostConstruct`/`@PreDestroy`, and each `@AroundInvoke`/`@AroundConstruct`/lifecycle interceptor method — never covered until vauban#109 |
| Synthetic bean, synthetic observer, built-in bean | none: `NOT_APPLICABLE` |

Which fields and methods a managed bean needs follows the container's own selection rules (`BeanInjector` for
fields, initializers and override rules, `BeanLifecycle` for callbacks): `CodegenCoverage` reuses them rather than
restating them. Two decisions are taken once at boot and are recorded where they are taken, not recomputed: whether
a bean is intercepted and whether its `$$Intercepted` subclass was pre-generated (`InterceptorBeanWrapper`), and
which disposer a producer got (`DisposerInvoker`). The producer, disposer and observer `Method`s are looked up the
way `VaubanContainer`, `DisposerInvoker` and `EventDispatcher` look them up — the observer by its declared event
type, since no event is at hand.

#### Verdict

1. No operation: `NOT_APPLICABLE`.
2. An operation is covered when a provider's `coverage()` lists its key; its generator is that provider's. The
   `intercepted subclass` operation is covered when the pre-generated class exists and adds no generator of its
   own: the provider that lists `<bean>$$Intercepted` in `instantiated` names it through the `constructor`
   operation.
3. An uncovered operation is **unknown** when a provider with `coverage() == null` serves its owner class: the
   provider lives in the owner's package. Every generator writes one `_VaubanComponents` per package (APT,
   `vauban:generate`, `enhance-dependencies`), so the package is what a provider serves. The owner is the field's
   or method's declaring class, and the bean class for `constructor` and `client proxy`.
4. Any unknown operation: `UNKNOWN`. Otherwise all covered: `APT`, `CLASS_FILE` or `APT_AND_CLASS_FILE` by the
   generators met. Some covered: `PARTIAL`. None: `REFLECTION`.
5. `byReflection` lists the labels of uncovered operations, in the table order above. For `UNKNOWN`, the list is
   the unknown operations.

#### What it reads

Metadata only: `getDeclaredFields`/`getDeclaredMethods` without `setAccessible` (no `opens` needed), and
`Class.forName(…, false, …)` (no initialization). No bean is created, no context is read, no provider method other
than `coverage()` is called.

### 4. Dev console — `vidocq` (`vidocq-runtime-devconsole-extension`)

- Columns appended after `from` in all three tables:
  - beans: `class, kind, scope, qualifiers, alternative, from, codegen, by reflection`
  - interceptors: `interceptor, bindings, priority, from, codegen, by reflection`
  - observers: `event, qualifiers, observer, mode, from, codegen, by reflection`
- `APPLICATION_FIRST` sorts on the `from` column by index instead of `row.getLast()`.
- `codegen` values: `APT`, `Class-File`, `APT + Class-File`, `partial`, `reflection`, `unknown`, `n/a`.
- `by reflection`: comma-separated labels, empty when nothing falls back; prefixed `provider predates coverage: `
  for `unknown`.
- `from`: `runtime` becomes `library`; the summary rows read `N of the application, M of the libraries`.
- Panel summary: one row per table, over **all** rows, not only the shown 100 — e.g.
  `beans codegen: 41 APT, 12 Class-File, 3 partial, 20 reflection, 4 n/a` (zero counts omitted).
- Read once in `onStart`, with the rest of the inventory; strings only, as today. `DevMcp` serves the same tables
  and gets the columns for free.
- Documentation: the `cdi-panel` section of `docs/en/modules/ROOT/pages/dev-console.adoc` describes the new columns
  and values.

## Delivery

1. **vauban PR:** SPI record and default method, both generators, `CodegenCoverage`, tests, Javadoc of
   `VaubanComponentProvider`. Merged, then `0.4.0-SNAPSHOT` published.
2. **vidocq PR:** `CdiInventory`, `CdiPanel`, tests, doc page; links this spec.

No brick changes code: its next build regenerates providers that declare their coverage. Until then its beans
show `unknown`, which is accurate.

## Testing

- **Generators (unit):** for each generator, `coverage()` of a generated provider lists exactly the keys its
  `create`/`injectField`/`invoke`/`createClientProxy` accept, with the right `Generator`; the APT output compiles
  and the Class-File output verifies.
- **Container (integration, a container built with test providers that declare a given coverage; the APT output
  itself is covered by the generator tests):** one case per verdict — fully generated bean
  (`APT`); private `@Inject` field (`PARTIAL`, `field x`); class without provider (`REFLECTION`); provider without
  `coverage()` (`UNKNOWN`); `@Dependent` producer field (`REFLECTION`, `producer field f`); intercepted bean
  with and without pre-generated subclass; normal-scoped bean proxy; observer; interceptor (`PARTIAL`, its
  `@AroundInvoke` listed); synthetic and built-in (`NOT_APPLICABLE`).
- **Key parity:** a test asserts that `CodegenCoverage`'s keys for the fixture equal those the runtime passes to
  the provider (same helpers, checked on real `Method`/`Field` objects).
- **Console (unit):** `CdiInventoryTest`, `CdiPanelTest` with a stub coverage — columns, values, `library`, summary
  counts, application-first sort.
- **TCK:** CDI Lite and atinject TCKs run locally with `-Ptck` before the vauban PR (the CI does not gate them,
  vauban#85).

## Risks

- **Selection drift:** if `CodegenCoverage` enumerated fields or methods differently from `BeanInjector`/
  `BeanLifecycle`, it would report operations that never run or miss ones that do. Mitigated by reusing their rules
  and by the key-parity test.
- **Stale providers in the local repository:** a brick jar built before this change reports `unknown`. Verify the
  vidocq PR against bricks rebuilt from a clean local repository.
