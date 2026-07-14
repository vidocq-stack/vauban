# Vauban — Reproducible bugs

Internal tracker. Minimalist format: short id, date, symptom, repro,
suspected cause, status. Updated on every investigation.

---

## VAU-INT-001 — overloaded intercepted methods collide on the `$$ti$<name>` glue
- **Date**: 2026-06-07 — **Status**: FIXED (unique per-overload `$$ti$` names in both renderers)
- **Severity**: medium (any bean with two intercepted methods of the same name fails to deploy)
- **Surfaced by**: MicroProfile Metrics 5.1 TCK `OverloadedTimedMethodBeanTest` (dirac), via the
  runtime `InterceptedEmitter`. Invisible to the vauban golden tests (no overloaded fixture).

### Symptom
```
Cannot create interceptor subclass: Duplicate method name "$$ti$overloadedTimedMethod"
with signature "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
in class file …/OverloadedTimedMethodBean$$Intercepted
```
A bean with two `@Timed`/`@Audited` methods of the same name (overloads) fails at subclass
generation/compilation.

### Cause
The `TargetInvoker` glue `$$ti$<name>` is named by method **name only** and erases to
`(Object, Object[]) Object`. Two overloads → two `$$ti$<name>` methods with identical name **and**
descriptor → invalid class file (bytecode) / duplicate method (source). The `$$super$<name>` bridges
do **not** collide (they keep the methods' distinct parameter descriptors). Naming dates back to the
B2 `invokedynamic`/`LambdaMetafactory` target-invoker work; the bug is pre-existing, shared by the
runtime/plugin bytecode emitter (`InterceptedEmitter`) and the APT source renderer
(`InterceptedSourceRenderer`).

### Fix
`targetInvokerNames(List<MethodShape>)` in both `InterceptedEmitter` and `InterceptedSourceRenderer`:
a non-overloaded name stays `$$ti$<name>` (golden snapshots byte-for-byte stable); each overload of a
repeated name gets a `$<occurrence>` suffix (`$$ti$run$0`, `$$ti$run$1`, …). The override's
`invokedynamic`/method-reference and the glue definition use the same unique name. Verified: dirac MP
Metrics TCK 127/127 (was 126+1 error) and a new compile-time test
`generatesDistinctGlueForOverloadedInterceptedMethods` (source path).

---

## VAU-INT-002 — primitive-array params/return mishandled in intercepted methods (`TypeRef` ignores array dims)
- **Date**: 2026-06-07 — **Status**: FIXED (`TypeRef.isPrimitive()`/`isCategory2()` now require `dims == 0`)
- **Severity**: medium (any intercepted method with an `int[]`/`long[]`/… param or return fails to load)
- **Surfaced by**: MicroProfile Fault Tolerance 4.1 TCK CircuitBreaker tests (heisenberg) — beans with
  `serviceA(int[])`. 458/463, 4 failures + 1 deployment error. Invisible to the vauban golden tests.

### Symptom
```
java.lang.VerifyError: Bad local variable type … in CircuitBreaker…$$Intercepted.serviceA([I)…
@39: iload_1  Reason: Type '[I' (locals[1]) is not assignable to int
```
The override loads an `int[]` parameter with `iload` (scalar int) instead of `aload` (reference).

### Cause
`TypeRef` stores a primitive-array as `primitive=INT, dims=1`, but `isPrimitive()` returned
`primitive != null && primitive != VOID` — **ignoring `dims`**. So `int[]` was treated as a scalar
`int`: `boxAndLoad`/`castOrUnboxParam` emitted `iload`/`Integer.valueOf`, and the slot arithmetic
(`isCategory2()` likewise ignoring dims) under/over-counted for `long[]`/`double[]`. An array of a
primitive is a **reference** type (one slot, `aload`/`areturn`, never boxed). Pre-existing since the
Phase 15 `TypeRef` model; shared by `InterceptedEmitter` (bytecode) and `InterceptedSourceRenderer`.

### Fix
`isPrimitive()` and `isCategory2()` now also require `dims == 0`. `InterceptedSourceRenderer.sourceName`
branches on `primitiveKind() != null` (non-null for primitive arrays, whose `binaryName` is null) so
`int[]` renders as `int[]` rather than NPEing. Verified: heisenberg FT TCK 463/463 (was 458 + 5) and a
new compile-time test `generatesValidGlueForPrimitiveArrayInterceptedMethods` (source path).

---

## VAU-INT-003 — source-rendered `$$Intercepted` of a method declaring `throws` fails to compile
- **Date**: 2026-06-07 — **Status**: FIXED (`InterceptedSourceRenderer`: pre-init guard moved inside the try)
- **Severity**: medium (any APT-generated subclass of a bean whose intercepted method declares a checked
  exception fails `javac` — `unreported exception java.lang.Exception; must be caught or declared`)
- **Surfaced by**: the new `mansart-transactions-cdi-jpms-it` module-path vehicle — `TxService.required(Block)`
  declares `throws Exception`. Invisible to the golden/structural tests (no checked-throws fixture) and to
  the bytecode emitter (the JVM does not enforce checked exceptions on `super.<m>()`), so only the SOURCE
  renderer (APT) is affected, and only when a bean's intercepted method declares a checked exception.

### Cause
The generated override drops the original `throws` clause (a `MethodShape` carries no exceptions — by
design, the intercepted path rethrows via `sneaky(...)`). But the **pre-init guard** that delegates to
`super.<m>(...)` before `$$init` ran was emitted *outside* the `try { … } catch (Throwable) { throw
sneaky(t); }` block, so `javac` saw an uncaught checked exception from the bare `super` call.

### Fix
The pre-init guard is now emitted as the first statement *inside* the `try`, so the checked exception from
`super.<m>(...)` propagates through `sneaky(...)` (which rethrows the very same throwable — behaviour
identical to the bytecode emitter). The vehicle's `TxModulePathTest` (a `@Transactional` bean whose
methods `throws Exception`) is the live regression: it generates, compiles and runs the build-time
subclass on the module path. (A vauban-local compile-time test mirroring it is a follow-up.)

---

## VAU-INT-004 — `@Interceptor` beans are not emitted into `_VaubanComponents` (module-path needs an opens)
- **Date**: 2026-06-07 — **Status**: FIXED (APT emits interceptors as components; runtime routes their instantiation through the provider)
- **Severity**: medium (a wrapper that declares `@Interceptor` beans cannot reach zero-opens on a strict
  module path: the interceptor instances fall back to reflective instantiation)
- **Surfaced by**: `mansart-transactions-cdi-jpms-it` — `container.select(TxService.class)` on the module
  path throws `Cannot reflectively access …TransactionalInterceptorRequiresNew … provide a generated
  VaubanComponentProvider … or open the package`. Invisible to every class-path TCK (dirac Metrics,
  heisenberg FT, etc. run on the class path, where reflection is unrestricted).

### Cause
`VaubanProcessor.CDI_ANNOTATIONS` (and the plugin's `VaubanGenerator`) select scope-annotated beans and
`@Produces` only — **`jakarta.interceptor.Interceptor` is excluded**. So the APT discovers interceptors
for binding resolution (`discoverInterceptors()`) and emits their `$$Intercepted` subclasses for target
beans, but never emits the interceptor classes themselves into `_VaubanComponents.create()`/`_Factory`.
On the module path the container therefore instantiates them reflectively, which needs
`opens … to io.vidocq.vauban.core`.

### Fix
Two parts. **(1) APT** — `jakarta.interceptor.Interceptor` is added to `VaubanProcessor.CDI_ANNOTATIONS`, so
interceptor classes enter the APT round/index and `BeanDiscovery` (which already lists `@Interceptor` as a
bean-defining annotation) returns them as `@Dependent` managed beans. They then flow through the normal
component pipeline: a `_Factory`, a `_VaubanComponents.create()` arm and `injectField` coverage (their
`@Inject` fields are package-private; inherited fields are keyed on the declaring class by `BeanInjector`,
so the empty per-TxType subclasses are covered too). `isInterceptedTarget` now returns `false` for
`@Interceptor` classes so no bogus `Interceptor$$Intercepted` is generated. **(2) Runtime** —
`InterceptorBeanWrapper` routed both interceptor-instantiation sites through `instantiatePreferProvider(...)`
(renamed from `instantiateIntercepted`): in-module provider → public Lookup → reflection. The interceptor's
public `@AroundInvoke` is already reachable without opens (F3 public-member guard).

Proven by `mansart-transactions-cdi-jpms-it` with **zero opens** (REQUIRED on the base interceptor and
REQUIRES_NEW on an empty subclass with an inherited `@Inject` field). Non-regression: dirac Metrics 5.1
127/127, heisenberg FT 4.1 463/463, vauban full suite green. The plugin (`VaubanGenerator`) is unchanged —
all first-party interceptor wrappers use the APT; mirroring it for external jars is a follow-up.

---

## VAU-INT-005 — APT forces `@InterceptorBinding` completion, breaking module-path interception of marker bindings
- **Date**: 2026-06-07 — **Status**: FIXED (name-based meta detection, no symbol completion)
- **Severity**: medium (a wrapper whose interceptor binding is a `@InterceptorBinding`-meta marker —
  e.g. heisenberg's `@FaultToleranceBinding` stamped on beans by a BCE `@Enhancement` — cannot have its
  targets' `$$Intercepted` generated by the APT for a downstream module on a strict module path)
- **Surfaced by**: `heisenberg-cdi-vauban-jpms-it` — the `@Retry` fixture's `default-compile` fails with
  `cannot access jakarta.interceptor.InterceptorBinding / class file … not found`
  (`com.sun.tools.javac.code.Symbol$CompletionFailure`). Invisible to every class-path TCK (heisenberg FT
  runs `useModulePath=false`); also distinct from dirac's `@Counted`, which is registered as a custom
  binding via `meta.addInterceptorBinding(...)` and is not itself `@InterceptorBinding`-meta.

### Cause
`VaubanProcessor.isInterceptorBinding(am)` decided "is this annotation an interceptor binding?" with
`am.getAnnotationType().asElement().getAnnotation(jakarta.interceptor.InterceptorBinding.class) != null`.
The class-literal form forces javac to **complete** the `jakarta.interceptor.InterceptorBinding` symbol.
When the binding marker (`@FaultToleranceBinding`) is declared in a dependency module that only
`requires static jakarta.interceptor`, that completion fails for a downstream module-path compile: a
`requires static` read edge is absent from a consumer's module graph, so the marker module cannot read
`jakarta.interceptor` during the consumer's annotation-processing round. The unchecked `CompletionFailure`
aborts the APT. Neither `requires transitive jakarta.interceptor` on the marker module nor
`--add-reads` on the consumer fixes it — the failure is in symbol completion during the APT round.

### Fix
Detect the meta-`@InterceptorBinding` by **name** over the annotation type's own meta-mirrors instead of by
class literal — the same pattern already used for the `@Interceptor` early-return in `isInterceptedTarget`.
Reading a meta-mirror's `getAnnotationType().toString()` only touches the constant pool, so it never
triggers completion. Behaviour is identical for every resolvable case (custom non-meta bindings like
`@Counted` still return `false`; meta-bindings still return `true`), but a marker behind `requires static`
no longer aborts the build.

Proven by `heisenberg-cdi-vauban-jpms-it` (`@Retry` intercepted 4× on the module path, **zero opens**,
`FaultToleranceService$$Intercepted` generated at build time). Non-regression: dirac Metrics 5.1 127/127,
heisenberg FT 4.1 463/463, vauban full suite green. The `requires transitive jakarta.interceptor`
work-around attempted before this fix was reverted (unnecessary once the APT stops forcing completion).

---

## VAU-DISC-002 — non-bean archive over-discovers an annotated, non-scanned class (trade-off vs VAU-DISC-001)
- **Date**: 2026-06-07 — **Status**: FIXED (commit `80e953c` — explicit bean-discovery mode)
- **Severity**: low (1 CDI TCK failure)
- **Surfaced by**: CDI TCK `CustomStereotypeTest` (`build.compatible.extensions.customStereotype`).

### Symptom
`CustomStereotypeTest.test:49` fails with `expected [true] but found [false]`. The failing line is
`assertTrue(getBeans(NotDiscoveredBean).isEmpty())` — i.e. **`NotDiscoveredBean` IS discovered when
it must not be**. (The earlier two assertions pass, so the custom-stereotype *scope* — `addStereotype`
+ `@ApplicationScoped` on `MyService` — actually works; the initial "MetaAnnotations gap" reading was
wrong.) The archive has no `beans.xml` (`beanArchive(false)`), the BCE `@Discovery` adds only
`MyService` via `ScannedClasses.add`, and `NotDiscoveredBean` is `@Dependent` but neither scanned nor
forced — yet it surfaces as a bean.

### Cause
`BeanDiscovery.isAllowedByScannedClassesFilter` admits any bean-defining-annotated class even in a
non-bean archive (the VAU-DISC-001 fix). That is exactly what the Mansart Data TCK relies on: its
`@Singleton @Produces DataSource` lives in a `beanArchive(false)` archive, is not scanned, and must
still be discovered (else every `@Repository` is unsatisfied → `EntityTests.setup` NPEs). So the two
TCKs demand opposite answers for the *same* Vauban inputs — `beanArchive(false)` + BCE
`ScannedClasses.add` + an annotated, non-scanned class. Verified: replacing the annotation bypass
with `forcedBeanClasses.contains(...)` makes the CDI TCK 774/774 **but** regresses Mansart Data to
73 errors ("DataSource bean not found"). Reverted.

### Fix
Made the discovery mode explicit instead of guessing from `beanArchive(boolean)`:
`BeanDiscovery.setStrictScannedDiscovery` / builder `strictScannedDiscovery(boolean)`.
`false` (default, "annotated") keeps the VAU-DISC-001 bypass → Mansart's non-scanned `@Produces`
stays discovered; `true` ("none" / CDI-Lite synthetic) admits only `forcedBeanClasses` (BCE
`ScannedClasses` + class-path bean-archive scan). The CDI TCK runner
(`vauban-tck-runner` `VaubanDeployableContainer`) sets `strictScannedDiscovery(!hasBeansXml)`, so its
`withoutBeansXml()` BCE archives use "none" mode; Mansart's runner leaves the default. Verified BOTH
ways: **CDI TCK 774/774** (CustomStereotypeTest passes) and **Mansart Data TCK 73/73** (unchanged).

---

## VAU-EVT-001 — checked exception from a synchronous observer wrapped as CreationException
- **Date**: 2026-06-07 — **Status**: FIXED (commit on `pr/ybl/wip-bce-static-metadata`)
- **Severity**: medium (CDI TCK `CheckedExceptionWrappedTest`)

### Symptom
A synchronous observer throwing a **checked** exception surfaced as `CreationException` instead of
the CDI-required `ObserverException`.

### Cause
When observer dispatch was rerouted through `VaubanLookup.invokeMethod` (in-module provider first,
reflective fallback), `invokeMethod` wraps a non-runtime throwable in `CreationException` — correct
for producers, wrong for observers. `CreationException extends RuntimeException`, so the dispatch's
`catch (RuntimeException) rethrow` branch re-threw it verbatim and the
`catch (Exception) -> ObserverException` branch became unreachable.

### Fix
`EventDispatcher` now wraps each observer-method invocation: a `CreationException` from
`invokeMethod` (which always wraps the observer's own checked exception — runtime exceptions/errors
are propagated unwrapped) is unwrapped and rethrown as `ObserverException`. CDI TCK: 2 → 1 failure.

---

## VAU-DISC-001 — non-bean archive + BCE `ScannedClasses` suppressed annotated beans (incl. `@Produces`)
- **Date**: 2026-06-02 — **Status**: FIXED
- **Severity**: high (any non-bean archive whose Build Compatible Extension calls
  `ScannedClasses.add(...)` — every legitimately-annotated bean outside the scanned set silently vanished)
- **Surfaced by**: Mansart Jakarta Data 1.0 TCK harness — the whole official `EntityTests` suite
  (73/73) errored in `@BeforeEach` with `assertNotNull` on injected repositories. The misleading
  proximate symptom was a `null` injection; the real cause was upstream in Vauban.

### Symptom
A container built with `beanArchive(false)` whose BCE adds classes via `ScannedClasses.add(...)`
discovered **only** the scanned classes. Any other class in the archive — even one carrying a
bean-defining annotation — was dropped, including its `@Produces` members. In the TCK this meant the
test-supplied `@Singleton @Produces DataSource` (`H2DataSourceProducer`) was never registered, so
`MansartRuntimeProducer` saw an unsatisfied `Instance<DataSource>` and threw "No @Default DataSource
bean found", which left every `@Repository`'s `RepositoryRuntime` unsatisfied → `@Inject` repo = null.

### Cause
`VaubanContainerBuilder.build()` calls `discovery.setScannedClassesFilter(scannedDotNames)` when
`!isBeanArchive` and the BCE scanned classes (lines ~818). In `BeanDiscovery.discoverBeans()` the
filter (`isAllowedByScannedClassesFilter`) ran **before** the bean-defining-annotation check and
turned the scanned set into an exclusive whitelist — contrary to CDI "annotated" discovery, where a
bean-defining annotation is always a discovery trigger and `ScannedClasses.add` only *augments* the
set with otherwise-unannotated classes. (Observers/disposers/interceptors never used this filter,
which is why only managed beans + producers were affected.)

### Fix
`isAllowedByScannedClassesFilter` now also returns `true` for any class with a bean-defining
annotation (index or reflection): the scanned-classes filter governs only *non-annotated* forced
classes and can no longer hide annotated beans. Regression:
`BeanDiscoveryTest$ScannedClassesFilter.annotatedProducerSurvivesScannedClassesFilter`. Full vauban
reactor green; downstream Mansart Jakarta Data TCK now 74/74 (EntityTests 73 + SignatureTests 1).

## VAU-PRX-003 — APT client proxy emits an invalid descriptor for a method whose return/param type is a NESTED class
- **Date**: 2026-06-01 — **Status**: FIXED
- **Severity**: high (any `@ApplicationScoped`/normal-scoped bean with a method returning or taking a
  nested class; the bean becomes uninstantiable at runtime once injected)
- **Surfaced by**: Arago Phase 1 — `AttendeeTokens.verify()` returning the nested record
  `AragoJwt.Claims`. Injecting `AttendeeTokens` into a resource threw, at request time:
  `NoClassDefFoundError: io/vidocq/tools/arago/auth/AragoJwt/Claims` (note the `/` before `Claims`).

### Symptom
The compile-time `*_ClientProxy` for such a bean has a method whose bytecode descriptor uses `/`
where `$` is required for a nested class (e.g. `Lio/.../AragoJwt/Claims;` instead of
`Lio/.../AragoJwt$Claims;`). Loading the proxy and resolving its methods (e.g. `getMethod(...)` during
interceptor-proxy setup) raises `NoClassDefFoundError` / `ClassNotFoundException` with a dotted name.

### Cause
APT path only. `ElementScanner.typeMirrorToTypeInfo` built the `DotName` for a `DeclaredType` from
`TypeElement.getQualifiedName()` — the **canonical** name (`Outer.Nested`, dot-separated). Fed to
`ClassDesc.of(...)`, the dot becomes a `/`, yielding `Outer/Nested`. (The runtime
`RuntimeClientProxyGenerator` was correct — it uses `Class.describeConstable()`/`descriptorString()`.)

### Fix
`ElementScanner` now uses `Elements.getBinaryName(element)` (the binary name, `Outer$Nested`) for the
`ClassType`/`ParameterizedType` raw name. Top-level types are unaffected (binary == qualified).
Regression: `BceCompileTimeTest.clientProxyHandlesNestedReturnType` (compiles a bean returning a
nested record, loads the generated proxy, asserts the method resolves with the `$` binary name).

## VAU-PROXY-INTERFACE — no client proxy for an interface-typed normal-scoped bean; `Instance`/`Provider` and nested generics lose their type arguments

**Date**: 2026-05-26
**Status**: `FIXED` — 2026-05-26 (branch `pr/ybl/cdi-interface-proxy-generics`)
**Severity**: high — blocks any normal-scoped bean whose bean type is an **interface** (the common case of a `@RequestScoped @Produces` method returning an interface), and any `Instance<T>`/`Provider<T>` whose `T` is parameterized. Concrete case: MicroProfile JWT's `@Produces @RequestScoped JsonWebToken` and `@Inject Provider<Optional<String>>` / `ClaimValue<Set<String>>` — surfaced by the Cervantes MP JWT 2.1 TCK.

**Symptom (1) — interface client proxy**: injecting a normal-scoped bean whose bean type is an interface into a wider-scoped consumer leaves the field `null` (the subclass-based `RuntimeClientProxyGenerator` cannot subclass an interface, the proxy creation fails and is swallowed). Repro: `vauban-core` `InterfaceClientProxyTest` — a `@RequestScoped @Produces Identity` (interface) injected into an `@ApplicationScoped` consumer; the field must be a non-null proxy that throws `ContextNotActiveException` out-of-scope and resolves the per-request instance in-scope. Red before the fix (field null).

**Symptom (2) — parameterized lookup**: `@Inject Instance<Optional<String>>` / `Provider<Set<String>>` resolves against the raw class (`Optional`, `Set`) instead of the full parameterized type, so it cannot match a synthetic bean registered with the exact `Optional<String>` type; nested generic arguments (`ClaimValue<Set<String>>`) are flattened to `Object`.

**Fix** (`vauban-core`, CDI container only — no public-API change):
- `InterceptorBeanWrapper` — when the proxy target type is an interface, build a `java.lang.reflect.Proxy` (implementing all interface bean types) that lazily delegates each call to the contextual instance of the active scope, instead of failing on the subclass generator.
- `InstanceImpl` + `BeanInjector` — carry the full `java.lang.reflect.Type` (possibly a `ParameterizedType`) as the resolution type for `getBeans()`/`getReference()`, alongside the raw class used for generic bounds (new full constructor).
- `ManagedBean` — reconstruct parameterized bean types **recursively** so nested type arguments (`ClaimValue<Set<String>>`) are preserved.

**Validation**: full `vauban-core` suite green (282 tests with the new `InterfaceClientProxyTest`); end-to-end via the Cervantes MicroProfile JWT 2.1 TCK (206/206). Symptoms (2) are exercised by the TCK's `@Claim` injection matrix.

---

## VAU-INJ-PRIM — primitive injection point exposed to BCE lang model as `ClassType[name=boolean]` instead of `PrimitiveType`

**Date**: 2026-05-26
**Status**: `FIXED` — 2026-05-26 (branch `pr/ybl/jwt-needs`)
**Severity**: medium — breaks any Build Compatible Extension that boxes primitive injection points by detecting `type instanceof PrimitiveType` (the spec-correct pattern, used by Ravel's `ConfigCdiExtension` and Cervantes' `CervantesClaimExtension`). Concrete case: `@Inject @Claim boolean emailVerified` (MicroProfile JWT) — the field was silently left at its default with no deployment error.

**Symptom**: a `@Dependent` consumer with an `@Inject` field of a *primitive* type qualified toward a wrapper-typed synthetic bean is never injected (field stays at the Java default; the wrapper-typed sibling field works). No error surfaces — `BeanInjector` resolves the field via `getBeans(boolean, quals)` (empty, because the synthetic bean got registered under a bogus `ClassType[boolean]`), falls back to `select(boolean)` which looks up `@Default` and throws `UnsatisfiedResolutionException`, which is swallowed.

**Repro**: `vauban-core` `PrimitiveQualifiedInjectionTest.primitiveInjectionPointIsPrimitiveTypeAndBoxes` — a BCE collects `@Tag` injection-point types in `@Registration` and boxes primitives (`instanceof PrimitiveType`) in `@Synthesis`; a primitive `@Tag boolean`/`int` field must be injected.

**Cause**: `BeanInfo.injectionPoints().get(i).type()` is built by `VaubanBceInjectionPointInfo.type()` → `TypeMapper.map(ip.requiredType(), …)`. The indexer encodes a primitive field's `requiredType()` as `TypeInfo.ClassType` whose `name` is a reserved primitive (`"boolean"`, `"int"`, …), not a `TypeInfo.PrimitiveType`. `TypeMapper` forwarded it verbatim as `VaubanClassType[boolean]`, so `instanceof PrimitiveType` was false in the extension → no boxing → synthetic bean registered under the bogus class type → resolution miss.

**Fix**: `TypeMapper.map` now maps a `TypeInfo.ClassType` whose name is a reserved primitive to `VaubanPrimitiveType` (CDI Lite lang model contract). Targeted at the BCE boundary; the indexer's internal `TypeInfo` is untouched. Full `vauban-core` suite green (281 tests); verified end-to-end against Cervantes (`@Claim boolean` now injects).

---

## VAU-BCE-001 — BCE pipeline: `@Registration` sees zero IP, `@Synthesis` loses parameterized types and qualifier members

**Date**: 2026-05-09
**Status**: `FIXED` — 2026-05-09 (branch `fix/vau-bce-001-bce-not-invoked`)
**Severity**: high — blocks any Build Compatible Extension that (a) traverses `BeanInfo.injectionPoints()` in `@Registration`, (b) declares a `SyntheticBean` with a parameterized type (`Optional<T>`, `List<T>`, `Provider<T>`, …), or (c) attaches a qualifier whose members drive resolution. Concrete case: Ravel's `ConfigCdiExtension` (MicroProfile Config 3.1) — blocks M6 (Vidocq ecosystem integration) as long as the BCE is not recognized.

### Symptom

The BCE pipeline is *entered* (the `@Registration` / `@Synthesis` methods are correctly dispatched by `BceProcessor`), but on arrival the deployment fails with:

```
jakarta.enterprise.inject.spi.DeploymentException:
CDI deployment validation failed:
  - Unsatisfied dependency: field <Bean>.<field>
      of type ClassType[name=java.lang.String]
      with qualifiers […, @ConfigProperty(name=app.greeting, defaultValue=Hello)]
```

or, earlier in the pipeline, an `IllegalArgumentException`:

```
@Registration error: Class not found in index: org.eclipse.microprofile.config.inject.ConfigProperty
@Registration error: Class not found in index: java.lang.String
```

or even a silent `NullPointerException` surfaced as `@Synthesis error: null` when the extension builds a `ParameterizedType` via `Types.parameterized(Optional.class, types.of(String.class))`.

### Minimal repro

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

The test covers exactly the API surface used by `ravel-cdi-vauban/.../ConfigCdiExtension`, which passes **349/349** on the MicroProfile Config 3.1 TCK under Weld 6.0.2 — so the Ravel code is spec-compliant; the bug is entirely on the Vauban side.

### Root cause — six cumulative defects on the same path

| # | Site | Problem |
|---|---|---|
| 1 | `VaubanBceBeanInfo.injectionPoints()` | Stub `return List.of();` — the extension receives zero IP, its `@Registration` collects nothing, `@Synthesis` synthesizes nothing. |
| 2 | `VaubanAnnotationInfo` | No override of `name()`. The CDI Lite 4.1 API default (`return declaration().name();`) goes through `lookup.requireClass(<annotation FQN>)` which crashes for annotations outside the index (typical: `@ConfigProperty`, which lives in `microprofile-config-api`). |
| 3 | `VaubanClassType.declaration()` | `lookup.requireClass(name)` throws `IllegalArgumentException("Class not found in index: java.lang.String")` for any JDK or third-party type absent from the scan. The spec contract requires `ClassType.declaration()` to succeed for any type in the model. |
| 4 | `VaubanSyntheticBeanBuilder.type(Type)` | No-op (`return this; // simplified`) — any `addBean(...).type(<lang-model Type>)` is silently lost, and the synthetic bean ends up exposing only `Object`. Systematic hit on `Optional<T>`, `List<T>`, `Provider<T>`, etc. |
| 5 | `VaubanTypes.ofClass(String name)` | Returns `null` if the class is not in the index. Causes an NPE when the extension builds `types.parameterized(Optional.class, types.of(String.class))` → `List.of(null, …)`. |
| 6 | `BceProcessor.toBeanDescriptor` | `qualifiers.add(new QualifierInstance(qName, Map.of()));` — the qualifier members are lost. Consequence: `@Tagged("scalar")` on the synthetic bean side vs `@Tagged("scalar")` on the IP side no longer resolve, because the bean qualifier has `members={}` whereas the IP qualifier has `members={value=StringVal[scalar]}`. The resolver considers them distinct. |

### Fix

Seven files touched in `vauban-core`, all on the BCE / lang-model pipeline:

1. `extensions/VaubanBceBeanInfo.injectionPoints()` — delegates to a new wrapper `VaubanBceInjectionPointInfo` that adapts each `BeanDescriptor.InjectionPointInfo` (internal model) to `jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo` with the type, the qualifiers (members preserved via `VaubanAnnotationInfo`) and the declaration (`FieldInfo` when parsable from the description, otherwise `ClassInfo` fallback).
2. `langmodel/VaubanAnnotationInfo` — override of `name()` returns `indexAnnotation.name().value()` directly without going through `declaration()`.
3. `langmodel/types/VaubanClassType.declaration()` — synthetic fallback (`io.vidocq.vauban.indexer.model.ClassInfo` with just the `name`) when the type is not indexed.
4. `extensions/VaubanSyntheticBeanBuilder.type(Type)` — converts the lang-model `Type` to a `TypeInfo` via the new helper `LangModelTypeMapper`, stored in a new `Set<TypeInfo> indexTypes`. Accessible via `getIndexTypes()`.
5. `extensions/LangModelTypeMapper` (NEW) — inverse of `langmodel.types.TypeMapper`: `jakarta…Type → indexer TypeInfo` (recursive over primitives, classes, parameterized, arrays, wildcards, type-variables).
6. `extensions/VaubanTypes.ofClass(String)` — always returns a `VaubanClassType`, no more `null` even if the type is not in the index (the fallback from #3 takes over).
7. `extensions/BceProcessor.toBeanDescriptor` — merges `synBean.getIndexTypes()` into the type set, and calls a new helper `extractAnnotationMembers(Annotation)` (reflection: each method of the annotation type → corresponding `AnnotationValue`, support for primitives / String / Class / Enum / nested Annotation / arrays).

No public signature modified. No impact on the pre-processed APT path (the `vauban-bce-runtime.list` / `vauban-bce-processed` markers go through other call-sites).

### Regression test

`vauban-core/src/test/java/io/vidocq/vauban/core/extensions/BceRegistrationSynthesisTest.java` (3 tests):

- `registrationExposesInjectionPoints` — verifies that `BeanInfo.injectionPoints()` exposes the 2 IPs with their type AND the `value` member of the `@Tagged` qualifier (covers #1, #2, #6 on the read path).
- `syntheticScalarBeanResolves` — `@Synthesis` registers a `SyntheticBean<String>` with qualifier `@Tagged("scalar")`, the IP `@Inject @Tagged("scalar") String` resolves (covers #6 on the resolution side).
- `syntheticParameterizedBeanResolves` — `@Synthesis` with `type(types.parameterized(Optional.class, types.of(String.class)))`, the IP `Optional<String>` resolves (covers #3, #4, #5).

`vauban-core`: 269 tests, 0 failure (266 baseline + 3 new). `ravel-cdi-vauban`: 21 tests, 0 failure; the test `VaubanContainerIntegrationTest.resolves_config_property_injection_through_bce_pipeline` (until now `@Disabled` for residual M6) now passes locally — it can be re-enabled on the Ravel side once a Vauban snapshot containing this fix is published.

### Follow-up

- `VaubanBceInterceptorInfo.injectionPoints()` remains a stub — `InterceptorDescriptor` does not track IPs yet; not critical for M6, to be opened separately (VAU-BCE-002 if needed).
- The other call sites of `new QualifierInstance(qName, Map.of())` (BceProcessor lines 1010, 1071, 1141; VaubanContainerBuilder line 1172) follow the same member-loss pattern; to be investigated if a non-synthetic case requires it.

---

## VAU-MVN-001 — `VaubanGenerator` propagates `NoClassDefFoundError` instead of skipping the class

**Date**: 2026-05-09
**Status**: `FIXED` — 2026-05-09 (branch `fix/vau-mvn-001-classpath-noclassdef`)
**Severity**: medium — blocks any build that consumes an artifact whose "optional" transitive dependency is missing from the classpath provided to the plugin (concrete case: `microprofile-config-api:3.1.1`, which references `jakarta.activation`).

### Symptom

During the `process-classes` phase of `vauban-maven-plugin`, the JVM raises an uncaught linkage error and the build fails:

```
java.lang.NoClassDefFoundError: jakarta/activation/DataSource
    at java.base/java.lang.Class.getDeclaredFields0(Native Method)
    at io.vidocq.vauban.maven.generate.VaubanGenerator.loadArchiveClasses(VaubanGenerator.java:352)
    at io.vidocq.vauban.maven.generate.VaubanGenerator.generate(VaubanGenerator.java:141)
    at io.vidocq.vauban.maven.generate.GenerateMojo.execute(GenerateMojo.java:62)
```

No `META-INF/vauban-beans.list` is produced for the affected module, and all subsequent Maven-dependent modules fail in cascade.

### Minimal repro

A Maven project that:
1. declares `org.eclipse.microprofile.config:microprofile-config-api:3.1.1` in `compile`,
2. runs `vauban:generate` (`process-classes` phase).

The scenario is exactly that of `ravel-cdi-vauban` packaged via Vauban (see `ravel/CLAUDE.md`).

### Root cause

Two call sites of `Class.forName(name, false, cl)` in `VaubanGenerator`:

| Line | Context | Original catch |
|---|---|---|
| ~211 | proxy / interceptor generation (in `generate`) | `ClassNotFoundException` |
| ~352 | `loadArchiveClasses` (reflective loading of indexed classes) | `ClassNotFoundException` |

`Class.forName(name, false, cl)` does not trigger static init but does trigger **linkage**: the JVM must resolve the superclass, the interfaces, and the field/method types. Any referenced class missing from the classloader passed as a parameter throws `NoClassDefFoundError` (a subclass of `LinkageError`, hence *not* a `ClassNotFoundException`).

The plugin builds its classloader via `GenerateMojo.buildClassLoader(...)` from `project.getArtifacts()`. Dependencies marked `optional=true` on the scanned artifact (typically `microprofile-config-api` → `jakarta.activation`) do not propagate up to the plugin classpath → the first call site that touches a field/method of the imported class blows up with `NoClassDefFoundError`.

### Fix

`vauban-maven-plugin/src/main/java/io/vidocq/vauban/maven/generate/VaubanGenerator.java`, **both** call sites widen their catch to `NoClassDefFoundError`:

- `loadArchiveClasses` (~352): silently ignored (the class will not be loaded for the rest of the BCE / proxy / interceptor pipeline, but remains available on the bytecode indexer side).
- proxy generation (~211): adds the existing warning ("Cannot load class for generation: <fqn>").

No semantic change with respect to the normal contract: we simply replace a hard crash with a graceful degradation aligned with the behavior already in place for `ClassNotFoundException`.

### How to avoid the regression

`VaubanGeneratorTest` still passes (9/9). To add as follow-up: `shouldSkipClassWithMissingTransitive`, which generates via Class-File API a class annotated `@ApplicationScoped` extending a non-existent `com.missing.Parent`, embeds it in a scanned JAR, and verifies that `generate()` returns without throwing.

### Possible widening

`Class.forName` can also throw other `LinkageError`s (`ClassFormatError`, `IncompatibleClassChangeError`, `UnsupportedClassVersionError`, `VerifyError`). If we observe one of these cases in production, widen the catch to `LinkageError` (common parent). For now the fix stays targeted at the observed symptom.

---

## VAU-PRX-002 — Drift between `ClientProxyGenerator` (compile-time) and `RuntimeClientProxyGenerator` (runtime)

**Date**: 2026-05-07
**Status**: `FIXED` — 2026-05-07
**Severity**: high (makes any `@ApplicationScoped` bean injected via `cassini-cdi-vauban` unusable on the JAX-RS side when the `_ClientProxy.class` is pre-generated by APT).

### Symptom

`InterceptorBeanWrapper.getOrCreateProxy` throws a `DeploymentException("Failed to create client proxy for normal-scoped bean ...")`. Cassini then falls back to `cls.getDeclaredConstructor().newInstance()`, which returns a raw, non-injected instance → NPE on invocation (`this.dataSourceInstance is null`, `this.products is null`, etc.).

```
Caused by: java.lang.NoSuchMethodException: …DatabaseInspectorResource_ClientProxy.<init>()
    at java.lang.Class.getDeclaredConstructor(Class.java:2491)
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.lambda$getOrCreateProxy$0(InterceptorBeanWrapper.java:235)
```

### Root cause

Two client proxy generators coexisted with incompatible contracts:

- `vauban-processor/.../ClientProxyGenerator.java` (compile-time, APT) emitted: final `delegate` field + `(Supplier)` ctor + putfield in the constructor.
- `vauban-core/.../RuntimeClientProxyGenerator.java` (runtime fallback) emits: non-final `$$delegate` field + no-arg ctor + `$$setDelegate(Supplier)` setter.

`InterceptorBeanWrapper.getOrCreateProxy` (lines 235-249) expects exclusively the second format. `loadOrDefineClassRobustly` loads the APT-pre-generated `_ClientProxy.class` in priority, so the old format always shadows the runtime one.

### Why `vidocq-runtime-cassini-rest-example` worked anyway

Its `target/classes/.../TodoResource_ClientProxy.class` came from an earlier compilation done with a version of `ClientProxyGenerator` that already produced the modern format — it simply had not been regenerated since the drift.

### Fix

`vauban/vauban-processor/src/main/java/io/vidocq/vauban/processor/codegen/proxy/ClientProxyGenerator.java`: aligned with the `RuntimeClientProxyGenerator` format:
- `$$delegate` field (non-final).
- Public no-arg constructor calling `super()`.
- `$$setDelegate(Supplier)` method.
- All `getfield "delegate"` → `getfield "$$delegate"`.

`ClientProxyGeneratorTest` tests updated to use the new contract (no-arg ctor + setter).

### How to avoid the regression

`ClientProxyGeneratorTest.shouldDelegateMethodCalls` / `shouldDelegateVoidMethods` now invoke the proxy via `getDeclaredConstructor().newInstance()` then `getMethod("$$setDelegate", Supplier.class)` — exactly the code that `InterceptorBeanWrapper.getOrCreateProxy` does. Any future unilateral drift of either generator immediately breaks these tests.

---

## VAU-INJ-001 — Field injection eagerly resolves normal-scope beans (loses the client proxy)

**Date**: 2026-05-07
**Status**: `FIXED` — 2026-05-07
**Severity**: high (makes `@TransactionScoped` / `@RequestScoped` unusable as a direct `@Inject` field).

### Symptom

When an `@ApplicationScoped` bean (or another managed bean) declares an `@Inject T`
where `T` is a **normal-scope** bean (`@TransactionScoped`, `@RequestScoped`, …),
field injection tries to resolve the context at boot/instance-creation time —
hence *out-of-scope* — and throws a
`ContextNotActiveException`. The field stays `null`; any subsequent invocation
causes an NPE.

```
INJECTION FAILED FOR audit ON class …ProductResource$$Intercepted :
  @TransactionScoped accessed outside an active transaction
jakarta.enterprise.context.ContextNotActiveException
    at io.vidocq.mansart.transactions.cdi.TransactionScopedContext.requireActiveTx
    at io.vidocq.mansart.transactions.cdi.TransactionScopedContext.get
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.lambda$getOrCreateProxy$0
    at io.vidocq.vauban.core.container.InterceptorBeanWrapper.getOrCreateProxy
    at io.vidocq.vauban.core.container.VaubanContainer.getOrCreateProxyForBean
    at io.vidocq.vauban.core.container.VaubanBeanManager.getReference        ← here
    at io.vidocq.vauban.core.container.BeanInjector.lambda$injectFieldsByReflection$0  ← calls getReference at boot
```

### Minimal repro

The `vidocq-runtime-mansart-h2-example` project reproduces this on the module-path:

```java
@ApplicationScoped
@Path("/products")
public class ProductResource {
    @Inject OperationAudit audit;        // @TransactionScoped → null after injection

    @POST @Transactional
    public Response create(Product input) {
        audit.record("…");                // NPE this.audit is null
    }
}

@TransactionScoped
public class OperationAudit implements Serializable { … }
```

### Suspected cause

`BeanInjector.injectFieldsByReflection` (vauban-core, line ~90):

```java
value = bm.getReference(resolved, fieldType, ctx);
```

For a normal-scope bean, `getReference` must return a **lazy client
proxy**: a wrapper object that resolves the context *on each method
invocation* (not at creation time). Today `getReference` →
`getOrCreateProxy` → `ApplicationContext.get` → `ManagedBean.create` → which
tries to create the instance immediately. Out-of-scope, the `OperationAudit`
cannot be created and the returned proxy is null.

The `OperationAudit_ClientProxy` client proxy does in fact exist (generated by
`ClientProxyGenerator`), with a `(Supplier<OperationAudit>)` constructor —
that is exactly the correct pattern. But `BeanInjector` does not instantiate
it; it calls `getReference`, which resolves the instance, losing the
lazy-resolution offered by the proxy.

### Documented workaround

Inject via `Provider<T>` or `Instance<T>`: `BeanInjector` has a specific
branch (lines 38-53) for these types that correctly creates a lazy
wrapper.

```java
@Inject Provider<OperationAudit> audit;   // OK
…
audit.get().record("…");                  // resolves in the active scope
```

### Applied fix (2026-05-07)

**Actual root cause**: `VaubanContainer.getContextualInstance` called
`context.get(contextual)` (without `CreationalContext`, i.e. "look up existing")
BEFORE checking `isNormal()`. For a scope inactive at boot
(`@TransactionScoped` outside a TX, `@RequestScoped` outside a request), this `context.get()`
calls `checkActive()` → `ContextNotActiveException`. This exception was
swallowed by the `BeanInjector` catch, leaving the field at `null`.

Second vector: the catch-all in `InterceptorBeanWrapper.getOrCreateProxy`
degraded to an eager `ctx.get()` for any scope when an exception occurred
during proxy creation.

**Fix 1 — `VaubanContainer.getContextualInstance`**: move the
`isNormal()` check to the first statement, before any call to `context.get()`.
The proxy is returned immediately without ever touching the context.

**Fix 2 — `InterceptorBeanWrapper.getOrCreateProxy` catch block**: for
`isNormal()` beans, throw `DeploymentException` instead of trying `ctx.get()`
eagerly.

**TDD test added**: `NormalScopeFieldInjectionTest` (4 cases) in
`vauban-core/src/test/java/io/vidocq/vauban/core/container/`.

262 vauban-core tests — 0 failures, 0 errors after the fix.

**TCK regression discovered and fixed (2026-05-07)**: Fix 2 caused 7 CDI TCK failures
(`EventTypesTest`, `MemberLevelInheritanceTest`, `InvokerAssignabilityTest`,
`VarargsMethodInvokerTest`). Actual cause: `RuntimeClientProxyGenerator.generateProxyMethod`
crashed for methods having array-type parameters (`Song[]`, `int[]`,
`String...` varargs) because `Class.describeConstable()` returns `Optional.empty()` for these
types and the fallback `ClassDesc.of(type.getName())` used the JVM descriptor format
(e.g. `"[Lorg...Song;"`) that `ClassDesc.of()` rejects. The `DeploymentException` from Fix 2
exposed this generation error that was previously silently ignored.

**Fix 3 — `RuntimeClientProxyGenerator.classDescOf(Class<?>)`**: helper that uses
`ClassDesc.ofDescriptor(type.descriptorString())` as a fallback — a format accepted for
all types (arrays, primitives, references). All
`describeConstable().orElse(ClassDesc.of(...))` calls replaced by `classDescOf()`.

CDI TCK **774/774 PASS** — 0 failures after Fix 3.

---

## VAU-TYP-001 — three private ParameterizedType/GenericArrayType copies with divergent equals/hashCode

- **Date**: 2026-06-10 — **Status**: FIXED (uncommitted — Phase 16a modernization batch)

**Symptom**: found by code review during the Java-modernization pass, no failing test
observed yet. `vauban-core` carried **three** independent synthetic implementations of
`java.lang.reflect.ParameterizedType` — `ManagedBean.ResolvedParameterizedType`,
`BeanDiscovery.ResolvedParamType` and an anonymous class in
`TypeHierarchyResolver.substitute` (plus a second anonymous one in
`ManagedBean.substituteTypeVariables`) — each with a **different** `equals`/`hashCode`
contract:

| Copy | equals checks owner? | hashCode includes owner? |
|---|---|---|
| `ManagedBean.ResolvedParameterizedType` | yes | **no** |
| `BeanDiscovery.ResolvedParamType` | yes | yes |
| `TypeHierarchyResolver` anonymous | **no** | no |
| JDK `ParameterizedTypeImpl` (reference) | yes | yes |

**Risk**: bean types live in hash-based sets (`LinkedHashSet<Type>`) mixing JDK-built
types and Vauban-built ones. Two equal types with different hash codes silently land in
different buckets → duplicate bean types, missed assignability matches. Only observable
when `ownerType != null` (nested generic classes) or when the laxer
`TypeHierarchyResolver` equals deduplicated types it should not.

Same family: `ManagedBean.ResolvedGenericArrayType` (record) kept the record-generated
`equals`, which only matches its own record type — `jdkGat.equals(resolvedGat)` was true
while `resolvedGat.equals(jdkGat)` was false (asymmetric), and the anonymous
`GenericArrayType` in `substituteTypeVariables` had **identity** equals/hashCode.

**Fix (2026-06-10)**:
- `BeanDiscovery.resolveReflectType` now delegates to `ManagedBean.resolveType` /
  `buildTypeVariableMapping`; the private copies (`resolveReflectTypeWithMapping`,
  `ResolvedParamType`, `ResolvedGenArrayType`, ~80 lines) are deleted.
- `ManagedBean.ResolvedParameterizedType.hashCode` aligned on the JDK formula
  (`Arrays.hashCode(args) ^ Objects.hashCode(owner) ^ Objects.hashCode(raw)`).
- `ManagedBean.ResolvedGenericArrayType` given explicit `equals`/`hashCode` matching
  `GenericArrayTypeImpl` semantics (any `GenericArrayType` with equal component).
- The two anonymous classes replaced by the records
  (`ManagedBean.substituteTypeVariables` → `ResolvedParameterizedType` /
  `ResolvedGenericArrayType`; `TypeHierarchyResolver.substitute` → new private
  `SubstitutedParameterizedType` record with JDK-aligned contract).
- Bonus dedup: the three near-identical `collectTypesFromSupers` /
  `collectTypesWithMapping` / `collectTypesWithMappingSkipSelf` walkers merged into a
  single `collectSupertypes(..., boolean addSelf)`.

**Validation**: full reactor `clean install` green + CDI 4.1 Lite TCK **774/774 PASS**.

## BUG-20260712-01 — vauban-api ships a hardcoded VERSION constant

- **Date** : 2026-07-12
- **Statut** : FIXED (branch fix/build-derived-version — ships with the next release)
- **Module touché** : vauban-api / Vauban.java
- **Symptôme** : the artifact published on Maven Central as 0.2.0 reports
  `Vauban.VERSION = "0.1.0-SNAPSHOT"` — the constant is maintained by hand and was
  never updated by the release train. Same class as vidocq BUG-20260704-01 (CLI banner).
- **Reproduction minimale** :
  ```
  jshell --class-path vauban-api-0.2.0.jar -q \
    -s <(echo 'System.out.println(io.vidocq.vauban.api.Vauban.VERSION)')
  ```
- **Hypothèse de cause** : compile-time constant, no build filtering.
- **Investigations** :
  - 2026-07-12 : found by grepping for stale version strings after the issue #3
    follow-up. Fixed: `version.properties` filtered by Maven next to the class,
    constant loaded at class init (same-module JPMS resource, no opens needed).
    No runtime consumers existed; the constant is no longer compile-time-inlineable,
    which also protects future consumers from the javac inlining trap.

## VAU-BCE-004 — Synthetic bean types given as runtime array classes never resolve

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (SyntheticBeanConverter, AssignabilityRules, ManagedBean, VaubanContainer, VaubanBeanManager)
- **Symptôme** : a BCE registering `addBean(Boolean[].class).type(Boolean[].class)`
  (MP Config's ConfigCdiExtension does, for `@ConfigProperty` array injection points)
  yields "Unsatisfied dependency" for every `ArrayType` injection point: the bean type
  was recorded as a flat `ClassType(DotName("[Ljava.lang.Boolean;"))`.
- **Reproduction minimale** : `SyntheticArrayBeanTypeTest` (boxed, primitive, `Class<?>[]`).
- **Hypothèse de cause** : `SyntheticBeanConverter.toBeanDescriptor` mapped every runtime
  `Class` to `ClassType(cls.getName())`; array matching, runtime bean-type resolution
  (`resolveArrayClass` on primitive-named components), `select(Class)` and
  `typesMatch(GenericArrayType)` each had the same blind spot.
- **Investigations** :
  - 2026-07-13 : found by migrating the MP Config TCK runner to the assembled Vidocq
    runtime (ravel BUG-20260713-01 family 1). Fixed end to end; array element matching
    now follows CDI 4.1 §5.2.4 (identical element types, no boxing, raw ↔ unbounded
    parameterized equivalence).

## VAU-LKP-001 — Programmatic lookup drops qualifiers and hides the injection point

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (VaubanCDI, InstanceImpl)
- **Symptôme** : `CDI.current().select(type, qualifiers...)` ignored the given
  qualifiers (resolved `@Default` or failed `UnsatisfiedResolution`); synthetic bean
  creators invoked through a programmatic lookup saw an EMPTY `InjectionPoint`
  (type `Object`) instead of the selected type/qualifiers, breaking MP Config's
  `@ConfigProperties` programmatic lookups (`ClassCastException: Object`).
- **Reproduction minimale** : `VaubanCDISelectQualifierTest`.
- **Investigations** :
  - 2026-07-13 : ravel BUG-20260713-01 family 2 (programmatic side). select now
    forwards qualifiers (CDI 4.1 §11.1) and `Instance.get()` without an underlying
    injection point installs a synthetic `InjectionPoint` carrying the selected
    type and qualifiers; an enclosing injection point stays visible to a creator's
    internal lookups.

## VAU-DSC-002 — Build-time composite discovery loader hides source-loader resources

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (VaubanContainerBuilder.buildCompositeClassLoader)
- **Symptôme** : during the build (BCE phases included) the TCCL is a composite
  loader that only delegated classes and `getResourceAsStream`; `getResources`
  (plural) fell through to the parent, so a deployment's
  `META-INF/microprofile-config.properties` and ServiceLoader `ConfigSource`s were
  invisible to MP Config during deployment validation. An embedded deployment
  contributing no class of its own (parent-first loader over classes that also
  exist on the application classpath) lost its resources entirely.
- **Reproduction minimale** : `CompositeLoaderResourceTest` (multi-loader + entry-TCCL).
- **Investigations** :
  - 2026-07-13 : ravel BUG-20260713-01 family 3. `findResource`/`findResources` now
    aggregate every source loader, and the caller-installed context loader joins the
    composite.

## VAU-OBS-002 — Observer method non-event parameters are not injection points

- **Date** : 2026-07-13
- **Statut** : FIXED (2026-07-13, branch pr/ybl/synthetic-array-bean-types)
- **Module touché** : vauban-core (BeanDiscovery)
- **Symptôme** : the non-event parameters of observer methods (CDI 4.1 §10.4.3) were
  absent from the bean descriptor: deployment validation skipped any parameter with a
  custom qualifier (ad-hoc check) and BCEs never saw them through
  `BeanInfo.injectionPoints()` — MP Config could neither validate
  `@ConfigProperty` observer parameters nor synthesize the beans satisfying them
  (TCK `MissingValueOnObserverMethodInjectionTest`).
- **Reproduction minimale** : `ObserverParamValidationTest`.
- **Investigations** :
  - 2026-07-13 : ravel BUG-20260713-01 family 4 (observer side). Observer non-event
    parameters are now collected as `METHOD_PARAMETER` injection points
    (EventMetadata excluded); the typed-resolution `AmbiguousResolutionException`
    message now lists the matching beans.

## VAU-BCE-005 — @Vetoed added at @Enhancement ignored for index-discovered beans

- **Date** : 2026-07-14
- **Statut** : FIXED (2026-07-14)
- **Module touché** : vauban-core (EnhancementApplier)
- **Symptôme** : a BCE adding `@Vetoed` to a bean class in its `@Enhancement` phase
  (the CDI Lite idiom for replacing a discovered managed bean with a synthetic one)
  only takes effect when the class goes through the pre-discovery archive pass. For a
  bean already discovered — the normal case for pre-indexed application classes on the
  module path — `EnhancementApplier.applyEnhancements` applied qualifier/scope changes
  but silently ignored the added `@Vetoed`, so the managed bean stayed registered and
  every injection point of its interface became `AmbiguousResolution` once the
  replacing synthetic bean was added.
- **Reproduction minimale** : mansart MANSART-005 — `AuditEntryRepositoryImpl`
  (managed `@Singleton`, vetoed by `MansartDataExtension`) + the routing synthetic
  bean; boot `vidocq-runtime-mansart-h2-example` on the module path.
- **Hypothèse de cause** : `applyEnhancements` only rewrote descriptors; it never
  dropped one. Bean discovery must see enhancement-modified annotations, so an added
  `@Vetoed` has to exclude the class exactly like a source-level one.
- **Investigations** :
  - 2026-07-14 : fixed — `applyEnhancements` drops any descriptor whose enhancement
    configs add `@Vetoed` (class-level, `getAddedAnnotations` + `getAddedAnnotationInfos`).
    Regression test `BceVetoedEnhancementTest`; full reactor green; CDI Lite TCK re-run
    774/774.

## VAU-CTX-001 — BCE-registered custom contexts silently dropped on the module path

- **Date** : 2026-07-14
- **Statut** : FIXED (2026-07-14)
- **Module touché** : vauban-core (VaubanContainerBuilder, InterceptorBeanWrapper)
- **Symptôme** : a context registered through `MetaAnnotations.addContext(scope, isNormal,
  contextClass)` was never installed when the app runs on the strict module path: the
  builder instantiated the context class via `privateLookupIn` (needs `opens`), caught the
  failure and **silently skipped** the context. Every normal-scoped bean of that scope then
  hit the client-proxy delegate's **silent `@Dependent` fallback** — a fresh instance per
  proxy call, so state written through the proxy vanished on the next call. Seen as
  mansart's `@TransactionScoped` bean always being empty in
  `vidocq-runtime-mansart-h2-example` ("TX audit: []").
- **Reproduction minimale** : `TxModulePathTest.transaction_scoped_context_works_on_the_module_path`
  (mansart-transactions-cdi-jpms-it) — red before the fix (`expected: <2> but was: <0>`).
- **Hypothèse de cause** : two compounding silent fallbacks. (1) The custom-context
  registration only knew the provider path and the private-lookup path; a public context
  class in an exported package (the normal case, and what the BCE API implies) needs
  neither. (2) The proxy delegate treated "no context registered for this scope" as
  "@Dependent", violating CDI 4.1 §6.5.1.
- **Investigations** :
  - 2026-07-14 : fixed — context instantiation now tries the module's
    `VaubanComponentProvider`, then the PUBLIC no-arg constructor via
    `MethodHandles.publicLookup()` (no opens needed for an exported package), then the
    private lookup; an uninstallable declared context is now a `DeploymentException`
    instead of a silent skip. The proxy delegate throws `ContextNotActiveException` when
    no context is registered for a normal scope instead of handing out per-call
    `@Dependent` instances. CDI Lite TCK re-run 774/774.
