# Vauban — Reproducible bugs

Internal tracker. Minimalist format: short id, date, symptom, repro,
suspected cause, status. Updated on every investigation.

---

## VAU-PROXY-001 — client proxy replays the bean constructor (side effects doubled, NPE on `@Inject` ctor)
- **Date**: 2026-08-10 — **Status**: FIXED, phases 0+1+2 (`pr/ybl/vauban-24-proxy-link`) — phase 2 weaves the marker automatically at `process-classes` (no app-code change required)
- **Severity**: high (constructor injection unusable on normal-scoped beans; silent double side effects)
- **Surfaced by**: Sébastien Blanc, Vidocq/vauban#24, while building Rossignol on Vidocq.

### Symptom
Creating the `_ClientProxy` of a normal-scoped bean chains `super(<defaults>)` into a business
constructor: construction side effects run once per proxy on top of the contextual instance
(reporter's case, 2 constructions for 1 instance), and a bean whose only constructor is an
`@Inject` one that dereferences a parameter throws `NullPointerException` at proxy creation
(the proxy passes null for every parameter).

### Repro
`ProxyLinkConstructorTest` (vauban-core) and `UnproxyableBeanValidationTest` (vauban-processor).

### Cause
Two independent defects. (1) The JVM forces the proxy's `<init>` to chain to a bean
constructor, and every generator picked the "simplest" business constructor with default
arguments. (2) Such beans are *unproxyable* per CDI 4.1 and should have been reported as a
deployment problem — but `DeploymentValidator`'s proxyability checks used `Class.forName`
over the TCCL with a silent `catch (ClassNotFoundException)`: inert at annotation-processing
time and on the module path (the Vidocq runtime deployment), so nothing ever fired.

### Fix
Phase 0: proxyability checks (final class, non-private final methods, missing no-arg ctor)
are now index-based with reflection as fallback — final members fail the build; the
missing-constructor case is a compile-time warning (weavable) and a container-start error.
Phase 1: opt-in `ProxyLink` marker constructor (`vauban-api`); all three proxy front-ends
chain to it when declared, and the container never selects it for injection. Phase 2
(the actual no-app-change fix): `vauban-maven-plugin` weaves a synthetic `(ProxyLink)`
constructor into the compiled bean at `process-classes` (`ProxyLinkWeaver` +
`ProxyLinkWeaving`) and retargets APT-emitted proxies onto it — the application source
never mentions `ProxyLink`. Full study: `tasks/vauban-24-client-proxy-constructor.md`.
Verified: reactor 526 tests + CDI Lite TCK 774/774 green, weaving exercised for real by
the examples modules during the reactor build.

---

## VAU-APT-001 — `@Inject` of a bean from another module rejected at compile time
- **Date**: 2026-08-09 — **Status**: FIXED (`pr/ybl/vauban-23-cross-module-index`)
- **Severity**: high (viral at every module boundary; no workaround for third-party beans)
- **Surfaced by**: Sébastien Blanc, Vidocq/vauban#23, while building Rossignol on Vidocq.

### Symptom
```
[ERROR] [Vauban] Unsatisfied dependency: field HelloResource.greeter of type
        ClassType[name=io.repro.core.Greeter] with qualifiers [... Any ..., ... Default ...]
```
`Greeter` is an `@ApplicationScoped` bean of a **different** Maven / Java module. The same
injection point wrapped in `Instance<Greeter>` compiled and resolved correctly at runtime,
against the same bean and the same module graph — so the build-time rejection was a false
negative, not a real deployment error.

### Repro
Two modules: `core` exports an `@ApplicationScoped Greeter`; `app` requires `core` and injects it
directly. `mvn install` fails. Reproduced as `CrossModuleInjectionTest` in `vauban-processor`.

### Cause
`VaubanProcessor` built its index solely from the types of the current compilation round
(`accumulatedIndex`), while the dependency scan lived only in `vauban-maven-plugin`
(`VaubanGenerator`, which reads each dependency's `META-INF/vauban-beans.list`). The validator
therefore judged the deployment on a partial index. The reporter's two checks confirmed the
runtime index was fine: `Instance<T>` worked end to end, and adding a local `@Produces` bridge
produced *Ambiguous dependency* — an ambiguity impossible to report without knowing the other
module's bean.

### Fix
No jar scanning was needed: `Elements` already sees the whole compile classpath. A required type
that is missing from the index is now looked up there, scanned in, and bean discovery is replayed
to obtain its descriptor (`resolveDependencyBeans`). Such a type goes into `externalClassNames`,
so no `_Factory` / `_ClientProxy` is emitted for it here — its own module ships those, and
emitting them again would split the package.

What still cannot be resolved is classified rather than rejected: an **interface or abstract
class from outside the Java platform** may be implemented by a bean this compilation cannot see,
so it is reported as a warning and deferred to the container (which re-validates on start). A
concrete type that was indexed and did not become a bean carries no scope, and a platform type is
nobody's bean — both keep failing the build. The in-module check therefore stays strict.

### Impact on the reported workarounds
`@Inject Instance<JwtValidator>` (cervantes) is no longer necessary, and neither is the
"library modules carry no CDI beans" restructuring the reporter had to adopt.

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
- **Surfaced by**: the new `mansart-transactions-cdi-module-it` module-path vehicle — `TxService.required(Block)`
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
- **Surfaced by**: `mansart-transactions-cdi-module-it` — `container.select(TxService.class)` on the module
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

Proven by `mansart-transactions-cdi-module-it` with **zero opens** (REQUIRED on the base interceptor and
REQUIRES_NEW on an empty subclass with an inherited `@Inject` field). Non-regression: dirac Metrics 5.1
127/127, heisenberg FT 4.1 463/463, vauban full suite green. The plugin (`VaubanGenerator`) is unchanged —
all first-party interceptor wrappers use the APT; mirroring it for external jars is a follow-up.

---

## VAU-INT-005 — APT forces `@InterceptorBinding` completion, breaking module-path interception of marker bindings
- **Date**: 2026-06-07 — **Status**: FIXED (name-based meta detection, no symbol completion)
- **Severity**: medium (a wrapper whose interceptor binding is a `@InterceptorBinding`-meta marker —
  e.g. heisenberg's `@FaultToleranceBinding` stamped on beans by a BCE `@Enhancement` — cannot have its
  targets' `$$Intercepted` generated by the APT for a downstream module on a strict module path)
- **Surfaced by**: `heisenberg-cdi-vauban-module-it` — the `@Retry` fixture's `default-compile` fails with
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

Proven by `heisenberg-cdi-vauban-module-it` (`@Retry` intercepted 4× on the module path, **zero opens**,
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
    constant loaded at class init (same-module Java Modules resource, no opens needed).
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

## BUG-20260824-01 — BCE on the compile path but absent from the processor path is silently ignored

- **Date** : 2026-08-24
- **Statut** : FIXED 726dc38
- **Module touché** : `vauban-processor` (`VaubanProcessor.init()` / `discoverBceClasses`)
- **Symptôme** : in a standalone Vauban + Mansart app, declaring Maven
  `<annotationProcessorPaths>` (e.g. for `mansart-data-processor`) narrows javac's
  `-processorpath` to those entries only. `VaubanProcessor` discovers
  `BuildCompatibleExtension` implementations via
  `ServiceLoader.load(BuildCompatibleExtension.class, VaubanProcessor.class.getClassLoader())`
  (`VaubanProcessor.java:146-147`, `:647-661`), i.e. on the processor path — so a BCE
  living in a regular dependency jar (`mansart-data-cdi`'s `MansartDataExtension`) never
  runs, its `ScannedClasses.add(...)` contributions are missing, and the user gets a
  confusing hard error much later:
  `[Vauban] Unsatisfied dependency: parameter 0 of <Repo>Impl() of type ClassType[name=io.vidocq.mansart.data.core.RepositoryRuntime] ...`
  (concrete final type → `mayBeSatisfiedElsewhere()` returns false → not deferred).
  Reported by an external user following the Mansart README/getting-started.
- **Reproduction minimale** :
  ```
  App with mansart-data-{core,cdi,dialect-postgresql} as dependencies, one @Repository
  interface + entity, and:
    <annotationProcessorPaths>
      <path>io.vidocq.mansart:mansart-data-processor</path>
    </annotationProcessorPaths>
  mvn compile → [Vauban] Unsatisfied dependency ... RepositoryRuntime
  Workaround/fix: add io.vidocq.mansart:mansart-data-cdi as an extra <path> (reference:
  mansart-transactions/mansart-transactions-cdi-module-it/pom.xml:88-110, plus
  -Avauban.validation=false for cross-module bean resolution the APT cannot see).
  ```
- **Hypothèse de cause** : not a resolution bug — a DX gap. The APT cannot load BCEs from
  the compile/module path (javac gives it no classloader over it), but it *can* detect the
  mismatch: dependency jars are visible as `-classpath`/`--module-path` entries and a scan
  for `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`
  (or `provides` in `module-info.class`) would identify BCEs its own ServiceLoader cannot
  see. Proposed fix: emit a `Diagnostic.Kind.WARNING` (and enrich the unsatisfied-dependency
  error) naming the jar and suggesting the `<annotationProcessorPaths>` addition.
- **Investigations** :
  - 2026-08-24 : fix implemented (warning + error hint; detection via module `provides`
    directives and the classpath services resource). Javac trap found on the way: reading
    the directives of a module STILL BEING COMPILED completes it prematurely and freezes
    `provides` resolution errors before last-round generated classes exist (broke
    vauban-module-it) — the scan now skips the modules owning this compilation's root
    elements. Covered by BceProcessorPathDiagnosticTest (3 tests); full reactor green.
  - 2026-08-24 : tracked publicly as codefloe issue vauban#29 (companion: mansart#9,
    the original support case + doc fixes).
  - 2026-08-24 : root cause traced end to end (processor-path ServiceLoader at
    `VaubanProcessor.java:650`; `GenerateMojo` runs BCEs later but `processEnhancementOnly`,
    no `@Discovery`/validation, so it cannot compensate). Docs fixed on the Mansart side
    (getting-started now wires `mansart-data-cdi` on the processor path); this entry tracks
    the diagnostic improvement in the APT itself.

---

## VAU-INT-006 — `@AroundInvoke` interceptors fire on `@PostConstruct` / `@PreDestroy` lifecycle callbacks
- **Date**: 2026-09-11 — **Status**: FIXED (vauban#114, branch `pr/ybl/lifecycle-callbacks-not-business`)
- **Severity**: medium (spec violation; concrete effect: a class-level `@Transactional` — or any
  business-method interceptor such as `@Retry`, `@Timed`, `@Logged` — wraps the bean's lifecycle
  callbacks, which neither Weld nor ArC do; a transaction is opened around `@PostConstruct` and
  `@PreDestroy`, and metrics/logging see phantom "business" invocations named `init`/`dispose`)
- **Surfaced by**: a request-scope probe on `vauban-module-it` (APT path, module path, zero opens)
  while checking that normal scopes compose with `$$Intercepted`. Scope behaviour itself is correct
  (one `$$Intercepted` per request, `ContextNotActiveException` outside a request, callbacks on the
  right instance). No existing test covers the `@AroundInvoke` × lifecycle-callback boundary.

### Symptom
For a `@RequestScoped @Audited` bean with package-private `@PostConstruct void init()` and
`@PreDestroy void dispose()`, the `@Audited` interceptor's `@AroundInvoke` records:
```
CALLS = [init/0, id/0, id/0, thisClass/0, outer/0, inner/0, viaCollaborator/0, dispose/0]
```
`init/0` and `dispose/0` must not be there. Jakarta Interceptors 2.2 §2.2/§5: `@AroundInvoke` applies
to **business method** invocations only; lifecycle callbacks are intercepted solely by the interceptor's
own `@PostConstruct`/`@PreDestroy` methods, and `InvocationContext.getMethod()` is `null` for them. Here
`getMethod()` returned the callback `Method` and the chain ran as a business invocation.

### Repro
```java
@RequestScoped @Audited
public class RequestAuditedService {
    @PostConstruct void init() {}
    @PreDestroy  void dispose() {}
    public int id() { return 1; }
}
// boot VaubanContainer with AuditInterceptor (@AroundInvoke records ctx.getMethod().getName())
container.requestContext().runInScope(() -> service.id());
// observed: CALLS contains "init" and "dispose"; expected: only "id"
```
Same outcome on the APT path (`vauban-module-it`, source-rendered `$$Intercepted`) and, by
construction, on the runtime/weaver fallback — both share the same override filter.

### Cause
Both override filters exclude `@Inject` initializer methods and target-class `@AroundInvoke` methods,
but **not lifecycle callbacks**:
- APT: `InterceptedShapeFromElements.shouldIntercept` (vauban-processor) — checks `INJECT`, `AROUND_INVOKE`.
- Runtime: `InterceptorSubclassGenerator.shouldIntercept` (vauban-core) — same two checks.

So a non-private, non-final `@PostConstruct`/`@PreDestroy` method is overridden in `<Bean>$$Intercepted`
like any business method (confirmed in the generated source: `@Override public void init()` /
`dispose()` build a `VaubanInvocationContext` and call `proceed()`). `BeanLifecycle` then invokes the
callback by **virtual dispatch on the `$$Intercepted` instance** (`vaubanLookup.invokeMethod(instance,
pcMethod)`, with `pcMethod` resolved on the superclass) → the override runs → the `@AroundInvoke`
chain fires. The dedicated lifecycle chain (`resolveLifecycleChain(..., PostConstruct.class, ...)`)
is built correctly on top of that — the defect is only the extra business-chain wrap.

### Proposed fix
Exclude every Jakarta lifecycle-callback annotation from both `shouldIntercept` filters (single
authority — `InterceptedShape`/shared IR — so APT, runtime emitter and weaver stay in lockstep):
`jakarta.annotation.PostConstruct`, `jakarta.annotation.PreDestroy`, `jakarta.interceptor.AroundConstruct`,
`jakarta.interceptor.AroundTimeout`, `jakarta.ejb.PostActivate` / `PrePassivate` (name-based, no symbol
completion — cf. VAU-INT-005). Add a regression test to `vauban-module-it` (APT, module path) and to
`vauban-core` asserting that an `@AroundInvoke` interceptor never sees a lifecycle callback and that
`InvocationContext.getMethod()` is `null` inside lifecycle interceptor methods.

### Why the CDI TCK did not catch it
The TCK has the exact fixture (`Goat` + `AnimalInterceptor` in `LifecycleCallbackInterceptorTest`) but
only asserts the positive path (lifecycle interceptor methods *are* called); its `@AroundInvoke` records
nothing and no test asserts it was *not* invoked for a callback. The only `assertFalse(methodIntercepted)`
cover `Object` methods and `@Inject` initializers — the two cases the filter already excludes. Details
and a proposed upstream assertion: `CDI_TCK_PROPOSALS.md` → `TCK-GAP-001`.

### Investigations
- 2026-09-11 : reproduced on `vauban-module-it` (0.4.0-SNAPSHOT, APT path). Root cause traced to the two
  `shouldIntercept` filters above; generated `RequestAuditedService$$Intercepted` confirmed to override
  `init()`/`dispose()`. Probe fixtures removed after the run (not committed).
- 2026-09-11 : TCK 4.1.0 coverage analysed (`cdi-tck-core-impl` sources) — gap documented as `TCK-GAP-001`.
- 2026-10-07 : fixed. `BeanMembers.NON_BUSINESS_METHOD_ANNOTATIONS` (vauban-core, `core.codegen`, exported to the
  processor) lists, by name, the annotations that make a method something other than a business method: `@Inject`,
  `@AroundInvoke`, `@AroundConstruct`, `@AroundTimeout`, `@PostConstruct`, `@PreDestroy`, `@PostActivate`,
  `@PrePassivate`. `InterceptorSubclassGenerator.shouldIntercept` and `InterceptedShapeFromElements.shouldIntercept`
  both read it, so the callbacks are no longer overridden in `$$Intercepted` and `BeanLifecycle`'s virtual call runs
  the bean's own method under the lifecycle chain only. Tests: `LifecycleCallbackInterceptionTest` (vauban-core,
  run-time path: a normal-scoped and a `@Dependent` bean, `getMethod()` is `null` in the interceptor's lifecycle
  methods) and `LifecycleCallbackModulePathTest` (vauban-module-it, processor path, module path), both failing
  first. heisenberg, dirac, cyrano and mansart rebuilt against it, their tests green.

## BUG-20260911-01 — Build-time Class-File bytecode takes the build JDK's class-file version, not the release

- **Date**: 2026-09-11
- **Status**: FIXED f9a2f4d
- **Module**: `vauban-processor` (`BeanFactoryGenerator`, and `ClientProxyEmitter` when the processor calls it: build-time `_ClientProxy` classes and the placed-proxy resources of #42 Stage 4); `vauban-maven-plugin` (`ComponentProviderClassGenerator` from `VaubanGenerator` and `DependencyEnhancer`).
- **Symptom**: a module compiled with `--release 25` on a JDK 26 ships its Class-File-generated classes at class file 70 while javac's own output is 69. A Java 25 runtime then rejects them: `UnsupportedClassVersionError: …JsonWebTokenContext_Factory has been compiled by a more recent version of the Java Runtime (class file version 70.0)` from `cassini-maven-plugin:generate`, and `jlink` fails with `Unsupported class file version: 70`.
- **Minimal reproduction** (observed, not yet rebuilt deliberately): eleven `io.vidocq` 0.4.0-SNAPSHOT jars in the local Maven repository, built on 2026-09-10, mix class files 69 and 70; only the `*_Factory` classes are 70.
  ```
  # with JDK 26 running Maven, a brick whose pom sets release 25:
  ./mvnw -o install -DskipTests -pl knock-cdi-vauban
  # class major of a generated factory: 70 (javac's classes: 69)
  # then, on Java 25, jlink over that jar: "Unsupported class file version: 70"
  ```
- **Suspected cause**: every build-time site calls `ClassFile.of().build(desc, …)` without `withVersion(...)`, so the class takes `ClassFile.latestMajorVersion()` of the JVM running javac or Maven. The version should follow the compilation target: `processingEnv.getSourceVersion()` in the processor, the project's release (or the major version of the classes being processed) in the plugin. Runtime-only sites (`InterceptedEmitter`, runtime proxies) are unaffected, since the same JVM loads what it defines.
- **Investigations**:
  - 2026-09-12: fixed in f9a2f4d. Every generator builds through `GeneratedClassFile.build`, which pins the class file version to Java 25. Proven by running the new tests on a JDK 26: red (70 vs 69) before, green after, green on Java 25 in both cases. The other bricks' Class-File sites (cassini, cyrano, mansart) generate at runtime only, where the running JVM defines what it writes, so they are unaffected.
  - 2026-09-11: found while gating vidocq#77 offline. The JWT and Knock examples fail on those local jars; CI, which builds on Java 25, does not see it. Site list from `grep "ClassFile.of().build"`: `BeanFactoryGenerator:69`, `ClientProxyEmitter:83`, `ComponentProviderClassGenerator:152` (build time), `InterceptedEmitter:106` (runtime). No site sets a version.

## BUG-20260912-01 — A nested bean's generated classes take its canonical name, so nothing can load them

- **Date**: 2026-09-12
- **Status**: FIXED (this branch)
- **Module**: `vauban-processor` (`apt/ElementScanner#scan`, hence `BeanFactoryGenerator` and `ClientProxyGenerator`)
- **Symptom**: for a nested normal-scoped bean `app.Holder.Counter`, the annotation processor emits `app/Holder/Counter_ClientProxy.class` and `app/Holder/Counter_Factory.class` — a class-as-package directory. The bytes name the class `app.Holder.Counter_ClientProxy` and give it the superclass `app/Holder/Counter`, which does not exist (the bean is `app/Holder$Counter`). The runtime looks up `app.Holder$Counter_ClientProxy`, finds nothing, and the two emitted classes are unloadable anyway: defining either one raises `NoClassDefFoundError: app/Holder/Counter`. A normal-scoped nested bean therefore has no usable client proxy at all.
- **Minimal reproduction**:
  ```java
  // app/Holder.java, compiled with the Vauban processor
  package app;
  public class Holder {
      @jakarta.enterprise.context.ApplicationScoped
      public static class Counter { public String hit() { return "hit"; } }
  }
  // javac -proc:full … → target/classes/app/Holder/Counter_ClientProxy.class
  //   javap: public class app.Holder.Counter_ClientProxy extends app.Holder.Counter
  ```
- **Suspected cause**: `ElementScanner#scan` built the `ClassInfo` name from `typeElement.getQualifiedName()` (canonical: `app.Holder.Counter`) instead of `Elements#getBinaryName` (`app.Holder$Counter`). The same trap had already been fixed for method return/parameter types in `typeMirrorToTypeInfo` (the `$`-vs-`.` comment there), but not for the scanned class's own name, and every artifact derived from it inherited the wrong name.
- **Investigations**:
  - 2026-09-12: found by writing the missing coverage for the APT's bytecode-proxy fallback (`NestedBeanProxyEmissionTest`) — the branch at `VaubanProcessor:480` had no end-to-end test, so the defect was invisible. Fixed by scanning the binary name. The fix is a no-op for top-level types (binary name == qualified name); it only changes nested ones, which were broken. `ComponentProviderCompileTimeTest#nestedBeanIsSkipped` still holds: the generated provider skips nested beans, so the proxy is resolved by name at runtime — which is exactly what the wrong name prevented.

## BUG-20260912-02 — The Vauban layer cannot be created inside a jlink image

- **Date**: 2026-09-12
- **Status**: FIXED (this branch)
- **Module**: `vauban-classloader` (`VaubanLayerFactory#applicationPaths`, `Launch#run`)
- **Symptom**: an application whose `main` starts with `Launch.run(...)` does not start at all from a `jlink` image. It fails with a message that names the wrong cause:
  ```
  Exception in thread "main" java.lang.IllegalStateException: No application module to re-layer.
  With -m, the launcher is the only root module: add --add-modules ALL-MODULE-PATH to the java
  command line. …
  ```
  Adding `--add-modules` changes nothing: there is no module path to add anything from. Consequently **every proxying case that depends on the layer is unavailable under `jlink`** — the in-package proxies the build ships under `META-INF/vauban/placed/` are never placed, and so is anything else the layer's loader does (sjar decryption, load-time weaving).
- **Minimal reproduction** (verified 2026-09-12 on the cdi#1015 example, which runs correctly on a plain module path):
  ```
  jlink --module-path <deps> --add-modules io.vidocq.vauban.example.cdi1015.app --output /tmp/img
  /tmp/img/bin/java -m io.vidocq.vauban.example.cdi1015.app/…Main
  # → IllegalStateException: No application module to re-layer
  ```
- **Suspected cause**: `applicationPaths` collects the archives to re-layer from each resolved module's `reference().location()`, keeping only URIs whose scheme is `file:` (`VaubanLayerFactory.java:94` and `:164`). In a runtime image every module's location is `jrt:/<module>`, so the filter drops all of them and the resulting path list is empty. The same filter is what makes the loader index the archives it owns, so simply lifting it is not enough: a `jrt:` module has no archive to read bytes from, and the layer's loader would have to read its classes through the `jrt` file system (`FileSystems.getFileSystem(URI.create("jrt:/"))`) instead.
- **Investigations**:
  - 2026-09-12: found while answering whether the loader-based placement survives `jlink`. It does not. `jpackage` is affected whenever it wraps a jlink runtime image; `jpackage` over a plain module path is not (same shape as the working `java -p` run). Until this is fixed, the build-time route — `vauban:enhance-dependencies`, which rewrites the dependency jar — is the only one that works under `jlink`, as it already is for GraalVM native images.
  - 2026-09-13: **fixed**. `applicationPaths` and `createAppLayer` no longer keep only `file:` locations; both go through `archiveOf`, which also maps a `jrt:` module to `/modules/<name>` of the `jrt` file system. Nothing else had to change: a module in a runtime image is an exploded directory, and `BuiltInReaders` already walks directories, so the layer's loader reads it exactly as it reads a `target/classes`. Verified end to end — the cdi#1015 example now runs from a jlink image with its layer, its placed proxies (`FraudScreen_ClientProxy`, `ReceiptPrinter_ClientProxy` defined inside the library's package) and its interceptor. A probe written first confirmed the JDK allows it: `ModuleFinder.of(jrtPath)` finds the module and a child layer resolves from it, with its own loader and its own class identity. The unit test needs no jlink run: every JVM has `jrt:/java.base`.

## BUG-20260914-01 — Boot validation ignores member defaults, so `@Q` and `@Q("default")` never match

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-core` (`QualifierMatcher#qualifierEquals`); neither `ClassFileScanner` nor `ElementScanner` records member defaults.
- **Symptom**: a valid deployment fails with `DeploymentException: Unsatisfied dependency` when an injection point and a bean spell the same qualifier differently, one relying on a member's default and the other writing it out. The same lookup done programmatically resolves, because the run-time path reads members through the annotation, defaults included.
- **Minimal reproduction** (`QualifierMemberResolutionTest$DefaultedMember`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Graded { String value() default "standard"; }
  @Graded @Dependent class GradedImplicit implements Service {}
  @Dependent class GradedField { @Inject @Graded("standard") Service service; }
  // VaubanContainer.builder().addBeanClass(GradedImplicit.class).addBeanClass(GradedField.class).build()
  // → DeploymentException: Unsatisfied dependency: field GradedField.service
  ```
- **Suspected cause**: the index keeps explicit member values only, and `qualifierEquals` compares the two member maps as they are, so a member written on one side and defaulted on the other counts as a mismatch. `java.lang.annotation.Annotation#equals` treats both spellings as the same annotation.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the tests are disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 2. Both scanners now record the default of each annotation member (`MethodInfo#defaultValue`), and `QualifierMatcher` compares `AnnotationKey`s, in which every member the type declares takes its written value or its default. Proven by mutation: with defaults left out of the key, both `$DefaultedMember` tests fail again with the same `DeploymentException`, and so do the default tests of `AnnotationTypesTest` and `QualifierMatcherTest`.

## BUG-20260914-02 — A field injection point drops enum, Class, char, byte, short, array and nested-annotation member values

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`QualifierHelper#annotationValueToObject`, reached from `BeanInjector#injectSingleField`)
- **Symptom**: boot validation accepts the injection point, then the field is injected wrongly at creation, without any error: it stays `null`, or receives the `@Default` bean when there is one. A constructor parameter or a programmatic lookup with the same qualifier resolves correctly.
- **Minimal reproduction** (`QualifierMemberResolutionTest$EnumMember#field`, `$NoDefaultFallback#enumField`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Colored { Hue value(); }
  @Colored(Hue.RED) @Dependent class ColoredOne implements Service {}
  @Colored(Hue.BLUE) @Dependent class ColoredTwo implements Service {}
  @Dependent class PlainService implements Service {}
  @Dependent class ColoredField { @Inject @Colored(Hue.BLUE) Service service; }
  // container.select(ColoredField.class).service → PlainService; null without PlainService
  ```
- **Suspected cause**: the field's qualifiers are rebuilt from the index as `QualifierHelper` proxies, and `annotationValueToObject` converts only String, boolean, int, long, float and double members; any other member reads as `null`. `getBeans` then compares `null` with the bean's value, finds no match, and the injection falls back on a `@Default` lookup. The same proxy also returns `hashCode` 0 and compares only the members it was given.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net for every listed kind (`$ClassMember#field`, `$NarrowPrimitiveMembers#field`, `$ObjectArrayMember#field`, `$PrimitiveArrayMember#field`, `$NestedAnnotationMember#field`); the tests are disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3a, which was aimed at BUG-20260914-03: field injection rebuilds its qualifiers through the same conversion as `Bean#getQualifiers()`, and `AnnotationInstances` keeps every member kind, so `getBeans` compares the values it was given. The seven tests are enabled here, once the change proved it. The field path still loads the qualifier type through the thread context class loader (BUG-20260914-05) and still rebuilds an instance per injection; PR 3b resolves it on keys instead.

## BUG-20260914-03 — Container-built qualifier instances misreport array and nested members and leave `@Nonbinding` out of `equals` and `hashCode`

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`QualifierUtils#createAnnotationInstance`, `#convertAnnotationValue`, `#membersEqual`, `#computeAnnotationHashCode`)
- **Symptom**: one proxy, two visible effects.
  - Resolution: a qualifier with an `int[]` or a nested-annotation member never matches at run time; constructor injection and programmatic lookups throw `UnsatisfiedResolutionException`. A `String[]` member happens to match (see the cause).
  - `Bean#getQualifiers()` breaks the `java.lang.annotation.Annotation` contract: reading an `int[]` member throws `ClassCastException: [Ljava.lang.Object; cannot be cast to [I`, a nested member returns `null`, and `equals`/`hashCode` skip `@Nonbinding` members, so `@Noted(value = "v", note = "n")` equals `@Noted(value = "v", note = "other")` and its hash code differs from the JDK's.
- **Minimal reproduction** (`BeanQualifiersContractTest`, `QualifierMemberResolutionTest$PrimitiveArrayMember`, `$NestedAnnotationMember`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Codes { int[] value(); }
  @Codes({1, 2}) @Dependent class Specimen implements Shape {}
  Annotation built = beanManager.resolve(beanManager.getBeans(Shape.class, Any.Literal.INSTANCE))
          .getQualifiers().stream().filter(q -> q.annotationType() == Codes.class).findFirst().orElseThrow();
  ((Codes) built).value();                                  // ClassCastException
  Specimen.class.getAnnotation(Codes.class).equals(built);  // false
  ```
- **Suspected cause**: `convertAnnotationValue` turns every array into a fresh `Object[]` and every nested annotation into `null`, so an `int[]` member can never equal an `int[]`, while a `String[]` passes `Objects.deepEquals` by luck. The handler's `equals` and `hashCode` apply CDI's `@Nonbinding` rule, which belongs to resolution, not to `Annotation#equals`. `getQualifiers()` also rebuilds these proxies on every call.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net, with the JDK's own annotation instance as the expected value; the tests are disabled with this id. The CDI TCK tests of this contract (`QualifierEquivalenceTest`, `InterceptorBindingEquivalenceTest`) belong to `cdi-full` and are excluded from the Lite run.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. `AnnotationInstances` replaces the five proxy handlers with one instance built from index data: each member returns its declared type — an `int[]` as an `int[]`, a nested annotation as that annotation — and `equals`, `hashCode` and `toString` follow the `Annotation` specification, `@Nonbinding` members included, since non-binding is a rule of CDI resolution and not of this contract. `Bean#getQualifiers()` builds the set once per bean instead of rebuilding proxies on every call. Proven by mutation: leaving `@Nonbinding` members out of `equals` and `hashCode` fails the two contract tests again, and retyping array members to `Object[]` fails the `int[]` and nested-annotation ones with the original `ClassCastException`.

## BUG-20260914-04 — Boot validation loses `@Nonbinding` on a qualifier type vauban-core's own class loader cannot see

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-core` (`QualifierMatcher#qualifierEquals`)
- **Symptom**: when the application's classes live in their own class loader, as with the TCK runner or a layer created by `Launch`/`Vidocq.run`, an injection point whose `@Nonbinding` member differs from the bean's fails the deployment with `Unsatisfied dependency`. The same fixture deploys when every member value is equal.
- **Minimal reproduction** (`QualifierMemberResolutionTest$IsolatedClassLoader#nonbindingMember`; the fixtures are generated with the Class-File API into a `URLClassLoader`, set as context class loader):
  ```java
  // iso.Marked:      @Qualifier @interface Marked { String value(); @Nonbinding String note(); }
  // iso.MarkedOne:   @Marked(value = "a", note = "one") @Dependent class MarkedOne implements Supplier
  // iso.MarkedLoose: @Inject @Marked(value = "a", note = "two") public Supplier supplier;
  VaubanContainer.builder().classLoader(loader).addBeanClass(…).build();
  // → DeploymentException: Unsatisfied dependency: field MarkedLoose.supplier
  ```
- **Suspected cause**: `qualifierEquals` looks the `@Nonbinding` members up with the one-argument `Class.forName`, which uses vauban-core's defining loader. The `ClassNotFoundException` is swallowed as "no non-binding member", so the differing note is compared.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 2. The container builds one `AnnotationTypes` for its lifetime. It describes a qualifier type from the index first, then from the type's class file or its declaration, read through the discovery class loader, the context class loader and vauban-core's own loader, in that order, so a `@Nonbinding` member is known whichever loader defines the type. Proven by mutation: with vauban-core's loader alone, and the index kept, `#nonbindingMember` fails again with the same `Unsatisfied dependency`, since `iso.Marked` is not indexed; it fails too when `@Nonbinding` members are kept in the key.

## BUG-20260914-05 — Field injection loads qualifier types through the thread context class loader and silently drops those it cannot load

- **Date**: 2026-09-14
- **Status**: FIXED a0110d4
- **Module**: `vauban-core` (`QualifierHelper#qualifierInstancesToAnnotations`)
- **Symptom**: with the application's classes in their own class loader and the thread context class loader left as it is, a qualified field stays `null` after injection, without any error, although boot validation accepted it.
- **Minimal reproduction** (`QualifierMemberResolutionTest$IsolatedClassLoader#withoutContextClassLoader`): the fixtures of BUG-20260914-04 with equal member values, built without setting the context class loader; `supplier` is `null`.
- **Suspected cause**: the qualifier type is loaded with `Thread.currentThread().getContextClassLoader().loadClass(name)`. A `ClassNotFoundException` skips the qualifier, the injection point is then resolved with `@Default` alone, and nothing matches.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3b. Field injection resolves the keys of the injection point the index built, so it builds no qualifier instance and loads no qualifier type. Proven by mutation: rebuilding the qualifiers as instances through the context class loader fails the test again.

## BUG-20260914-06 — Asynchronous observers ignore qualifier member values

- **Date**: 2026-09-14
- **Status**: FIXED a0110d4
- **Module**: `vauban-core` (`EventDispatcher#fireAsync`)
- **Symptom**: an event fired asynchronously with `@Channel("alpha")` reaches both `@ObservesAsync @Channel("alpha")` and `@ObservesAsync @Channel("beta")`. A synchronous `fire` delivers it to the first observer only.
- **Minimal reproduction** (`QualifierMemberEventTest#asynchronousMemberValue`):
  ```java
  public void alpha(@ObservesAsync @Channel("alpha") Ping ping) { … }
  public void beta(@ObservesAsync @Channel("beta") Ping ping) { … }
  event.select(channelAlpha).fireAsync(new Ping("3")).toCompletableFuture().get();
  // received: [async-alpha:3, async-beta:3]
  ```
- **Suspected cause**: `fireAsync` calls the three-argument `findMatchingObservers`, which compares qualifier names only, whereas `fire` passes the annotations on to `observerQualifiersMatchFull`.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3b. There is one matching method left, on `AnnotationKey`s, and both paths call it; the event's qualifiers are converted on the calling thread, before the task. Proven by mutation: matching on the qualifier type alone on the asynchronous path fails the test again, with the original two observers notified.

## BUG-20260914-07 — Observers ignore members an extension made non-binding

- **Date**: 2026-09-14
- **Status**: FIXED a0110d4
- **Module**: `vauban-core` (`EventDispatcher#qualifierMembersMatch`)
- **Symptom**: for a qualifier registered with `MetaAnnotations.addQualifier` whose `value` member the extension marks `@Nonbinding`, observer resolution still compares `value`, so an event with another `value` never reaches the observer. Injection and programmatic lookups honour the rule, and the observer does receive the event when every member is equal.
- **Minimal reproduction** (`QualifierMemberEventTest#extensionNonbindingMember`):
  ```java
  meta.addQualifier(Stream.class).methods().stream()
          .filter(m -> m.info().name().equals("value")).forEach(m -> m.addAnnotation(Nonbinding.class));
  public void grouped(@Observes @Stream(value = "observer", group = "g") Ping ping) { … }
  event.select(streamEvent).fire(new Ping("4"));   // @Stream(value = "event", group = "g")
  // received: []
  ```
- **Suspected cause**: `qualifierMembersMatch` skips only members annotated `@Nonbinding` in source; unlike `QualifierMatcher` and `VaubanBeanManager#qualifierEquals`, it never consults the extension-declared set.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3b. Observers match on keys, which the container's `AnnotationTypes` builds — and it holds what the extensions declared non-binding, so every path applies the same rule. Proven by mutation: building that metadata without the extension-declared members fails the observer test and the two injection ones.

## BUG-20260914-08 — Interceptor bindings declared by an extension ignore their member values

- **Date**: 2026-09-14
- **Status**: FIXED bbb3c0b
- **Module**: `vauban-core` (interceptor resolution: `InterceptorDiscovery`, `InterceptorManager#bindingMembersMatchWherePresent`)
- **Symptom**: a binding registered with `MetaAnnotations.addInterceptorBinding` binds its interceptor whatever the member values: a method annotated `@Metered(value = "method", group = "other")` is intercepted by an interceptor declared `@Metered(value = "interceptor", group = "g")`, although `group` is binding.
- **Minimal reproduction** (`InterceptorBindingMemberTest#extensionBindingMemberValue`):
  ```java
  meta.addInterceptorBinding(Metered.class).methods()…   // value made @Nonbinding, group stays binding
  @Metered(value = "interceptor", group = "g") @Interceptor @Priority(APPLICATION) class MeteredInterceptor { … }
  @Metered(value = "method", group = "other") public String metered() { … }
  // intercepted; expected not
  ```
- **Suspected cause**: interceptor discovery keeps only annotation types meta-annotated `@InterceptorBinding`, while the bean side also accepts bindings registered by extensions; the interceptor's binding list is then empty and `bindingMembersMatchWherePresent` has nothing to compare.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net. `#extensionNonbindingMember` passed first; its control showed that it passes only because no member is compared at all. `#extensionBindingMemberValue` is disabled with this id.
  - 2026-09-14 (fix, bbb3c0b): two causes, both about values that never reached the comparison. `InterceptorDiscovery` collected an interceptor's binding *annotations* by asking each annotation type for a physical `@InterceptorBinding`, which a type registered by an extension does not carry, so the descriptor's list stayed empty — and `bindingMembersMatchWherePresent` returns `true` on an empty list; it now uses the set of binding names beside it, which already went through the predicate that knows about extensions. And `VaubanMetaAnnotations` reported the members an extension had made `@Nonbinding` for its qualifiers but not for its interceptor bindings, so `value` would have started binding as soon as the first cause was fixed. The comparison itself moved onto `AnnotationKey`, which applies member defaults and drops non-binding members from both sources at once. `#extensionBindingMemberValue` is enabled.

## BUG-20260914-09 — An `@Inherited` qualifier loses its long, float, double, byte, short, char, array and nested members

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-core` (`QualifierResolver#toAnnotationInfo`)
- **Symptom**: a bean inheriting `@Leveled(1L)` from its superclass can neither be injected nor looked up with `@Leveled(1L)`: unsatisfied at boot validation and on lookup. The same scenario with a `String` member resolves on both paths.
- **Minimal reproduction** (`QualifierMemberResolutionTest$InheritedQualifier#longField`, `#longProgrammatic`):
  ```java
  @Inherited @Qualifier @Retention(RUNTIME) @interface Leveled { long value(); }
  @Leveled(1L) abstract class LeveledBase {}
  @Dependent class LeveledChild extends LeveledBase implements Service {}
  @Dependent class LeveledField { @Inject @Leveled(1L) Service service; }
  // → DeploymentException: Unsatisfied dependency: field LeveledField.service
  ```
- **Suspected cause**: inherited annotations are read reflectively and converted by a switch that keeps only `String`, `Boolean`, `Integer`, `Class` and enum values; any other member is dropped from the qualifier, which then cannot equal the required one.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the tests are disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 2. `QualifierResolver#toAnnotationInfo` delegates to `AnnotationValues#toAnnotationInfo`, which converts every member kind the way the bytecode scan records it; `AnnotationValuesTest` checks each kind against `ClassFileScanner`. A member that cannot be read is still left out, as before, since the inherited annotation need not be a qualifier. Proven by mutation: keeping only the five former kinds makes `#longField` and `#longProgrammatic` fail again.

## BUG-20260914-10 — A qualifier added by an `@Enhancement` turns enum, Class, array and nested members into strings

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`EnhancementApplier#annotationMemberToValue`)
- **Symptom**: an extension adding `@Colored(Hue.BLUE)` to a bean class leaves a `@Colored(Hue.BLUE)` injection point unsatisfied at boot validation. Adding a `String`-valued qualifier the same way works.
- **Minimal reproduction** (`QualifierMemberResolutionTest$EnhancementAddedQualifier#enumMember`):
  ```java
  @Enhancement(types = PaintedBlue.class)
  public void paint(ClassConfig config) {
      config.addAnnotation(AnnotationBuilder.of(Colored.class).member("value", Hue.BLUE).build());
  }
  // @Inject @Colored(Hue.BLUE) Service service; → DeploymentException: Unsatisfied dependency
  ```
- **Suspected cause**: the member conversion handles strings and primitives, and maps every other kind to `StringVal(member.toString())`, which cannot equal the injection point's `EnumVal`.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. `LangModelAnnotations` converts an annotation an extension writes into the index model without losing anything: an enum constant keeps its enum type, a class literal stays a class, nested annotations and arrays are converted element by element. Proven by mutation: stringifying the enum member again fails `$EnhancementAddedQualifier#enumMember` and the lang-model comparison.

## BUG-20260914-11 — The default name of a nested bean class keeps its enclosing class

- **Date**: 2026-09-14
- **Status**: FIXED (vauban#116, branch `pr/ybl/nested-bean-default-name`)
- **Module**: `vauban-core` (`StereotypeResolver` and `BeanDiscovery#decapitalize`), through `DotName#simpleName`
- **Symptom**: `@Named` on the static nested class `Outer.ReportService` names the bean `outer$ReportService` instead of `reportService`, so `@Named("reportService")` is unsatisfied at boot validation and on lookup.
- **Minimal reproduction** (`QualifierMemberResolutionTest$NamedQualifier#field`, `#programmatic`):
  ```java
  class Outer { @Named @Dependent public static class ReportService implements Service {} }
  CDI.current().select(Service.class, NamedLiteral.of("reportService")).get();   // UnsatisfiedResolutionException
  ```
- **Suspected cause**: `DotName#simpleName` cuts the binary name at its last `.`, which leaves `Outer$ReportService` for a nested class; CDI 4.1 §3.1.5 takes the unqualified class name, `ReportService`.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net, whose fixtures are nested classes; the tests are disabled with this id. Top-level classes are not affected. Not part of the vauban#70 rework.
- **Fix**: `ClassInfo` gains a `simpleName` component, the unqualified name as `Class#getSimpleName()` gives it. The
  bytecode scan reads it from the class's own entry of the `InnerClasses` attribute (`""` for an anonymous class,
  the binary simple name for a top-level class, which has no entry); the processor's `ElementScanner` takes
  `TypeElement#getSimpleName()`. `StereotypeResolver` builds the default name from it, and the two enrichment
  copies (`IndexEnricher`, `VaubanGenerator`) keep it through `ClassInfo#withAnnotations`. The eight-argument
  constructor stays and derives the name from the binary name, as before. Covered by the two re-enabled
  `QualifierMemberResolutionTest$NamedQualifier` tests, `ClassFileScannerTest` (nested, top-level and local
  classes) and `ElementScannerMemberValueTest#nestedSimpleName` (both scanners agree).

## BUG-20260914-12 — The processor names nested types canonically in member values, and loses primitive and array class literals

- **Date**: 2026-09-14
- **Status**: FIXED 358fc16
- **Module**: `vauban-processor` (`ElementScanner#scanAnnotations`, `#convertAnnotationValue`, `#typeMirrorToDotName`)
- **Symptom**: the processor's index disagrees with the run-time bytecode scan of the same class. For `@Probe` declared in `app.Holder`, the processor records the annotation `app.Holder.Probe`, an enum value of type `app.Holder.Hue`, a nested `@app.Holder.Inner` and the class literal `app.Holder.Hue`, where the class file says `app.Holder$Probe`, `app.Holder$Hue` and `app.Holder$Inner`. `int.class` and `String[].class` both become `java.lang.Object`.
- **Minimal reproduction** (`ElementScannerMemberValueTest`): compile the fixture with a processor that runs `ElementScanner#scan` on `app.Holder.Target`, then compare with `ClassFileScanner#scan` of `app/Holder$Target.class`.
- **Suspected cause**: annotation, enum and class-literal names come from `getQualifiedName()`, which BUG-20260912-01 replaced by `Elements#getBinaryName` for the scanned class and its method types but not for member values; `typeMirrorToDotName` falls back to `java.lang.Object` for anything that is not a declared type.
- **Investigations**:
  - 2026-09-14: found by the vauban#70 safety net; the tests are disabled with this id. vauban#70 keys qualifier matching on these values, so fixing it comes before the build-time metadata.
  - 2026-09-14: **fixed** by vauban#70 PR 2. Annotation, enum and nested-annotation names go through `Elements#getBinaryName`. A class literal is named the way `DotName#fromDescriptor` reads the class file: the keyword for a primitive, the descriptor for an array. The superclass and interface names, which were still canonical, follow the same rule (`#nestedSupertypes`), and the processor now records member defaults too, compared with the bytecode scan by `#memberDefaults`. Proven by mutation: with canonical names and the `java.lang.Object` fallback put back, the eight comparisons fail again, while the bytecode-side guard and the String member stay green.

## BUG-20260914-13 — Compile-time validation does not know qualifiers declared in the module being compiled

- **Date**: 2026-09-14
- **Status**: FIXED be435eb
- **Module**: `vauban-processor` (`VaubanProcessor#getSupportedAnnotationTypes`, `#resolveDependencyBeans`), through `vauban-core`'s `QualifierResolver#isQualifierAnnotation`
- **Symptom**: a module that declares its own qualifier and two beans of one type does not compile with the Vauban processor: `[Vauban] Ambiguous dependency: field Checkout.card of type … Matching beans: [CardPayment, WirePayment]`, although `@Channel("card")` selects a single bean. The container resolves the same deployment at run time.
- **Minimal reproduction** (`SameModuleQualifierValidationTest#sameModuleQualifier`):
  ```java
  @Qualifier @Retention(RUNTIME) @interface Channel { String value(); }
  interface Payment {}
  @Channel("card") @Dependent class CardPayment implements Payment {}
  @Channel("wire") @Dependent class WirePayment implements Payment {}
  @Dependent public class Checkout { @Inject @Channel("card") Payment card; }
  // javac -proc:full with VaubanProcessor → error: Ambiguous dependency: field Checkout.card
  ```
- **Suspected cause**: the processor indexes the types carrying its supported annotations (the bean-defining ones and the extension triggers) and the injection points' required types; a qualifier annotation type is neither. `isQualifierAnnotation` misses it in the index and falls back on `Class.forName` through the thread context class loader, which cannot load a type still being compiled. The qualifier is then ignored on both sides and every bean of the type matches. A qualifier from a dependency jar is probably affected the same way, since the context class loader of javac does not see the compile classpath.
- **Investigations**:
  - 2026-09-14: found while compiling the vauban#70 benchmarks, which work around it with `-Avauban.validation=false` (the container validates again at boot); the test is disabled with this id.
  - 2026-09-14: **fixed** by vauban#70 PR 4a. Before discovery runs, the processor adds to its index every annotation type the indexed classes name, and then the types those name in turn, resolving each one through `Elements` — which sees the module being compiled and the compile classpath, where a class loader sees neither. Discovery therefore reads `@Qualifier`, `@Stereotype` and `@InterceptorBinding` from the index and never falls back on `Class.forName`. Compile-time matching goes through an `AnnotationTypes` built on that index, so a member default counts as a written value and a `@Nonbinding` member takes no part, as at run time. `vauban-bench` compiles with validation on again: its `-Avauban.validation=false` is gone. Proven by mutation: without the indexing step the two-bean fixture is ambiguous again, and with the previous matcher the defaulted injection point is unsatisfied.

## BUG-20260914-14 — The CDI invoker wraps the trailing array of a varargs method in another array

- **Date**: 2026-09-14
- **Status**: FIXED 3e05bdf
- **Module**: `vauban-core` (`VaubanInvoker#unreflect`, `#invoke`)
- **Symptom**: calling a varargs method through a CDI `Invoker` throws `ClassCastException: Cannot cast [Ljava.lang.String; to java.lang.String` at `VaubanInvoker.java:144`. The CDI TCK's `VarargsMethodInvokerTest` fails: 773/774 on 2026-09-14.
- **Minimal reproduction** (`VaubanInvokerTest#varargsMethod`):
  ```java
  public String join(String head, String... tail) { … }
  new VaubanInvoker(Calculator.class.getMethod("join", String.class, String[].class), Calculator.class, false, Set.of())
          .invoke(new Calculator(), new Object[] {"a", new String[] {"b", "c"}});   // ClassCastException
  ```
- **Suspected cause**: a regression from b9b7ab1 (vauban#71). `MethodHandles.Lookup#unreflect` returns a variable-arity handle for a varargs method, and `invokeWithArguments` then collects the trailing arguments into a fresh array, so the `String[]` the caller passes ends up inside a new one. The reflective call it replaced passed the array through.
- **Investigations**:
  - 2026-09-14: found by the CDI TCK run of the vauban#70 safety-net PR. It went unnoticed because the pull-request workflow runs no TCK and the main workflow does not read the TCK reports (`testFailureIgnore=true`).
  - 2026-09-14: fixed. `unreflect` returns the handle with `asFixedArity()`, so `invokeWithArguments` passes the caller's array through, as `Method#invoke` did. `VaubanInvokerTest#varargsMethod` was written first and failed with the TCK's exact `ClassCastException`; it passes with the fix. vauban-core 356 tests green, CDI TCK 774/774 and AtInject green, counted from the surefire reports.

## BUG-20260914-15 — A synthetic bean or observer loses the member values of its qualifiers

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`VaubanSyntheticBeanBuilder#qualifier(AnnotationInfo)`, `VaubanSyntheticObserverBuilder#qualifier(AnnotationInfo)`, `SyntheticComponentRegistrar#toObserverDescriptor`)
- **Symptom**: a synthetic bean qualified `@Channel("alpha")` answers no lookup at all — neither `@Channel("alpha")` nor any other value — and a synthetic observer qualified the same way receives no event. `AnnotationBuilder` is the only way an extension can write an annotation, so this is every qualified synthetic component whose qualifier has a member.
- **Minimal reproduction** (`SyntheticQualifierMemberTest`):
  ```java
  @Synthesis
  public void synthesise(SyntheticComponents components) {
      components.addBean(String.class).type(String.class)
              .qualifier(AnnotationBuilder.of(Channel.class).member("value", "alpha").build())
              .createWith(AlphaCreator.class);
  }
  // CDI.current().select(String.class, channelAlpha).get() → UnsatisfiedResolutionException
  ```
- **Suspected cause**: `qualifier(AnnotationInfo)` keeps the annotation's name and drops its members: it loads the type and delegates to `qualifier(Class)`, which builds a proxy answering every member with its default — `null` for a member that has none. The observer path loses them a second time, in the registrar: `new QualifierInstance(qName, Map.of())`.
- **Investigations**:
  - 2026-09-14: found while writing the vauban#70 PR 3a tests, which cover the lossless path from an extension-written annotation to resolution.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. Both builders keep the annotation an extension wrote, member values included, as an `AnnotationInstances` instance; the registrar converts an observer's qualifiers through `AnnotationValues#infoOf`, which reads the index data such an instance carries. Proven by mutation: keeping only the qualifier's type on either path fails the matching test again.

## BUG-20260914-16 — `AnnotationBuilder.member(name, Foo.class)` records the class name as a string

- **Date**: 2026-09-14
- **Status**: FIXED b71b31a
- **Module**: `vauban-core` (`VaubanAnnotationBuilder#member(String, Class)`, `#member(String, Class[])`, `#member(String, ClassInfo)`, `#member(String, ClassInfo[])`)
- **Symptom**: a qualifier an extension adds with a `Class` member never matches an injection point that declares the same qualifier in source, so the deployment fails with `Unsatisfied dependency`. Reading the member back through the lang model fails too: `asType()` throws `IllegalStateException: Not a CLASS value, but STRING`, where CDI 4.1 §BCE says `member(String, Class)` writes a class-typed member.
- **Minimal reproduction** (`QualifierMemberResolutionTest$EnhancementAddedQualifier#classMember`):
  ```java
  @Enhancement(types = PaintedKind.class)
  public void paint(ClassConfig config) {
      config.addAnnotation(AnnotationBuilder.of(OfKind.class).member("value", Integer.class).build());
  }
  // @Inject @OfKind(Integer.class) Service service; → DeploymentException: Unsatisfied dependency
  ```
- **Suspected cause**: the four overloads store `BuiltAnnotationMember.ofString(value.getName())`, so the member reads as a `String` where the class file records a class. The `Type` overloads do record a class (`ofClass`), which is why only the `Class`/`ClassInfo` ones are affected.
- **Investigations**:
  - 2026-09-14: found while writing the vauban#70 PR 3a tests. `VaubanParameters#convertMemberValue` carries a workaround for the same shape — it reads a `Class`-typed member back from a string.
  - 2026-09-14: **fixed** by vauban#70 PR 3a. The four overloads record a class member, whose lang-model type names a primitive by its keyword and an array by its descriptor, as the class file does. The workaround is gone with `convertMemberValue`, but a string written for a `Class` member is still read as one, so an annotation built before this fix keeps working. Proven by mutation: recording the class name again fails the end-to-end `@Enhancement` test and both lang-model conversions.

---

## BUG-20260914-17 — An injection failure is swallowed, and the field is left null

- **Date**: 2026-09-14
- **Status**: FIXED (see the investigation of 2026-09-14 below)
- **Module**: `vauban-core` (`BeanInjector#injectSingleField`, `ManagedBean#getInjectionPoints`)
- **Symptom**: when injecting a field throws anything other than `IllegalProductException` or `UnproxyableResolutionException`, the exception is logged and swallowed; the field keeps its default value and the bean is handed out looking fine. The failure surfaces later as a `NullPointerException` in application code, with a stack trace that names neither the field nor the cause. `ManagedBean#getInjectionPoints` does the same with an empty `catch` and returns an empty set, so `Bean#getInjectionPoints()` silently reports that a bean has no injection points at all.
- **Minimal reproduction**: make any field injection throw — for instance run a module under `-Dvauban.annotations.reflection=forbid` before vauban#70 PR 4d, which turns a reflective read into an exception:
  ```
  java.lang.NullPointerException: Cannot invoke "…Payment.id()" because "this.viaField" is null
      at …Checkout.fromField(Checkout.java:55)
  ```
  The real cause — `reading the qualifiers off field …Checkout.viaField needs reflection` — appears only in a log line.
- **Suspected cause**: `catch (Exception e) { LOG.log(ERROR, …); }` in `injectSingleField`, and `catch (Exception e) { // Fallback to empty set }` in `getInjectionPoints`. CDI 4.1 §5.1.2 makes an unsatisfiable injection point a deployment problem, not a null field.
- **Investigations**:
  - 2026-09-14: found while writing the `forbid` integration test of vauban#70 PR 4d, where it hid three genuine failures behind NPEs. That PR only makes `AnnotationReflection.ForbiddenException` pass through both catches — a diagnostic mode that a `catch` can silence is worthless — and leaves the rest of the behaviour alone: turning the swallow into a propagation changes what happens to every failing injection in the container, which wants its own change and its own TCK run.
  - 2026-09-14 (fix): both catches propagate. `injectSingleField` rethrows a `RuntimeException` with its type intact — a caller catching `UnsatisfiedResolutionException` or `IllegalProductException` must still see it — and wraps a checked exception in `CreationException`, naming the field. `getInjectionPoints` throws rather than reporting that a bean has half its injection points, or none: the caller cannot tell a wrong answer from the truth. **The swallow turned out to protect nothing**: 434 unit tests, both surefire run orders, the CDI Lite TCK 774/774 and AtInject are green without it, and no test relied on a failed injection leaving a field null. Covered by `InjectionFailurePropagatesTest`; the `getInjectionPoints` half has no test of its own — nothing in the reactor makes that method fail — and rests on those suites staying green.

---

## BUG-20260914-18 — `Bean#destroy` runs a producer's disposer twice

- **Date**: 2026-09-14
- **Status**: FIXED (vauban#115, branch `pr/ybl/disposer-runs-once`)
- **Module**: `vauban-core` (`ManagedBean#destroy`, the dependent-context release)
- **Symptom**: destroying a produced `@Dependent` instance calls its `@Disposes` method **twice**. `ManagedBean#destroy` invokes the destroyer, then releases the `CreationalContext`, and the instance is destroyed a second time along the way — the `removeIf(dep -> dep.instance() == instance)` guard just above does not cover it, so the instance must also be registered as a dependent of another context.
- **Minimal reproduction** (`DisposerParameterTest#anUnqualifiedParameterIsUnaffected`, which pins the count at 2 on purpose so a fix fails there and gets noticed):
  ```java
  var bean = beanManager.resolve(beanManager.getBeans(Ledger.class));
  var context = beanManager.createCreationalContext(bean);
  var ledger = beanManager.getReference(bean, Ledger.class, context);
  bean.destroy(ledger, context);
  // the disposer ran twice
  ```
- **Suspected cause**: two paths destroy the same instance — the explicit `destroyer.accept(...)` and the dependent released by `cc.release()`. Whichever registration the guard misses is the one to find.
- **Investigations**:
  - 2026-09-14: found while fixing vauban#89. It is older than that change and independent of it: the count is 2 whether the disposer's other parameter is qualified or not, which is why the test pins both cases. It was invisible until #89 made the disposer's body actually run — before, its parameters resolved to nothing and the call failed inside a `catch (Exception) { /* Best effort */ }`.
  - CDI 4.1 §5.5.3: a disposer runs once per destroyed instance. Note `ManagedBean#destroy` suppresses any exception a disposer throws, which the specification does require — that swallow is not this bug.
  - 2026-10-07: the second call came from `DisposerInvoker.callDisposer` itself. It resolved the disposer's declaring
    instance and other parameters in the produced instance's `CreationalContext`, then released that context when
    the call completed — and that context also holds the produced instance (registered by `getReference`), so the
    release ran `Bean#destroy` again, before the `removeIf` guard of `ManagedBean#destroy` was reached.
- **Fix**: the disposer call gets its own `CreationalContext`; the `@Dependent` objects created to receive it are
  destroyed when it completes (CDI 4.1 §6.4.2), and the produced instance's context is released once, by
  `ManagedBean#destroy`. `DisposerParameterTest` now asserts one call in both cases (it pinned two), and that the
  `@Dependent` parameter created for the call is still destroyed after it.

## BUG-20261001-01 — A synthetic bean built in the processor is listed as a managed bean, and the boot fails

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/extension-build-time-signal`)
- **Module**: `vauban-processor` (`VaubanProcessor#writeBeansList`)
- **Surfaced by**: Vidocq/ravel#21 (Sébastien Blanc), once Ravel's extension ran in the compiler.
- **Symptom**: an application compiled with a Build Compatible Extension on the processor path does not start. A synthetic bean the extension adds in `@Synthesis` (Ravel: one typed `java.lang.String`, qualified `@ConfigProperty`) is written to `META-INF/vauban-beans.list` as well as to the synthetic metadata. The container scans every class the list names: `java.lang.String` comes from the bootstrap loader, `getClassLoader()` is `null`, and `VaubanContainerBuilder#build` throws a `NullPointerException`.
- **Minimal reproduction**: `BceCompileTimeTest#syntheticBeanClassStaysOutOfTheBeanList` — a BCE that calls `components.addBean(String.class)`; the bean list held `[RealBean, java.lang.String]`.
- **Cause**: the synthetic descriptors are appended to the discovered beans so that validation sees them, and `writeBeansList` listed every descriptor, synthetic ones included.
- **Fix**: `writeBeansList` skips `BeanKind.SYNTHETIC`; the synthetic metadata already carries those beans to the container.

## BUG-20261001-02 — An extension cannot tell the processor from the container start

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/extension-build-time-signal`)
- **Module**: `vauban-api` (new `ExtensionPhase`), `vauban-processor`, `vauban-maven-plugin`
- **Surfaced by**: Vidocq/ravel#21.
- **Symptom**: Ravel's extension, run in the processor, checked configuration values against the build machine (`Missing required config property 'shop.name'`) although the value comes from the deployment. CDI Lite gives an extension no way to know where it runs.
- **Fix**: `ExtensionPhase.isBuildTime()`, a `ScopedValue` the processor and the Maven plugin bind around every extension phase they run (`ExtensionPhase.atBuildTime`). Pinned by `ExtensionPhaseTest`, `BceCompileTimeTest#extensionKnowsItRunsAtBuildTime` and `VaubanGeneratorTest#extensionsRunByThePluginKnowTheyRunAtBuildTime`.

## BUG-20261001-03 — An `@Inject` constructor next to a no-arg one falls back to reflection (grimm#15)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/inject-constructor-over-no-arg`)
- **Module**: `vauban-indexer` (`ComponentCollector#instantiableCtorParams`)
- **Symptom**: once Grimm shipped named modules, `GrimmModelCache` (an `@Inject` constructor taking `GrimmConfig, ScannedTypes` next to a public no-arg one) could not be built: `Cannot reflectively access io.vidocq.grimm.cdi.GrimmModelCache on the module path … does not open io.vidocq.grimm.cdi to module io.vidocq.vauban.core`.
- **Minimal reproduction**: `ComponentProviderCompileTimeTest#injectConstructorWinsOverNoArgConstructor`.
- **Cause**: the collector chose the no-arg constructor whenever one existed. The container picks the `@Inject` one (CDI 4.1 §3.1.1) and asks the provider for `create(name, args)`. The generated provider only had `create(name)`, so the default `create(name, args)` answered `null` and the container fell back to reflection. On an automatic module, which is open, this went unnoticed.
- **Fix**: the `@Inject` constructor comes first. A no-arg constructor is chosen only when there is no `@Inject` one.

## BUG-20261001-04 — A bean class that cannot be loaded is skipped without a word (grimm#15)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/inject-constructor-over-no-arg`)
- **Module**: `vauban-core` (`ContainerScanner#tryAddBeanClass`)
- **Symptom**: every Grimm bean vanished from a modular application (its package split into the application module), and nothing in the log said so.
- **Fix**: a class listed in a `META-INF/vauban-beans.list` that cannot be loaded is still skipped, but with a warning that names it and the likely causes: a split package, or a missing module. `ScanClasspathTest#shouldReportUnloadableListedClass`. Not a single one shows in the CDI TCK run.

## BUG-20261004-01 — `InvocationContext.getMethod()` returns the generated `$$super$` bridge for a method the bean class does not declare

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`2d85326`; resolution moved to `BusinessMethods` in `1165eb8`, private declarations skipped in `7202b86`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`VaubanInvocationContext#getMethod`)
- **Surfaced by**: a review of the MicroProfile 7.2 upgrade (Humboldt names spans and sets `code.function.name` from `getMethod()`).
- **Symptom**: an interceptor bound to a bean sees `ctx.getMethod()` as `<Bean>$$Intercepted.$$super$<name>` instead of the bean's method whenever the method is inherited: declared on the direct superclass, on any class above it, or as an interface default method. Every interceptor gets the wrong `Method` (wrong declaring class, `$$super$` name, no annotations), so `getInterceptorBindings()` also loses the method-level bindings of the inherited method. Both front-ends are affected: the run-time Class-File subclass and the processor's source subclass. Only a method the bean class declares itself was resolved.
- **Minimal reproduction**: `InheritedInterceptedMethodTest` (vauban-core, run-time subclass) and `InheritedMethodModulePathTest` (vauban-module-it, processor subclass on the module path): `C extends B extends A`, interceptor recording `ctx.getMethod()`. Before the fix, the vauban-core run had 8 tests, the six kept plus two temporary probes of an inherited protected and package-private method (since removed, see BUG-20261004-03): 6/8 failed, the four inherited cases with `getMethod() leaked the generated bridge` and the two probes with no interception at all. The vauban-module-it run failed 6/8 with `getMethod() leaked the generated bridge`.
- **Cause**: the generated subclass hands the context its `$$super$<name>` bridge, and `getMethod()` looked the original up with `getSuperclass().getDeclaredMethod(…)` on the bean class only, falling back to the bridge on `NoSuchMethodException`. `InterceptorManager` already walked the whole superclass chain to collect the method's bindings, so the chain was right and only the method reported to interceptors was wrong.
- **Fix**: `getMethod()` resolves the bridge up the whole superclass chain (most derived declaration first, any access), then to the interface default method, once per bridge: the result is kept per generated subclass in a `ClassValue`. Resolving at run time rather than capturing the declaring class in the generated code keeps every already-compiled `$$Intercepted` correct, and does not name a class that may be inaccessible from the bean's package or module. The walk now lives in `BusinessMethods`, which `InterceptorManager` shares, so the method reported and the bindings applied cannot diverge (BUG-20261004-04).

## BUG-20261004-02 — A client proxy does not forward an interface default method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`RuntimeClientProxyGenerator#shapeOf`), `vauban-processor` (`ClientProxyShapeFromElements#from`, `#fromColocated`)
- **Symptom**: calling an interface default method that a normal-scoped bean does not override, through its client proxy, runs the default body on the proxy instance: the contextual instance is bypassed and the interceptors bound to the bean do not fire. The bean's own methods are forwarded and intercepted as expected.
- **Minimal reproduction**: an `@ApplicationScoped @Audited` bean `implements Greeting` (default `greet`), no override; `select(…).greet("y")` returns `"hi y"` and the interceptor records nothing. Seen on both proxies while working on BUG-20261004-01 (run-time proxy in vauban-core, processor proxy in vauban-module-it); the regression tests of BUG-20261004-01 use a `@Dependent` bean to reach the generated subclass directly.
- **Cause**: all three shapes walk the superclass chain only; methods inherited from interfaces are never listed.
- **Fix**: the three shapes also forward the interface default methods no class of the bean overrides: `Class#getMethods()` filtered on `isDefault()` in `shapeOf`, `Elements#getAllMembers` filtered on `DEFAULT` in both processor shapes (as `InterfaceProxySourceRenderer` already did for an interface-typed proxy). The producer proxies built from the same shapes forward them too. Tests: `DefaultMethodInterceptionTest#defaultMethodThroughTheClientProxy` (vauban-core), `InheritedBindingModulePathTest#defaultMethodThroughTheClientProxy` (vauban-module-it), `ClientProxyInheritanceCrossCheckTest#defaultMethodsAreForwarded` (vauban-processor: all three shapes, an overridden default and a default re-declared by a sub-interface). The indexer-based `ClientProxyGenerator` fallback (beans with no non-private constructor, unproxyable per CDI 4.1 §3.10) still overrides declared methods only.
- **Regression of this fix, found by the round-4 re-review, fixed in `d6023f5` (never on `main`)**: a normal-scoped bean inheriting a default whose member signature names a type its package cannot — `label(Hidden)` from `implements Labeled<Hidden>` in a superclass of package `q`, `Hidden` package-private there, no shadow — got a rendered `_ClientProxy` that does not compile (`Hidden is not public in q`), where `main`, which forwarded no default, compiled it. The rendered proxy now leaves such a method out (`ClientProxyShapeFromElements`, `InterceptedShapeFromElements#memberSignatureNameableFrom`), which is `main`'s outcome — the default runs on the proxy instance — and the processor reports it as a warning on the bean; the bytecode shapes keep forwarding it by descriptor (pinned as a documented divergence). A rendered subclass with the same member is BUG-20261004-09. Tests: `ClientProxyInheritanceCrossCheckTest#nonShadowedDefaultWithAnInaccessibleMemberType` (RED `fv2r4-r5red-processor.log`), `InaccessibleMemberTypeModulePathTest` (RED `fv2r4-r5red-moduleit.log`: vauban-module-it did not compile).

## BUG-20261004-03 — The run-time intercepted subclass skips an inherited protected or package-private method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; cross-package behaviour test `9fe86da`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorSubclassGenerator#fromClass`, also used by the Maven plugin's `VaubanGenerator`)
- **Symptom**: a protected or package-private business method that the bean inherits from a superclass is not intercepted when the `$$Intercepted` subclass comes from the run-time generator (class-path fallback, Maven plugin). The processor's subclass intercepts it (`InterceptedShapeFromElements` lists every member through `Elements#getAllMembers`), so the two front-ends do not produce the same override set, although both are documented to.
- **Minimal reproduction**: a `@Dependent @Traced` bean `extends Parent extends Grandparent`, where `Grandparent` declares `protected String inheritedProtected()` and `String inheritedPackagePrivate()`; calling either from the same package reaches no interceptor, while the public inherited method is intercepted. Seen while working on BUG-20261004-01.
- **Cause**: `fromClass` takes the bean's declared methods, then only the inherited *public* ones (`Class#getMethods()`).
- **Fix**: `fromClass` then walks the superclasses for the inherited protected and package-private methods, with the rules `Elements#getAllMembers` applies (JLS 8.4.8): the most derived declaration of a signature decides, so a `final` or `private` one hides the rest, and a package-private method counts only when every class from the bean up to its declaring class shares that class's runtime package — no subclass elsewhere can override it. They are appended after the existing passes, so the shape of a bean without such methods is unchanged (`GoldenBytecodeTest`). Tests: `InheritedInterceptedMethodTest#inheritedProtected` and `#inheritedPackagePrivate` (vauban-core, through the run-time client proxy and subclass), `#inheritedProtectedFromAnotherPackage` (added by the review: a protected method of a superclass in another package, through the run-time subclass; RED with the new pass disabled, `fv2r1-red-foreign-protected.log`), `InterceptedShapeFromElementsTest#inheritedNonPublicMethodsAgree` (vauban-processor: both front-ends select the same set, including a protected and a package-private method of a superclass in another package and a `final` override).

## BUG-20261004-04 — An interceptor bound to an interface default method never runs, while `getInterceptorBindings()` lists its binding

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; review follow-ups `7202b86` (private declarations), `2f56400` (business methods only), branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorManager#matchingForMethod`, `#resolveInterceptorDescriptorsForMethod`, `InterceptorBeanWrapper#wrapInterceptedBeans`)
- **Surfaced by**: the review of the BUG-20261004-01 fix, which made `getMethod()` — and with it `getInterceptorBindings()` — answer the interface default method.
- **Symptom**: an interceptor binding declared on an interface default method the bean does not override is listed by `getInterceptorBindings()`, but the interceptor bound to it does not run: the chain ignores it. A bean whose only binding sits on such a method is not intercepted at all.
- **Minimal reproduction**: `interface Greeter { @Marked default String markedGreet(String w) {…} }`, a `@Traced @Dependent` bean implementing it, a `@Marked` interceptor: `markedGreet("z")` runs the `@Traced` interceptor, which sees `@Marked` in its bindings, but not the `@Marked` one. `DefaultMethodInterceptionTest#defaultMethodBindingApplies` and `#beanBoundOnlyByADefaultMethod` (vauban-core) failed before the fix.
- **Decision**: the binding applies. CDI 4.1 does not mention default methods; it gives the bindings of inherited methods for superclasses (§4.2: inherited unless a class below overrides the method) and never inherits type-level metadata from interfaces (§4). A default method the bean does not override is a member of the bean class (JLS 8.4.8) exactly as a non-overridden superclass method is, so its own bindings apply by the same rule, and stop applying once a class of the bean overrides it. Weld does the same: its annotated type lists the interface default methods next to the class hierarchy's methods (`BackedAnnotatedType`), and the interception model reads each business method's own bindings (`InterceptionModelInitializer`). The class-level bindings of an interface still never apply.
- **Cause**: `InterceptorManager` resolved the method's declaration on the superclass chain only, while `getMethod()` also fell back to the interface default method; the bean wrapper looked for method-level bindings on the superclass chain only, and resolved each method from its declaring class instead of the bean class.
- **Fix**: one helper, `BusinessMethods#declarationOf`, gives the declaration a call runs on an instance of the bean class — the most derived one up the superclass chain, else the inherited default method — and the method-level bindings are read off it. `VaubanInvocationContext#getMethod()` (hence `getInterceptorBindings()`), the chain and the wrapper's "is this bean intercepted" check all use it; the wrapper now also checks the inherited default methods. Tests: `DefaultMethodInterceptionTest` (vauban-core, five cases including an overridden default method that drops its binding), `InheritedInterceptedMethodTest#overriddenMethodDropsTheSuperclassBinding` and `#overloadOnTheGrandparent`/`#overloadOnTheParent`, `InheritedBindingModulePathTest` (vauban-module-it).
- **Review follow-up, same day**: `declarationOf` took the most derived declaration "whatever its access", so a private superclass method with the signature of an inherited default method stood for it: `getMethod()` answered the private method and the bindings were read from it, while the bean's member is the default method (JLS 8.4.8). It now skips private and static declarations. Test: `DefaultMethodInterceptionTest#privateSuperclassMethodDoesNotShadowTheDefault` (RED `fv2r1-red-private-shadow-2.log`: `getMethod()` was `private PrivateHidden.hidden`). The call itself failed in that shape until BUG-20261004-08 was fixed. Since `54e5c9c` it skips every declaration that is not a business method of the bean (`isBusinessMethodOf`): a package-private one of another package too.

## BUG-20261004-05 — On the module path, a bean bound only through an inherited method cannot be deployed

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; review follow-up `2f56400`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-processor` (`VaubanProcessor#isInterceptedTarget`)
- **Surfaced by**: the tests of BUG-20261004-04.
- **Symptom**: a bean with no class-level binding whose only binding sits on a method it inherits — a superclass method, or (since BUG-20261004-04) an interface default method — gets no `$$Intercepted` subclass from the processor. On the strict module path the container then has to define the subclass itself and cannot (no `opens`): `DeploymentException: Could not define interceptor subclass`. On the class path the run-time fallback hid it.
- **Minimal reproduction**: `@Dependent class InheritedBindingService extends BoundBase`, `BoundBase` declaring `@Audited public String inheritedBound()`; booting a container with it in vauban-module-it throws the exception above. `InheritedBindingModulePathTest#inheritedMethodBindingApplies` and `#defaultMethodBindingApplies` failed before the fix.
- **Cause**: `isInterceptedTarget` looked for method-level bindings on the methods the bean class declares only.
- **Fix**: it also looks at the methods the bean inherits, as `Elements#getAllMembers` lists them: a method a class of the bean overrides is left out, so its binding does not count (CDI 4.1 §4.2). A supertype the compiler cannot complete leaves the bean to the run-time fallback, with a note, as before. A bean bound through a *generic* superclass method also needed BUG-20261004-06.
- **Review follow-up, same day — the reverse mismatch**: the container's "is this bean intercepted" check still counted a binding on a method that is not a business method of the bean — private, static, or package-private in a superclass of another package, which the bean does not inherit — while the processor (`getAllMembers`, `hasMethodLevelBinding`) does not. On the module path such a bean was wrapped without a pre-generated subclass: `DeploymentException: Could not define interceptor subclass` again. `BusinessMethods#isBusinessMethodOf` (non-static, non-private, inherited per JLS 8.4.8) now gates the method-level bindings in `InterceptorManager`, so the check, the chain and `InterceptorSubclassGenerator` count the same methods as the processor. Tests: `NonBusinessMethodBindingTest` (vauban-core, RED `fv2r1-red-business.log`: the foreign package-private case was wrapped) and `NonBusinessMethodBindingModulePathTest` (vauban-module-it, RED `fv2r1-red-business-moduleit.log`: the `DeploymentException`); the private and static cases already passed once BUG-20261004-04's follow-up made `declarationOf` skip private and static declarations, and are pinned.

## BUG-20261004-06 — The processor's generated sources do not compile when the bean binds a type variable of an inherited method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1165eb8`; review follow-ups `982bfa1` (one override per member signature), `ae477c2` (generic bean extended raw), branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-processor` (`InterceptedShapeFromElements`, `InterceptedSourceRenderer`, `ClientProxyShapeFromElements#from`), `vauban-core` (`MethodShape`)
- **Surfaced by**: a probe written while fixing BUG-20261004-02 and -05, which would otherwise have widened it (`fv2-probe-generic.log`, probe files deleted).
- **Symptom**: a bean that inherits a method whose parameter or return type is a type variable its supertype binds — `echo(T)` of `class Bean extends Base<String>`, or a default `label(T)` of `implements Labeled<String>` — breaks the build: the `$$Intercepted` source the processor renders for an intercepted bean, and the `_ClientProxy` source it renders for a normal-scoped bean, declare `echo(java.lang.Object)`, which javac rejects (`name clash: echo(Object) … and echo(String) … have the same erasure, yet neither overrides the other`, then `incompatible types`). Pre-existing for generic superclass methods on both sources and for generic default methods on the `$$Intercepted` source; BUG-20261004-02 would have added the default methods of the client proxy, and BUG-20261004-05 every bean bound through a generic base-class method (a repository bound through its base class), which until then fell back to the run-time generator and worked on the class path.
- **Minimal reproduction**: `GenericInheritanceModulePathTest` with `GenericScopedService` (`@ApplicationScoped @Audited extends GenericBase<String> implements Labeled<String>`) and `GenericBoundService` (`@Dependent extends GenericBase<String>`, bound only through `@Audited store(T)`): vauban-module-it did not compile (`fv2-red-generic-moduleit.log`).
- **Cause**: the source front-ends render each method with the erasure of its declaration, which is the right descriptor for a class file (the bytecode emitters override by descriptor) but not an override Java source accepts when the bean sees the type variable as a concrete type.
- **Fix**: `MethodShape` also carries the method's signature as a member of the bean (`Types#asMemberOf`, erased), identical to the declaration unless a type variable is bound. `InterceptedSourceRenderer` declares the override with it (javac adds the bridge for the descriptor) and keeps the `$$super$` bridge on the erased declaration — the run-time front-end's descriptor, which `getMethod()` resolves to the declaration — casting each argument to its member type for `super.<name>(…)`. `ClientProxyShapeFromElements#from`, rendered as source only, uses the member signature; `#fromColocated` and the run-time shapes, emitted as bytecode, keep the descriptor. Tests: `GenericInheritanceModulePathTest` (four cases: a generic superclass method and a generic default method through the build-time client proxy, a generic return type, a bean bound only through a generic superclass method; the interceptor sees the erased declaration), `ClientProxyInheritanceCrossCheckTest#genericMethodsUseTheMemberSignatureInSource`.
- **Reopened by the review, same day**: the source front-ends still keyed their walks on the erased *declaration*, then rendered the *member* signature, so two declarations that are one member of the bean were rendered twice: `method echo(String) is already defined` in the client proxy of `class OverridingBean extends GenericBase<String> { @Override echo(String) }` (`echo(T)` keyed as `echo(Object)`), and `method label(String) is already defined` in the `$$Intercepted` of `class X extends PlainBase implements Labeled<String>` whose `PlainBase.label(String)` implements `label(T)` (`getAllMembers` lists both). The second shape was already a name clash before. Reproduced by `GenericInheritanceModulePathTest#overriddenGenericMethodThroughTheClientProxy` and `#interfaceMethodImplementedBySuperclass` (vauban-module-it did not compile, `fv2r1-red-dedupe-moduleit.log`), `ClientProxyInheritanceCrossCheckTest#oneOverridePerMemberSignature` and `InterceptedShapeFromElementsTest#oneMethodPerMemberSignature`.
- **Fix, second part**: every APT walk — `InterceptedShapeFromElements#from`, `ClientProxyShapeFromElements#from`, `#fromColocated` and the default methods — is keyed on the erased *member* signature (`InterceptedShapeFromElements#memberSignatureKey`), the most derived declaration first, class methods before interface default methods, a non-interceptable or non-proxyable declaration still hiding the ones it overrides. The cross-check helpers now fail on a signature listed twice.
- **Regression of the first fix on generic beans, found by the branch review, fixed** (`ae477c2`, never on `main`): the member signature was computed against the parameterised bean, while the rendered subclass and proxy extend a generic bean *raw*, where every inherited member is erased (JLS 4.8). `@Dependent @Audited class GenericNoShadow<X> implements Labeled<String>` got an override `label(String)` that "does not override or implement a method from a supertype"; `main` rendered `label(Object)` and compiled. `InterceptedShapeFromElements#memberType` now takes the member as of the raw type when the bean is generic, which restores `main`'s output for every generic bean and keeps the bound member for a non-generic one. Tests: `InterceptedShapeFromElementsTest#genericBeanIsExtendedRaw` (member signatures), `ClientProxyInheritanceCrossCheckTest#genericBeanIsExtendedRaw`, `GenericInheritanceModulePathTest#genericBeanIsExtendedRaw` and `ShadowedDefaultModulePathTest#genericBeanWithAShadowedDefault` (vauban-module-it did not compile: `fv2r4-red-moduleit.log`).

## BUG-20261004-07 — An unproxyable intercepted bean is not reported as a deployment problem, nor told why it is intercepted

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`6c1952b`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorBeanWrapper#wrapInterceptedBeans`)
- **Surfaced by**: the review of the FV2 fixes, which make more beans intercepted (bound through an inherited or default method).
- **Symptom**: a final intercepted bean class failed with a `DefinitionException`, while CDI 4.1 treats an unproxyable intercepted bean as a deployment problem (§3.10, §8.3) and the final-method case already threw a `DeploymentException`. None of the three proxyability errors (final class, final method, private no-arg constructor) said why the bean is intercepted, which is no help when the binding sits on a method the bean inherits: its own source carries none.
- **Minimal reproduction**: `UnproxyableInterceptedBeanTest` (vauban-core): a final `@Dependent` bean bound only through `@Marked` on an inherited default method threw `DefinitionException`; a bean with a final method bound only through `@Marked` on a superclass method threw `Intercepted bean … has final method pinned`, naming neither (RED `fv2r1-red-unproxyable.log`).
- **Fix**: the "is this bean intercepted" check keeps the reason it found — the class-level bindings, `InterceptorManager#describeMethodBindings` (the bindings of the declaration and whether it is declared, inherited or an interface default method), the constructor binding, or the target `@AroundInvoke` method — and every proxyability error is a `DeploymentException` that ends with "An intercepted bean must be proxyable (CDI 4.1 §3.10); <bean> is intercepted because of …".

## BUG-20261004-08 — A default method shadowed by a private superclass method cannot be called through the generated classes

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`1de9e00`; re-review fixes `016858e`, `3caac57`, `1e3a925`; branch-review fixes `54e5c9c`, `abe4c2c`, `d3fa351`, branch `pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`ShadowedDefaults`, `InterceptorSubclassGenerator`, `InterceptedEmitter`, `RuntimeClientProxyGenerator`, `ClientProxyEmitter`, `MethodShape`, `ClientProxyShape`), `vauban-processor` (`InterceptedShapeFromElements`, `InterceptedSourceRenderer`, `ClientProxyShapeFromElements`, `ClientProxySourceRenderer`)
- **Surfaced by**: the review follow-up on `BusinessMethods#declarationOf` (BUG-20261004-04).
- **Symptom**: `class C extends Base implements I`, where `I` has a default `m()` and `Base` a *private* `m()` with the same signature. `C`'s member is `I.m()` (JLS 8.4.8), but once `C` is intercepted a call through `I` ended in `IllegalAccessError: C$$Intercepted tried to access private method Base.m()`: the interceptors ran, then the `$$super$m` bridge's `super.m()` failed. Normal-scoped, the client proxy did not forward `m()` (the private method held its key), and a call on the proxy ended in `AbstractMethodError`.
- **Minimal reproduction**: `DefaultMethodInterceptionTest#privateSuperclassMethodDoesNotShadowTheDefault` and `#shadowedDefaultThroughTheClientProxy` (vauban-core, run-time front-ends), `ShadowedDefaultModulePathTest` (vauban-module-it, processor front-ends): RED in `fv2r2-red-shadowed.log`.
- **Cause**: the JVM resolves `invokespecial C.m` — the bridge's `super.m()` — and `invokevirtual C.m` through `C`'s superclasses before its superinterfaces (JVMS 5.4.3.3), finds the private `Base.m()` and refuses it: a class-typed call from outside `Base`'s nest fails the same way in plain Java (observed). HotSpot 25 also gives `AbstractMethodError` on an interface call to any class that inherits `I` only through its superclass under such a shadow (`class D extends C`; observed with plain javac classes, although JVMS 5.4.6 selects the default there): a generated subclass or proxy that does not override `m()` cannot run it either.
- **Fix**: generated code reaches the default through an interface, never through the bean class. `ShadowedDefaults` (run time) and `InterceptedShapeFromElements#isShadowedDefault`/`#accessibleDefaultOwner` (processor) detect a default method whose name and descriptor a class of the bean's chain declares, and pick its owner: the declaring interface when the generated class can name it, else an interface among the bean's supertypes that inherits it and can be named. The `$$Intercepted` subclass lists the owner among its direct superinterfaces — `invokespecial` on an interface method must name one (JVMS 4.9.2) — and its bridge and pre-init guard invoke the default explicitly: an `invokespecial` on the interface method in the bytecode emitter; in the rendered source a `MethodHandles.lookup().findSpecial(I.class, …)` handle, because `I.super.m()` is a compile error there: JLS 15.12.1 forbids it when the class's superclass (the bean) is itself a subtype of `I` ("redundant interface I is extended by C"). The client proxies forward through the owner (`invokeinterface`; `((I) delegate).m(…)` in source). `MethodShape#defaultOwner` and `ProxyMethodShape#interfaceOwner` carry it.
- **Fallback, tested**: when no interface carrying the default can be named from the bean's package — the bean inherits it only from a package-private interface of another package, through a superclass — no class may list it (JVMS 5.4.4 for the run-time subclass, JLS 6.6.1 for the rendered one), so the method is left out of the subclass and the proxy, and is not intercepted. A call then behaves as on a plain instance of the bean, which on HotSpot 25 is the `AbstractMethodError` above. Tests: `InaccessibleDefaultOwnerTest` (vauban-core: the bean's other methods are intercepted, `carried()` behaves as on a plain instance), `InterceptedShapeFromElementsTest#shadowedDefaultMethods` and `ClientProxyInheritanceCrossCheckTest#shadowedDefaultMethods` (vauban-processor: both front-ends agree on the owner, and on leaving the inaccessible case out).
- **Regression found by the re-review, fixed**: `isShadowedDefault` took *any* class declaration with the default's descriptor for a shadow, a public inherited one included, against its own Javadoc. In `@ApplicationScoped class TaggedScoped extends PlainTagBase implements Tagged`, where `PlainTagBase.tag(String)` (public) implements the default `Tagged.tag(String)`, the processor's client proxy forwarded `tag(String)` through the bean class *and* through `Tagged`: `method tag(String) is already defined`. Only a declaration that is not a member of the bean — private, or package-private in another package (JLS 8.4.8) — shadows now, at run time (`ShadowedDefaults#isShadowed`) and in the processor (`InterceptedShapeFromElements#isShadowedDefault`); the processor's proxy shapes (`from` and `fromColocated`) never declare a signature twice, whatever path finds it, and the cross-check helper fails on a signature listed twice whatever its `#mh`/`@owner` suffix. Tests: `ClientProxyInheritanceCrossCheckTest#inheritedClassMethodIsNoShadow`, `InterceptedShapeFromElementsTest#inheritedClassMethodIsNoShadow`, `ShadowedDefaultModulePathTest#inheritedClassMethodIsNoShadow` (RED `fv2r3-red-noshadow-*.log`: the duplicate, and vauban-module-it not compiling).
- **Generic interface, found by the re-review, fixed**: a shadowed default of a generic interface — `class GenericShadowed extends PrivBase implements Labeled<String>`, `PrivBase` declaring a private `label(Object)` — made the processor render a raw `implements Labeled` next to the bean's `Labeled<String>`: "Labeled cannot be inherited with different arguments: <> and <java.lang.String>". `MethodShape#defaultOwnerSource` now carries the interface as the bean parameterises it, taken from the bean's supertypes (erased for a generic bean, which its subclass extends raw), and `InterceptedSourceRenderer`, the only renderer that lists the interface, writes it so (`implements Labeled<java.lang.String>`); the `findSpecial` class literal stays erased. The proxies only cast to the interface, which needs no type arguments. Tests: `InterceptedSourceRendererTest#parameterisedOwner`, `ShadowedDefaultModulePathTest#genericInterface` (RED `fv2r3-red-generic-owner-*.log`).
- **Producer proxies (raised as a concern, fixed)**: a producer's proxy is rendered in the *producer's* package (#42), where a package-private interface of the produced type's package cannot be named, yet the owner was chosen as seen from the produced type's package: a build break. The processor now chooses the owner as seen from the package the proxy is rendered in (`ClientProxyShapeFromElements#from(bean, proxyPackage, …)`, `InterceptedShapeFromElements#accessibleDefaultOwner(…, generatedIn, …)`; an unknown package asks for an interface public and exported to all), like the run-time producer path, which takes public exported interfaces only. When none can be named there, the method is not forwarded through an interface — not forwarded at all, as when no interface qualifies — instead of breaking the build. Test: `ClientProxyInheritanceCrossCheckTest#shadowedDefaultFromAnotherPackage` (RED `fv2r3-red-producer-package.log`: the shape forwarded through the package-private interface).
- **Shadow by a package-private method of another package, run-time proxy (regression of `1de9e00`, found by the branch review, fixed in `54e5c9c`, never on `main`)**: for `@ApplicationScoped class C extends q.PkgBase implements Tagged`, where `PkgBase.tag(String)` is package-private in another package and `Tagged` has a default `tag(String)`, `RuntimeClientProxyGenerator#shapeOf` forwarded `PkgBase.tag` through a MethodHandle — a method the bean does not inherit (JLS 8.4.8), and a lookup the JVM refuses — then forwarded the default through `Tagged` as well: `ClassFormatError: Duplicate method name "tag"` when the proxy was defined. `BusinessMethods#declarationOf` also stood that declaration for the default, so `getMethod()` and the bindings named `PkgBase.tag`. The processor's shapes already left the method out; the run-time walk now takes the same decision through the bean-member rule — first made public as `BusinessMethods#isBusinessMethodOf`, it now lives in `BeanMembers#isBusinessMethodOf` (`io.vidocq.vauban.core.codegen`, exported to the processor only, `ff80960`), and `BusinessMethods`, package-private again, delegates to it — and `declarationOf` skips it too. Tests: `CrossPackageShadowedDefaultTest` (vauban-core, RED `fv2r4-red-core.log`: the `ClassFormatError`, and `getMethod()` naming `ForeignPackagePrivateTagBase.tag`), `ClientProxyInheritanceCrossCheckTest#crossPackagePackagePrivateIsNoMember` (RED `fv2r4-red-processor.log`: the run-time shape listing `tag(String)` twice), `InterceptedShapeFromElementsTest#crossPackagePackagePrivateIsNoMember`, `ShadowedDefaultModulePathTest#crossPackagePackagePrivateShadow`.
- **Type argument the bean's package cannot name (found by the branch review, fixed in `abe4c2c`)**: `class HiddenArgBean extends q.Base`, where `Base implements Labeled<Hidden>` with `Hidden` package-private in `q`, under a private `label(Object)`: the rendered subclass wrote `implements q.Labeled<q.Hidden>` and the override `label(q.Hidden)` — the member signature — neither of which Java source in the bean's package may write: a build break. As when no interface qualifies, the method is now left out of the rendered subclass (not intercepted) and of the rendered client proxy (not forwarded) when the member signature, or the interface as the bean parameterises it, names a type the generated class's package cannot (`InterceptedShapeFromElements#memberSignatureNameableFrom`, `#nameableFrom(TypeMirror, …)`). The bytecode generators keep the method: a class file overrides by descriptor and lists the raw interface, which the cross-checks pin as the one documented divergence. Tests: `InterceptedShapeFromElementsTest#shadowedDefaultWithAnInaccessibleTypeArgument`, `ClientProxyInheritanceCrossCheckTest#shadowedDefaultWithAnInaccessibleTypeArgument`, `ShadowedDefaultModulePathTest#inaccessibleTypeArgument` (RED `fv2r4-red-moduleit.log`: `HiddenArgument is not public in io.vidocq.vauban.moduleit`).
- **Known limitation, not fixed — a non-member declaration with the default's name and parameters but another return type**: `class C extends Base implements Tagged`, where `Base` declares a private `Integer tag(String)` (or a package-private one in another package) and `Tagged` a default `String tag(String)`. Both proxy walks key on name and parameter types and the non-member declaration takes the key first (`RuntimeClientProxyGenerator#shapeOf`, `ClientProxyShapeFromElements#from`/`#fromColocated`), while `ShadowedDefaults#isShadowed` and `InterceptedShapeFromElements#isShadowedDefault` compare the whole descriptor, return type included, so the default is neither shadowed nor forwarded: a call on the client proxy runs the default on the proxy instance and is not intercepted, on both paths alike (the generated subclass of a dependent bean does intercept it). Found by the round-4 re-review (probe `n1`: `PrivRetScoped`, `CrossRetScoped`). Not a regression: no client proxy forwarded a default before BUG-20261004-02. Fix direction: record the key only after the member check — a declaration that is not a member of the bean then no longer holds it, and the default is forwarded through the bean class like any other inherited default. That is enough: `ShadowedDefaults#isShadowed` and `InterceptedShapeFromElements#isShadowedDefault` must keep comparing the return type, because the JVM resolves a call by its full descriptor, so a private `Integer tag(String)` is never what `invokevirtual C.tag:(Ljava/lang/String;)Ljava/lang/String;` reaches, and is no shadow.
- **Readability of the interface's module (found by the branch review, fixed in `d3fa351`)**: the processor checked that the interface's module *exports* its package, not that the bean's module *reads* that module (JLS 7.7.1). A bean inheriting the interface through a superclass of another module, with no `requires` of the interface's module and no `requires transitive` along the way, cannot name it: "module m.bean does not read module m.iface". `nameableFrom` now walks the bean's module's `requires`, following the transitive ones (`java.base` and the unnamed module always read). The run time already asked `Module#canRead`. Test: `DefaultOwnerReadabilityTest` (three Java modules compiled in-process; RED `fv2r4-red-readability.log` with the check disabled).

## BUG-20261004-09 — The processor's generated sources do not compile when an inherited member's signature names a type the bean's package cannot

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-07 (vauban#120, branch `pr/ybl/unnameable-inherited-member-types`): `n3a`, `n3b`, `n3d`, `n11b`, `n11c`, see **Fix** below. Pre-existing on `main` 7384ac1; found by the round-4 re-review of `pr/ybl/inherited-interceptor-method`, probes `n3a`, `n3b`, `n3d`. The shapes that were a regression of that branch (`n11a`, `n11d`) were FIXED 2026-10-04 (`ee46fc4`), with `n11e`. What stays as it was is listed under **Left as is**.
- **Module**: `vauban-processor` (`InterceptedShapeFromElements`, `InterceptedSourceRenderer`, `ClientProxyShapeFromElements`, `ClientProxySourceRenderer`); for `n3b` also `vauban-core` (`InterceptedEmitter`, the run-time subclass)
- **Symptom**: a bean inherits a member whose parameter or return type is a type its own package cannot name — a package-private class of the superclass's package — and the processor renders an override of it: the build breaks. Three shapes, with `q.Hidden` package-private in `q`:
  - `n3a`: `@Dependent @Audited class Bean extends q.HiddenBase`, `HiddenBase implements Labeled<Hidden>` (default `label(T)`, no shadow): `Bean$$Intercepted` declares `label(q.Hidden)` (on `main`, the erased `label(Object)`: a name clash with `label(Hidden)`) — "Hidden is not public in q" / "name clash … have the same erasure, yet neither overrides the other";
  - `n3b`: `@Dependent @Audited class Bean extends q.TakesHidden`, `TakesHidden` declaring `public String take(Hidden h)`: `Bean$$Intercepted` declares `take(q.Hidden)` — "Hidden is not public in q";
  - `n3d`: the same `TakesHidden` superclass under an `@ApplicationScoped` bean: `Bean_ClientProxy` declares `take(q.Hidden)` — same error.
  - The fourth shape, `n3c` (a normal-scoped bean over the `Labeled<Hidden>` default), compiled on `main` because no client proxy forwarded a default then; the branch's BUG-20261004-02 fix made it a build break, which was fixed on the branch: the rendered proxy leaves the method out, with a warning (`ClientProxyInheritanceCrossCheckTest#nonShadowedDefaultWithAnInaccessibleMemberType`, `InaccessibleMemberTypeModulePathTest`).
- **The run-time generators on the same shapes** (probe `n7` of the round-5 re-review: the three beans compiled with `-proc:none`, interceptor enabled; `main` and the branch behave alike):
  - `n3a` works: the run-time `$$Intercepted` overrides `label(Ljava/lang/Object;)` — the hidden type erases away — and the call is intercepted;
  - `n3d` works: the run-time client proxy forwards `take(Lq/Hidden;)` by descriptor and the call reaches the contextual instance;
  - `n3b` does not: every call of `take` on the run-time `$$Intercepted` throws `IllegalAccessError: failed to access class q.Hidden from class …$$Intercepted`.
- **Cause**: Java source can only override a method with its signature as a member of the bean, and that signature may name a type the bean's package cannot (JLS 6.6.1): `n3a`, `n3b`, `n3d`. A class file can carry that type in a method descriptor — the JVM does not check access to the classes a descriptor names, which is why the run-time client proxy handles `n3d` — but it does check access whenever it resolves a class constant (JVMS 5.4.3.1, 5.4.4). The run-time `$$Intercepted` resolves one for every reference parameter of an intercepted method: `ldc` of the parameter class to look its `$$super$` bridge up with `getDeclaredMethod` (`InterceptedEmitter.java:272`), and `checkcast` of each argument in the invoker (`:406`). In `n3a` that class is `Object`; in `n3b` it is `q.Hidden`, hence the `IllegalAccessError` at the first call. By the same rule a hidden reference return type would fail at the `checkcast` of the returned value (`:363`); not probed.
- **Fix direction — needs design.** Constraints:
  - Leaving such a bean to the run-time generators, as the processor does for a supertype it cannot complete, fixes `n3d` and `n3a` only. For `n3b` it turns the build break into an `IllegalAccessError` on every call, and on the strict module path the run-time definition also needs the bean's package opened to `io.vidocq.vauban.core` (BUG-20261004-05).
  - `n3b` needs the bytecode emitter to stop resolving a class the subclass cannot access. It would get the bridge's parameter classes without a class constant: `Class.forName(name, false, loader)` from the bean's loader, which checks no access, or the parameter types of the superclass method `getDeclaredMethods` returns. It would also pass such an argument, and such a return value, without the `checkcast` the verifier otherwise requires, for instance through a `MethodHandle` adapted to `Object` with `asType`: a `MethodType` may hold a class the caller cannot access.
  - The processor cannot render that class as source at all. It can emit it as bytecode with such an emitter, but the source `_VaubanComponents` provider instantiates the subclass by name and must still reach it. Or it can leave the method out with a warning, as for a shadowed default with no nameable interface (BUG-20261004-08): the build then passes, but a class method of the bean is not intercepted, and only the warning says so.
- **Same family — a private nested type of the bean's own package** (found by the final review of the branch; probe `n11` of its fix wave, against the round-5 exports of `main` 7384ac1 and of `00c22c8`, and after the fix against the working tree): `InterceptedShapeFromElements#nameableFrom(TypeElement, PackageElement, Elements)` answered "nameable" for any type of the generated class's package before it looked at the enclosing types. A `private` nested type of a top-level class of that package passed, although a generated top-level class cannot name it (JLS 6.6.1). With `p11.Outer` declaring `private static class Hidden`, `public static class LabeledBase implements Labeled<Hidden>`, `public static class TakesBase { public String take(Hidden h) }` and `public static class ShadowedLabeledBase extends PrivLabel implements Labeled<Hidden>` (`PrivLabel` declaring a private `label(Object)`):
  - **FIXED 2026-10-04 (`ee46fc4`)**: in the same package the check now refuses a private type or a type nested in one. Each shape below compiles again, with the processor's warning, and its generated sources are byte-identical to `main`'s:
    - `n11a`, `@ApplicationScoped class ScopedLabeled extends Outer.LabeledBase` (the default `label(Outer.Hidden)` through the client proxy). It compiled on `main`, whose proxies forwarded no default. Before the fix the branch's rendered `_ClientProxy` declared `label(p11.Outer.Hidden)` — "Hidden has private access in Outer" — a regression of BUG-20261004-02 that the guard of `d6023f5` missed. Now the default is left out of the proxy, as for `n3c`, and runs on the proxy instance.
    - `n11d`, the same shape through a producer's proxy rendered in the produced type's own package (`@Produces @ApplicationScoped Produced make()`, `Produced extends Outer.LabeledBase`): the same regression and the same fix. The warning sits on the producer method.
    - `n11e`, the shadowed default of `ShadowedLabeledBase`: its client proxy now leaves it out, and its `$$Intercepted` (`@Dependent @Marked`) leaves it out too. That subclass is not `main`'s, which did not compile (name clash).
  - Tests: `ClientProxyInheritanceCrossCheckTest#nonShadowedDefaultWithAPrivateNestedMemberType` and `InterceptedShapeFromElementsTest#shadowedDefaultWithAPrivateNestedTypeArgument` (RED `final2-vauban-n11a-red-processor.log`); `InaccessibleMemberTypeModulePathTest#privateNestedDefaultLeftAlone` (RED `final2-vauban-n11a-red-moduleit.log`: vauban-module-it did not compile).
  - **Still OPEN**, pre-existing: `n11b` (client proxy over `take(Outer.Hidden)`) and `n11c` (a `@Dependent` bean with a class-level binding, over the non-shadowed default) break the build on `main` and on the branch alike. The branch reports "Hidden has private access in Outer"; on `main`, `n11b` fails the same way and `n11c` with a name clash. These are the class-method and subclass shapes of this entry, which the nameability check does not cover: for a class method the processor does not check names at all, and the subclass checks names for a shadowed default only.
  - Divergences left by the fix. Every processor shape picks the owner of a shadowed default with this check, and `ShadowedDefaults#accessible` accepts any interface of the bean's package and loader: a class file gives a private nested interface package access. So when the only interface carrying the default is a private nested one of the bean's package, the processor's shapes no longer use it and the run-time ones still do:
    - client proxy: the co-located (bytecode) proxy no longer forwards through it, the run-time proxy still does. This is from the code, not probed. `main` forwarded no default on either path.
    - intercepted subclass: the processor's subclass leaves the method out (`n14` of the re-review of the fix wave: `class Bean extends Outer.Mid`, `Mid extends Shadow implements PI`, `PI` a private nested interface with a default `m()`, `Shadow` with a private `m()`), so a call throws `AbstractMethodError` on HotSpot 25. The run-time subclass lists `Outer$PI` and intercepts `m()`. On `main` both paths threw `IllegalAccessError`. Pinned by `InterceptedShapeFromElementsTest#omittedDefaultReportsCompareTheGenerators` (`PrivateCarrierBean`), and the subclass warning says that the bytecode generators decide by class-file access (`8710532`).
  - **Recorded, not changed — a protected nested type of a superclass in another package** (nit of the re-review of the fix wave, probes `n12h`, `n12x`): for another package, `nameableFrom(TypeElement, …)` requires `public` through every enclosing type. A `protected` nested type of a superclass of another package therefore counts as unnameable, although the generated class, a subclass of that superclass, may name it (javac accepts `label(q12.QBase.Prot)` in such a proxy, `n12x`). A non-shadowed default whose member signature names it is not forwarded, with a warning that says the package "cannot" name the type (`n12h`). That output is byte-identical to `main`'s, which forwarded no default, so nothing that worked is lost. A fix would accept a `protected` nested type when the generated class extends its enclosing class.
- **Fix (2026-10-07, vauban#120)**. Java source cannot write these overrides, and the bytecode generators can, so the processor now emits the class Java source cannot render as bytecode, with the same emitters the run-time path and the Maven plugin use. Everything else is rendered as source, as before.
  - **The decision.** `InterceptedShapeFromElements#from(…, omitted, unnameable)` reports each method it keeps whose signature as a member of the bean names a type the bean's package cannot name: a non-shadowed default or a class method (a shadowed default in that case is still left out, BUG-20261004-08). `ClientProxyShapeFromElements#unnameableForwards` does the same for the class methods a source proxy forwards and for the non-shadowed defaults (`n3c`, `n11a`, see below). When either list is not empty, `VaubanProcessor` writes the subclass with `InterceptedEmitter#emit` and the proxy with `ClientProxyEmitter#emit`/`#emitAt` from `ClientProxyShapeFromElements#fromErased`/`#fromProducedErased`, the erased shape of `from`, through the `Filer` as class files, and says so in a note.
  - **How the provider reaches them.** The processor generates in the last round, and javac does not read back the class files created in the last round, so the source `_VaubanComponents` cannot name them ("cannot find symbol", observed). It instantiates them through its own `MethodHandles.lookup()` instead: `$$construct(binaryName, parameterTypes, args)` (`findConstructor`), and for a proxy `$$proxy(binaryName, delegate)` (`findVirtual` of `$$setDelegate`). The provider's lookup has full privileges in its own package, so the module still needs no `opens`. These helpers are written only into a provider that has such a class; every other provider is unchanged (`ComponentProviderGenerator#generateFrom(…, bytecodeClasses)`).
  - **`n3a`, `n11c`** (`$$Intercepted` over the default `label(Hidden)`): the bytecode subclass overrides the erased descriptor `label(Ljava/lang/Object;)`, as the run-time subclass does, and the call is intercepted. Source cannot: `label(Hidden)` names the type, and `label(Object)` is a name clash.
  - **`n3b`** (`$$Intercepted` over `take(q.Hidden)`): the bytecode subclass had the same `IllegalAccessError` as the run-time one, so the emitter changed for both. `MethodShape#namesInaccessibleTypes` marks a method whose descriptor has a parameter class the subclass may not access (JVMS 5.4.4: a class of another runtime package that is not public in its class file, or not exported to the bean's module). For such a method only, `InterceptedEmitter` loads the bridge's parameter classes with `Class.forName(name, false, <subclass>.class.getClassLoader())` instead of `ldc`, and the `$$ti$` glue calls the `$$super$` bridge through `MethodHandles.lookup().unreflect(…).bindTo(target).invokeWithArguments(params)` instead of `checkcast` + `invokevirtual`, since the verifier requires the cast and the cast resolves the class. Both front-ends set the flag by the same rule: `InterceptorSubclassGenerator#accessibleFrom` (run time) and `InterceptedShapeFromElements#resolvableFrom` (processor). The bytecode of every other method is unchanged (`GoldenBytecodeTest`).
  - **A return type the subclass may not access** (not probed before; `HiddenTaker#give()` now): nothing in a class outside that type's package can type the `Object` the interceptor chain returns. `checkcast` throws `IllegalAccessError`, and so does a `MethodHandle#invokeExact` whose descriptor names the type (observed). Such a method is left out of the subclass by both front-ends, and the processor warns: a call runs it as on a plain instance of the bean, not intercepted. Before, the run-time subclass threw `IllegalAccessError` on every call, and the processor's source did not compile.
  - **`n3d`, `n11b`** (`_ClientProxy` over `take(q.Hidden)`, `take(Outer.Secret)`): the bytecode proxy forwards by descriptor (`invokevirtual Bean.take:(Lq/Hidden;)…`), which resolves no class constant for the parameter, as the run-time proxy already did. A producer's proxy (#42) takes the same path, in the producer's package.
  - **`n3c`, `n11a`** (`_ClientProxy` over a non-shadowed default whose member signature is unnameable; second round of the fix): the source proxy left the default out, with the warning of BUG-20261004-02, so a call ran the default body on the proxy instance, while the bytecode generators forwarded it. Such a default now triggers the bytecode proxy too, which forwards its erased descriptor to the contextual instance, as the run-time proxy does: the two generators agree on these shapes. `ClientProxyShapeFromElements#from` keeps its omission for a caller that renders source, but the processor no longer renders these proxies as source. Comparison against `main`: no generated source or class changes for any bean outside the fixed shapes (`n3c`/`n11a` themselves are `ScopedHiddenDefaultService` and `ScopedSecretLabeledService` in vauban-module-it). Tests: `InaccessibleMemberTypeModulePathTest#defaultForwarded`, `#privateNestedDefaultForwarded` (the expectation was reversed: RED `bug09b/red-n3c.log`, the default ran on the proxy instance), `ClientProxyInheritanceCrossCheckTest#unnameableClassMethods`, `UnnameableMemberBytecodeEmissionTest#nonShadowedDefault`. `OmittedDefaultDiagnosticTest` moved to a shadowed default (a private `label(Object)` on the base), which the source proxy still leaves out with that warning.
  - **Readability.** The new checks do not ask whether the generated class's module reads the type's module. That check (`reads`, `d3fa351`) reads the `requires` of the module being compiled, and so completes it. Run for every forwarded method, it made javac resolve the `provides … with _VaubanComponents` before the last round wrote that class: "cannot find symbol" in `vauban-producer-module-it/bean-b` and `example-cdi1015-app` (observed on the first green run). The shadowed-default owner keeps the check. So a type of a module the bean's module does not read is still taken as nameable, as on `main`, and `resolvableFrom` does not mirror the run-time `Module#canRead`.
  - **Tests.** RED on `main` (`bug09/red-core.log`, `red-processor.log`, `red-moduleit-main.log`): `InaccessibleMemberTypeTest` (vauban-core, run-time path: `IllegalAccessError` on `take`, `takeMany` and `give`, and through the proxy), `InterceptedShapeFromElementsTest#unnameableMemberSignatures`, `ClientProxyInheritanceCrossCheckTest#unnameableClassMethods`, `ComponentProviderGeneratorTest#bytecodeClassesThroughTheLookup` (vauban-processor: new API, does not compile), `UnnameableMemberBytecodeEmissionTest#producerProxy` (vauban-processor, through `VaubanProcessor`: a producer's proxy over `take(HiddenArg)` is a class file, the provider reaches it with `$$proxy`, and it forwards to the produced instance), `ConstructorInjectedInterceptedModulePathTest#bytecodeSubclass` (the provider builds a bytecode subclass through an injected constructor; it waited on BUG-20261007-03), and `UnnameableMemberTypeModulePathTest` (vauban-module-it, `n3a`, `n3b`, `n3d`, `n11b`, `n11c`, a class method over `Outer.Secret`, and a producer's proxy over `HiddenTaker`: the module did not compile, "HiddenArgument is not public" and "Secret has private access").
  - **Left as is.**
    - A *shadowed* default whose member signature, or carrying interface, the source cannot name is still left out of the source subclass and proxy (BUG-20261004-08 above), and the bytecode generators keep it. That divergence is unchanged.
    - A bytecode subclass or proxy has no `@Vetoed` and is not `final`, unlike the source one. Vauban never discovers it, since it is not in the index, but a container that scans the archive could.
    - `$$construct` and the `$$ti$` glue of a flagged method look up their handles on every call. These shapes are rare, so nothing is cached.

## BUG-20261004-10 — The Maven plugin pre-generates the intercepted subclass only for a bean with a class-level binding

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-07 (vauban#121, `82670c48`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`). Was pre-existing on `main` 7384ac1, noted in the FV2 report and the final review of `pr/ybl/inherited-interceptor-method`.
- **Module**: `vauban-maven-plugin` (`VaubanGenerator#generate`, the `vauban:generate` goal)
- **Symptom**: `vauban:generate` writes `<Bean>$$Intercepted` only for a managed, non-final bean whose class-level interceptor bindings are not empty (`VaubanGenerator.java:298-301`: `bean.kind() == BeanKind.MANAGED && !bean.interceptorBindings().isEmpty() && !isFinal`). `BeanDescriptor#interceptorBindings` holds the bindings on the bean class, plus the `@Inherited` ones of its superclasses (`BeanDiscovery#extractInterceptorBindings`). A bean intercepted any other way gets no pre-generated subclass from the plugin: through a method-level binding it declares or inherits, from a superclass or on an interface default method; through a constructor binding; or through an `@AroundInvoke` method of its own. The container then defines the subclass at run time. On the class path that works. On the strict module path it needs the bean's package opened to `io.vidocq.vauban.core`, and fails otherwise with `DeploymentException: Could not define interceptor subclass`, the mechanism of BUG-20261004-05.
- **Scope**: an archive the annotation processor compiled is not affected: `VaubanProcessor#isInterceptedTarget` counts the method-level bindings of every member since BUG-20261004-05, and the plugin skips a subclass that is already on disk.
- **Minimal reproduction**: a module built without the processor, holding `@Dependent class Audit { @Logged public void write() {} }` and a `@Logged` interceptor, run through `vauban:generate`, writes no `Audit$$Intercepted.class`; booted on the module path without `opens`, it fails as above.
- **Fix direction**: decide on the same rule as the processor. A managed bean is pre-generated when the container would wrap it: a class-level binding, a method-level binding on a business method it declares or inherits (`BeanMembers#isBusinessMethodOf`, CDI 4.1 §4.2), a constructor binding, or an `@AroundInvoke` method. The plugin holds the loaded `Class` at that point, so it can apply the rule the way the container does.
- **Cause**: the plugin kept its own rule, on the index-based `BeanDescriptor#interceptorBindings` (class-level only), instead of the walk the container runs in `InterceptorBeanWrapper#wrapInterceptedBeans`.
- **Fix**: the walk that decides whether, and why, the container wraps a bean moved to `vauban-core` (`InterceptionTargets#causeOf`): class-level bindings, then a method-level binding of a business method the bean declares or inherits (superclass or interface default method, resolved from the bean class, so an overriding declaration without the binding does not count, CDI 4.1 §4.2), then a constructor binding, then an `@AroundInvoke` method of the bean's class chain. The container asks it whether enabled interceptors select each member, as before; `vauban:generate` asks whether each member carries a binding (`InterceptionTargets#carriesInterception`), since the interceptors enabled in the deployment are not known at build time, and an unused subclass costs nothing. Like the container and the processor, the plugin no longer writes a subclass for an `@Interceptor` class, which the container never wraps; a bean whose members cannot be linked on the plugin's class path is skipped with a warning rather than failing the goal.
- **Tests**: `InterceptedSubclassGenerationTest` (vauban-maven-plugin; fixtures in `generate/intercepted`): `declaredMethodBinding`, `inheritedMethodBinding`, `defaultMethodBinding`, `constructorBinding`, `ownAroundInvoke` and `notIntercepted` (the `@Interceptor` case) failed before the fix (RED `bug10-red.log`: `MethodBound$$Intercepted must be pre-generated; generated: [ClassBound$$Intercepted, LoggedInterceptor$$Intercepted]`); `classLevelBinding` and `overriddenBindingDoesNotCount` pin the unchanged cases. Over the 74 interceptor packages of the CDI TCK 4.1.0 jar, the plugin writes the same 62 subclasses as `main` for the beans it already handled, byte for byte except one whose two methods come out in another order (the order depends on what the JVM loaded before, on `main` too), 30 new ones, and none of the 152 for `@Interceptor` classes.

## BUG-20261004-11 — The processor's index-based client proxy forwards only the methods the bean class declares

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-07 (vauban#122, `515fdcc`, branch `pr/ybl/index-proxy-inherited-methods`). Pre-existing on `main` 7384ac1 and still on `b95b8b1`, reproduced there by the tests below.
- **Module**: `vauban-processor` (`codegen/proxy/ClientProxyGenerator`, called from `VaubanProcessor`)
- **Symptom**: for a normal-scoped bean, the processor renders a source `_ClientProxy` when it finds the bean's `TypeElement` and the bean has a non-private constructor. Otherwise it emits a bytecode proxy from the index (`generateClass(ClientProxyGenerator.generate(classInfo))`). `ClientProxyGenerator#shapeOf` overrides `ClassInfo#methods()`, the methods the bean class declares, and no others, as its Javadoc says. So through that proxy, a method the bean inherits from a superclass, or an interface default method, runs on the proxy instance: it does not reach the contextual instance, and no interceptor runs. BUG-20261004-02 closed that gap for the two other proxy front-ends (`RuntimeClientProxyGenerator`, `ClientProxyShapeFromElements`).
- **When it is reached**: two cases.
  - A bean whose `TypeElement` the processor does not find. The lookup replaces every `$` of the binary name with `.`, so a top-level class whose own name contains `$` is one such case.
  - A bean with private constructors only. It is unproxyable under CDI 4.1 §3.10 unless the `ProxyLink` entry constructor is woven into it (vauban#24). For that reason `DeploymentValidator` reports it as `UNPROXYABLE_BEAN`, which the processor turns into a warning rather than an error.
- **Fix direction**: walk the superclasses and the default methods as `ClientProxyShapeFromElements#fromColocated` does. The index has the superclasses' `ClassInfo` when they are indexed. For the first case, find the `TypeElement` by its binary name rather than by a `$`-to-`.` rewrite, and keep the source path.
- **Fix**: the index-built proxy is no longer used for a bean the processor can see. `VaubanProcessor#typeElementByBinaryName` tries each top-level candidate of the binary name, then walks its member types, and keeps the one whose binary name matches, so a top-level `A$B` is found as well as a nested `Outer$Inner`. The source proxy then names the bean by its canonical name (`ClientProxySourceRenderer#render(shape, beanSourceName)`), not by a `$`-to-`.` rewrite. A bean with private constructors only gets the shape of a proxy placed in its own package, `ClientProxyShapeFromElements#fromColocated`, emitted as bytecode by `ClientProxyEmitter`: the inherited members and the interface default methods are forwarded under the rules the other front-ends share, the constructor chaining is unchanged (plain `super()`, retargeted by the weaver to the woven `(ProxyLink)` constructor). `ClientProxyGenerator` is left for a bean whose `TypeElement` the compiler cannot resolve, which no managed bean compiled here reaches (the index is built from `TypeElement`s, and the classes an extension adds are skipped as external); it still forwards declared methods only, since the index holds a superclass or an interface only when it is indexed itself. The generated sources and classes of `vauban-module-it` and `vauban-bench` are the same as `main`'s apart from the new fixture and the known entry order of `_VaubanComponents`. Tests: `IndexProxyInheritedMethodsTest` (vauban-processor: `dollarBeanTakesTheSourceRoute`, `dollarBeanForwardsInheritedMethods`, `privateConstructorBeanForwardsInheritedMethods`, the last two loading the proxy, woven by the auto-started javac plugin, and calling a declared, an inherited and a default method), `IndexProxyInheritedMethodModulePathTest` (vauban-module-it, module path: `Dollar$SelfNamingService` through the container). RED on `b95b8b1`: `bug11-red-final.log`.

## BUG-20261007-01 — The rendered intercepted subclass of a top-level class whose name contains `$` does not compile

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-07 (vauban#122, same root as BUG-20261004-11; `fc031fc` and `41a9d61`, branch `pr/ybl/index-proxy-inherited-methods`). Pre-existing on `main` `b95b8b1`; found while reproducing BUG-20261004-11.
- **Module**: `vauban-processor` (`codegen/interceptor/InterceptedSourceRenderer`, `codegen/proxy/ClientProxySourceRenderer`, `codegen/proxy/InterfaceProxySourceRenderer`), `vauban-core` (`interceptor/TypeRef#sourceName`)
- **Symptom**: an intercepted bean declared as a top-level class whose own name contains `$` (`@ApplicationScoped @Audited public class Dollar$SelfNamingService`) breaks the build: the rendered `Dollar$SelfNamingService$$Intercepted` `extends io.vidocq.vauban.moduleit.Dollar.SelfNamingService`, which javac resolves as a nested type of a class `Dollar` and refuses (`cannot find symbol`, `method does not override or implement a method from a supertype`).
The same goes for a normal-scoped producer of such a class (`Dollar_Produced$$…_ClientProxy`: `extends …Dollar.Produced`) or interface (`Dollar_Port$$…_ClientProxy`), and for any generated method signature naming such a type (`public app.Dollar.Note echo(app.Dollar.Note p0)` in a client proxy or a subclass, or the producer bean's own proxy for `Dollar$Produced produced()`): `app.Dollar$Note is not visible`.
- **Cause**: every source renderer derived a type's source name from its binary name by turning every `$` into `.`, which is right for a nested type only: `InterceptedSourceRenderer#render` for the bean, `ClientProxySourceRenderer#renderAt(shape, proxyBinaryName)` for a produced class, `InterfaceProxySourceRenderer#render` for a produced interface, and `TypeRef#sourceName` for every type in a signature.
- **Fix**: the names come from the `TypeElement`s, as BUG-20261004-11 did for the bean's source client proxy. `TypeRef` carries the canonical name an element front-end knows (`TypeRef.ofReference(binaryName, dims, canonicalName)`, set by `InterceptedShapeFromElements#typeRefOf` and for the interface a shadowed default is forwarded through); it is not part of the type's identity, so the shapes the front-ends build still compare equal, and `sourceName()` writes it, falling back on the `$` rewrite for a `TypeRef` built from a `Class` or the index. `InterceptedSourceRenderer#render(shape, beanSourceName)` and `ClientProxySourceRenderer#renderAt(shape, proxyBinaryName, beanSourceName)` take the bean's or produced type's qualified name, and `InterfaceProxySourceRenderer` uses the interface's. `VaubanProcessor` also finds the producer bean and the provider's bean element with `typeElementByBinaryName`. In `41a9d61`, the annotation artefacts (`AnnotationArtefacts`, rendered from the index) write an annotation type, a class value or an enum value by the canonical name the processor resolves with `typeElementByBinaryName` (`AnnotationArtefacts.render(types, canonicalNames)`), keeping the `$` rewrite only for a type the compiler does not know; and `resolveProducedTypeElement`/`resolveProducedInterfaceElement` look the produced type up by binary name too, so a producer of a nested class or interface gets its build-time client proxy, which `getTypeElement(binaryName)` never found. The generated sources and classes of the four modules the processor runs on (`vauban-module-it` built from `main`'s sources, `vauban-bench`, `vauban-producer-module-it/bean-b`, `vauban-examples/example-cdi1015-app`) are byte-identical to `main`'s, apart from the `_VaubanComponents` entry order (sorted sources and normalised `javap -c -p` equal). A produced type that is parameterised still has its first class supertype picked rather than its raw type; that is unchanged, and with this fix a nested supertype found first is now resolved too. Tests: `DollarNamedTypeRenderingTest` (vauban-processor: `interceptedDollarBean`, `dollarTypeInSignatures`, `producerOfDollarClass`; RED `bug701-red-processor.log`), `DollarNamedTypeModulePathTest` (vauban-module-it, module path: `interceptedDollarBean`, `producerOfDollarClass`, `producerOfDollarInterface`; RED `bug701-red-moduleit-reactor.log`, processor of `79863dc` in the reactor: the module did not compile; the earlier `bug701-red-moduleit.log` ran `-pl vauban-module-it -am`, which leaves the processor out of the reactor and takes it from the local repository), `DollarNamedTypeRenderingTest#annotationArtefactsOfDollarTypes` and `#producerOfNestedTypes` (RED `left-red-processor.log`), `DollarQualifierAndNestedProducerModulePathTest` (vauban-module-it: a `$`-named qualifier with enum and class members resolved with annotation reflection forbidden, producers of a nested class and interface; RED `left-red-moduleit.log`: the provider did not compile).

## BUG-20261007-02 — The bytes of a run-time-generated subclass depend on what the JVM loaded before

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-07 (`0393dd78`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`)
- **Module**: `vauban-core` (`InterceptorSubclassGenerator#fromClass`, `RuntimeClientProxyGenerator`), reached at build time through `vauban:generate`
- **Surfaced by**: the generated-bytes comparison of BUG-20261004-10.
- **Symptom**: `vauban:generate` run twice over the same input can write a `<Bean>$$Intercepted` whose methods come in another order. Over the interceptor packages of the CDI TCK 4.1.0 jar, `contract/invocationContext/SimpleBean$$Intercepted` lists `testGetTimer` before `testGetMethod` when that package is generated with the 73 others, and the reverse when it is generated alone, on `main` as well. Same members, same size: the class works either way, but the build is not reproducible.
- **Minimal reproduction**: generate the subclass of one bean in two JVMs that loaded different classes before it, and compare the bytes.
- **Cause**: the shapes are built from `Class#getDeclaredMethods`, `Class#getMethods` and `Class#getDeclaredConstructors`, whose order the JDK leaves unspecified ("not sorted and not in any particular order"). HotSpot returns the methods in the order of their name symbols in memory, which depends on the classes the JVM loaded before. The order also decides which of two `getMethods` entries with the same name and parameters is kept.
- **Fix**: `BeanMembers#STABLE_ORDER` (name, then parameter types, then return type, then declaring class) and `BeanMembers#inStableOrder`; `InterceptorSubclassGenerator#fromClass` and `RuntimeClientProxyGenerator#shapeOf` read `getDeclaredMethods` and `getMethods` through it, so each group of methods (declared, inherited public, inherited non-public for the subclass; each class of the hierarchy, then the default methods for the proxy) comes sorted, and the first of two same-key `getMethods` entries is always the same one. Constructors stay in reflection order: they share one name, so the symbol order cannot move them. The processor's front-ends read javac's elements in source order and are not affected.
- **Tests**: `StableMemberOrderTest` (vauban-core), fixtures `OrderedBean`, `OrderedBeanSuper` and `OrderedBeanPrimer`, the last loaded first so that its reverse-order method names set the symbol order. Both tests failed before the fix (RED `bug07-02-red.log`: `interceptedSubclassOrder` got `[soZulu(), soXray(), soSierra(), …, soAlpha(), soYankee(), soHotel(int), soBravo(), soPapa(), soDelta()]`, `clientProxyOrder` `[soZulu(), …, soAlpha(), soYankee(), soPapa(), soHotel(int), soDelta(), soBravo()]`). Over the interceptor packages of the CDI TCK 4.1.0 jar, the subclasses and client proxies `vauban:generate` writes are now identical byte for byte whether the 74 packages are generated in one order, in the reverse order, or one alone.
- **Not the same cause**: the entry order of a `_VaubanComponents` provider follows the order of the beans, which `BeanDiscovery#discoverBeans` takes from `VaubanIndex#getKnownClasses`. `VaubanIndex` keeps its classes in `Map.copyOf`, whose iteration order the JDK randomizes from one JVM run to the next, for the plugin and the processor alike; `ComponentCollector#collect` expects its caller to sort the beans and neither does. Not fixed here.

## BUG-20261007-04 — The entry order of a generated `_VaubanComponents` changes from one build to the next

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-08 (`fdf6ded1`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`)
- **Module**: `vauban-indexer` (`ComponentCollector#collect`), its callers `VaubanProcessor` and `VaubanGenerator` (vauban-maven-plugin)
- **Surfaced by**: BUG-20261007-02 (the generated-bytes comparison).
- **Symptom**: the processor and `vauban:generate` can write a different `_VaubanComponents` for the same input: the same entries, in another order. Two plugin runs, in two JVMs, over the same fixture directory wrote the components of `ClassBound`, `MethodBound`, `InheritedBound`, … in two different orders.
- **Cause**: the beans come from `BeanDiscovery#discoverBeans`, which walks `VaubanIndex#getKnownClasses`. `VaubanIndex` keeps its classes in `Map.copyOf`, whose iteration order the JDK randomizes from one JVM run to the next. `ComponentCollector#collect` documents that its caller sorts the beans, and neither caller does. The other inputs of a provider follow bean order too: the client proxies, the producers' proxies, and the processor's annotation types and service file.
- **Fix direction**: sort where the provider is assembled, on a documented key, without changing the index's iteration order, which bean discovery depends on.
- **Fix**: `ComponentCollector#collect` sorts the beans by binary name before it groups them, so each package lists its components (a bean's `$$Intercepted` right after it), field injections and method invocations in that order, whatever order the caller hands them in; bean discovery keeps the index order. The other inputs of a provider are sorted where the callers assemble it: the client proxies by name, the producers' proxies by produced-type key then proxy name, the processor's annotation types by name, and the service file's provider names.
- **Tests**: `ComponentCollectorOrderTest` (vauban-indexer): `entriesInNameOrder` and `sameOutputWhateverTheInputOrder` failed before the fix (RED `bug07-04-red.log`: `expected: <[app.Alpha, app.Alpha$$Intercepted, app.Bravo, app.Charlie]> but was: <[app.Charlie, app.Alpha, app.Alpha$$Intercepted, app.Bravo]>`). End to end: two builds of `vauban-module-it` and `vauban-producer-module-it` with the reactor's processor write the same `_VaubanComponents` sources, classes and service files (8 files); two earlier builds with the processor from the local repository did not (the client-proxy cases and the annotation literals moved). Two `vauban:generate` runs over the same input, in two JVMs, write the same `_VaubanComponents`.

## BUG-20261007-05 — The plugin's `_VaubanComponents` does not list the `$$Intercepted` subclasses it pre-generates

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-08 (`6945745f`, branch `pr/ybl/plugin-pregenerates-every-intercepted-bean`)
- **Module**: `vauban-maven-plugin` (`VaubanGenerator#generateComponentProvider`, `#generateDependencyProviders`)
- **Surfaced by**: BUG-20261004-10.
- **Symptom**: the plugin builds every `ProvidedClass` with `intercepted = false`, so `ComponentCollector` never adds a `<Bean>$$Intercepted` component to the provider, although the plugin wrote that subclass next to it. The processor sets the flag for each subclass it renders.
- **What it costs at run time** (from `InterceptorBeanWrapper` and `VaubanLookup`; the qualified-export case is reproduced by the test below): the container finds the pre-generated subclass (`Class.forName`), so it does not define one. It then instantiates it through `instantiatePreferProvider`: the provider has no entry, so it tries `MethodHandles.publicLookup()` on the subclass constructor, which the emitter makes public. That works, without `opens`, when the bean's package is exported to everyone. When the package is exported to `io.vidocq.vauban.core` only (a qualified export), the public lookup is refused and the container falls back to `privateLookupIn`, which needs `opens <pkg> to io.vidocq.vauban.core`: without it, creating the bean fails with "Cannot reflectively access <Bean>$$Intercepted on the module path". So the cost is one reflective constructor call when the package is exported to everyone, and a boot failure without `opens` when the export is qualified.
- **Fix direction**: set `intercepted` for a bean whose `$$Intercepted` the plugin wrote or found on disk, as the processor does.
- **Fix**: both provider paths of the plugin (`generateComponentProvider`, `generateDependencyProviders`) set `intercepted` when `<Bean>$$Intercepted` is on disk in the output directory, written by this run or found there, so `ComponentCollector` adds its component right after the bean's, as for the processor.
- **Tests**: `InterceptedSubclassGenerationTest#providerListsThePreGeneratedSubclasses` (RED `bug07-05-red.log`: `the provider must create …ClassBound$$Intercepted; it knows [… the beans only …]`). `PluginBuiltModulePathTest` (vauban-maven-plugin) builds a module the way a plugin user does (javac, then the generator over the classes, then `module-info.java` alone, declaring the provider), with a bean bound only through a method it declares, in a package exported to `io.vidocq.vauban.core` only and no `opens`, and boots it in a module layer: the bean is the pre-generated subclass and the interceptor runs. Without this fix (RED `bug07-05-e2e-red.log`) it fails with `Cannot reflectively access vauban.plugin.it.beans.MethodBoundService$$Intercepted on the module path.`; it also covers BUG-20261004-10 on the module path. Over the interceptor packages of the CDI TCK 4.1.0 jar, with the providers of the scanned jars written too, the 69 providers keep every entry `main` writes, in sorted order, and 40 of them gain one `$$Intercepted` entry for each subclass written next to them (90 in all).

## BUG-20261007-03 — An intercepted bean with an injected constructor reads its parameters' qualifiers by reflection

- **Date**: 2026-10-07
- **Status**: FIXED 2026-10-07 (branch `pr/ybl/unnameable-inherited-member-types`). Pre-existing on `main` b95b8b1; found while writing the tests of BUG-20261004-09.
- **Module**: `vauban-core` (`InterceptorBeanWrapper`, `QualifierHelper`)
- **Symptom**: with `vauban.annotations.reflection=forbid` (every vauban-module-it test), a `@Dependent @Audited` bean whose `@Inject` constructor takes a `Collaborator` fails when it is created: `Forbidden: reading the qualifiers off parameter 0 of …CtorInjectedAuditedProbe.<init>() needs reflection`. The parameter has no annotation. The same bean without interception, or with a no-arg constructor, is created in-module.
- **Minimal reproduction**: on `main`, in vauban-module-it, `@Dependent @Audited public class CtorInjectedAuditedProbe { @Inject public CtorInjectedAuditedProbe(Collaborator c) {…} }` and `VaubanContainer.builder().addBeanClass(AuditInterceptor.class).addBeanClass(Collaborator.class).addBeanClass(CtorInjectedAuditedProbe.class).build().select(CtorInjectedAuditedProbe.class)`; log `bug09/probe-ctor-main.log`.
- **Cause (from the trace)**: `InterceptorBeanWrapper$1#create` (`InterceptorBeanWrapper.java:824`) resolves the constructor arguments itself, through `QualifierHelper#extractParamQualifiers(Parameter)`, which reads the parameter's annotations reflectively. It does not use the metadata the processor generates for the bean's injection points.
- **Fix**: `InterceptorBeanWrapper`'s intercepted factory takes each constructor parameter's qualifiers from the bean's descriptor (`VaubanContainer#describedParameterQualifiers`), as `VaubanContainer` does for a bean that is not intercepted, and reads the parameter back only when nothing describes it. This covers both of its constructor paths: with and without an `@AroundConstruct` chain. Interceptor instances built with constructor arguments (`getOrCreateInterceptorInstance`) still read their parameters: they have no bean descriptor there. Not probed.
- **Tests**: `ConstructorInjectedInterceptedModulePathTest#sourceSubclass` and `#bytecodeSubclass` (vauban-module-it; RED `bug09b/red-ctor.log`: `Forbidden … reading the qualifiers off parameter 0 of …<init>()` for both).

## BUG-20261008-01 — A synthetic bean type given as a language-model type is lost between build time and run time

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (`ca601963`, branch `pr/ybl/rest-client-module-path`)
- **Affected module**: `vauban-core` (`SyntheticMetadataSerializer.writeBeanBuilder`)
- **Symptom**: a build compatible extension run by the Vauban processor declares a synthetic bean with
  `components.addBean(Object.class).type(langModelType)` — the only way to name a type the compilation is still
  producing, which cannot be loaded. The processor validates the deployment against that type, then writes
  `META-INF/vauban-synthetic-metadata.properties` with `bean.N.types=java.lang.Object` only: at run time the bean has
  no other type and every injection point of that type is unsatisfied. Seen with Cyrano's `@RestClient` beans on the
  Vidocq runtime (`DeploymentException: Unsatisfied dependency: field RelayResource.greetings of type GreetingClient`)
  after cyrano BUG-20261008-06 made the extension declare them that way.
- **Minimal repro**: `new VaubanSyntheticBeanBuilder<>(Object.class).type(new VaubanClassType(DotName.of("com.example.Api"), null))`,
  `SyntheticMetadataSerializer.write(...)`, `readBeans(...)`: the types are `[java.lang.Object]`.
- **Root cause**: `VaubanSyntheticBeanBuilder.type(Type)` keeps language-model types in `indexTypes`;
  `writeBeanBuilder` writes `getTypes()` (the `Class` types) only.
- **Investigations**:
  - 2026-10-08: `SyntheticMetadataSerializerTest.shouldRoundTripALanguageModelClassType` read back `[java.lang.Object]`.
    Fixed: a class type given through the language model is written by name with the other types and loaded at run
    time like them. Parameterized language-model types (`Optional<String>`…) are still not written: the `types` list
    has no notation for them. With the fix, the Vidocq Rest Client example (Cyrano's `@RestClient` bean, declared at
    build time) starts and answers on the module path and from its jlink image.

## BUG-20261008-02 — A build-time synthetic bean param that is an array, an annotation or a ClassInfo is silently dropped

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#130, branch `pr/ybl/synthetic-param-types`)
- **Affected module**: `vauban-core` (`SyntheticMetadataSerializer.encodeParam`, `SyntheticComponentRegistrar.applyParam`)
- **Surfaced by**: Foy Phase 2 (`FoyWebExtension`, foy branch `pr/ybl/servlet-completion-phase2`).
- **Symptom**: a build compatible extension run by `vauban-processor` calls
  `SyntheticBeanBuilder.withParam("classes", Class<?>[])`; at run time the creator's
  `Parameters.get("classes", Class[].class)` is `null`, and nothing is reported at build time (no `.param.classes=`
  line in `META-INF/vauban-synthetic-metadata.properties`). CDI 4.1 Lite requires every `withParam` overload to reach
  the creator: primitive and `String`/`Class`/`Enum` arrays, `ClassInfo`, `AnnotationInfo`/`Annotation`, `InvokerInfo`.
- **Minimal repro**: `@Synthesis` with `syn.addBean(X.class).type(X.class).withParam("classes", new Class<?>[]{String.class}).createWith(Creator.class)`,
  compiled with `vauban-processor`, then booted: `Creator` receives `null`. Pinned by foy's spike test
  `BceCapabilitySpikeTest.synthesisWithClassArrayParam` (foy `383b850`).
- **Root cause**: `encodeParam` (`main` @ `4ce059fa`, ~l.158-168) encodes `String`, `Boolean`, `Integer`, `Long`,
  `Double`, `Class`, `Enum` and returns `null` ("unsupported param type — skipped") for everything else.
- **Workaround (Foy)**: one `String` param of comma-joined binary names, split by the creator.
- **Investigations**:
  - 2026-10-08: confirmed on `main` @ `4ce059fa` by reading `encodeParam`; observed end to end on `0.4.0-SNAPSHOT`
    from `feat/dependency-providers` @ `7384ac10`.
  - 2026-10-08: `BceRuntimeParamsTest` (boot from written metadata) fails on `main` `76f530bc`: the creator gets a
    `String` where it asked for an enum. Reading the boot side showed more of the same: `applyParam` dropped `Class`
    and `Enum` params too (its switch had no case for them), and the params of a synthetic observer were never
    applied at all.
- **Fix**: `SyntheticParamCodec` writes every value `withParam` accepts — primitive, `String`, `Class`, `Enum` and
  annotation arrays, `ClassInfo` (read back as `Class`), `Annotation` and `AnnotationInfo` (read back as
  `Annotation`, every member kept), `InvokerInfo` (bean class, method, lookups) — in length-prefixed frames, so a
  value may hold any character. Earlier files still read. A value of another type fails the build with the bean and
  the param named; a class the boot cannot load fails the deployment the same way. Observer params are applied, and
  the duplicate check of synthetic beans compares array params by content. Tests: `SyntheticParamCodecTest`,
  `BceRuntimeParamsTest`, `BceCompileTimeTest.syntheticParamsOfEveryKindSurviveTheBuild` (Foy's `Class<?>[]`).

## BUG-20261008-03 — @Registration does not see the beans that @Enhancement creates

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#131, branch `pr/ybl/registration-after-enhancement`)
- **Affected module**: `vauban-core` (`BceProcessor`)
- **Surfaced by**: Foy Phase 2 (`FoyWebExtension`).
- **Symptom**: an extension adds `@Dependent` in `@Enhancement(types = Object.class, withAnnotations = WebServlet.class)`
  to an unscoped `@WebServlet` class. The class becomes a bean (listed in `vauban-beans.list`, resolvable at run time),
  but the extension's `@Registration(types = Servlet.class)` method is never called for it. CDI 4.1 Lite runs
  Registration on the bean set as it stands after Enhancement.
- **Minimal repro**: foy `FoyWebExtensionTest.scopesAndIndex` (foy-cdi-vauban) before Foy's workaround — the index built
  in `@Registration` held only the class that already had a scope.
- **Root cause**: `BceProcessor` (`main` @ `4ce059fa`, ~l.229-234) passes the same pre-enhancement `beans` list (from
  discovery) to `processEnhancement` and `processRegistration`; the enhancement modifications are applied to the
  descriptors afterwards (`applyEnhancements`).
- **Workaround (Foy)**: `FoyWebExtension` also records the classes it sees in `@Enhancement`; foy-core skips listed
  classes that end up without a bean, with a WARNING.
- **Investigations**:
  - 2026-10-08: confirmed on `main` @ `4ce059fa` by reading `BceProcessor`.
  - 2026-10-08: `BceCompileTimeTest.registrationSeesTheBeansEnhancementCreated` fails on `main` `76f530bc`. The
    first failure is a different one: `bean.scope().name()` threw a `NullPointerException` in `@Registration`,
    because `VaubanBceScopeInfo.annotation()` returned `null` for a scope outside the index, which every built-in
    scope is at build time. A second cause showed when reading `process`: each extension went through all its phases
    before the next extension started, so an extension's `@Registration` could run before another extension's
    `@Enhancement`. At container start the bug was masked: the index is rebuilt with the enhanced annotations
    before discovery (but see BUG-20261008-05).
- **Fix**: `BceProcessor.process` runs each phase for every extension before the next phase, and hands
  `@Registration` the beans as `@Enhancement` left them, through `beansAfterEnhancement`. That method is now the one
  place that applies the enhancements and makes a bean of a class that gained a scope. The processor and the
  container each had a copy of that code; both copies are gone. `VaubanBceScopeInfo` names a scope outside the index
  and gives the stub declaration a class type gives. Tests: `BceCompileTimeTest.registrationSeesTheBeansEnhancementCreated`
  (the extension that registers is listed before the one that enhances), `VaubanBceScopeInfoTest`.

## BUG-20261008-05 — An annotation added by @Enhancement loses its member values

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#135, branch `pr/ybl/enhancement-annotation-members`)
- **Affected module**: `vauban-core` (`VaubanContainerBuilder` index rebuild, `EnhancementPatchSerializer`,
  `VaubanClassConfig`, `VaubanFieldConfig`, `EnhancementApplier`), `vauban-processor`, `vauban-maven-plugin`
- **Surfaced by**: the tests of BUG-20261008-03.
- **Symptom**: `ClassConfig.addAnnotation(NamedLiteral.of("enhanced"))` on a `@Dependent` bean gives the bean its
  default name, not `enhanced`; a qualifier with members added the same way, to a class or to a field, loses its
  members, so an injection point that tells two beans apart by a member is unsatisfied. The default name of a nested
  class on that path keeps the binary simple name (`bceRegistrationAfterEnhancementTest$Plain`).
- **Minimal repro**: two extensions on `SeContainerInitializer.addBeanClasses`, one adding `@Named("enhanced")` to a
  bean in `@Enhancement`, the other recording `BeanInfo.name()` in `@Registration`: it records the default name.
- **Root cause**: `VaubanClassConfig.addAnnotation(Annotation)` and `VaubanFieldConfig.addAnnotation(Annotation)`
  kept the annotation type only. At container start, the rebuilt index wrote each added annotation with no member,
  through the 8-argument `ClassInfo` constructor that loses the simple name; `addAnnotation(AnnotationInfo)` did not
  reach it at all. At build time, the frozen patch `META-INF/vauban-enhancements.properties` recorded annotation
  names only, in the processor and in `vauban:generate`. `EnhancementApplier` built the added qualifiers from their
  type and never applied an added `@Named` to the bean's name.
- **Investigations**:
  - 2026-10-08: found on `pr/ybl/registration-after-enhancement` (based on `main` `76f530bc`).
    `BceEnhancementMembersTest` fails on `main`: `Unsatisfied dependency ... @Channel(value=beta)`.
- **Fix**: the class and field configs keep the instance an extension gives; `VaubanClassConfig.getAddedAnnotationsIndexed()`
  gives every added annotation in index form, members included, whichever `addAnnotation` added it. The rebuilt index
  uses it and `ClassInfo.withAnnotations`, which keeps the simple name. The frozen patch writes each annotation with
  its members through `SyntheticParamCodec.encodeAnnotation`; the names-only form of earlier builds still reads.
  `EnhancementApplier` builds added qualifiers from the instance and applies an added `@Named` (its value, or the
  default name) to the bean and its `@Named` qualifier. Tests: `BceEnhancementMembersTest` (name, default name of a
  nested class, class and field qualifier members, seen by another extension's `@Registration`),
  `EnhancementPatchSerializerTest`, `BceCompileTimeTest.frozenPatchKeepsMembers`.

## BUG-20261008-04 — Application stereotypes are not bean-defining at build time

- **Date**: 2026-10-08
- **Status**: FIXED 2026-10-08 (Vidocq/vauban#132, branch `pr/ybl/stereotype-bean-defining`)
- **Affected module**: `vauban-processor` (indexing / bean-defining annotations), `vauban-core` (stereotype scope)
- **Surfaced by**: Foy Phase 2 (`FoyWebExtension`, fix round of Task 2.10).
- **Symptom**: with `vauban-processor`, (1) a class whose only CDI annotation is an application stereotype
  (`@Stereotype @ApplicationScoped @interface Managed`) is not indexed — no bean — although a scoped stereotype is
  bean-defining (CDI 4.1 §2.5.1, §2.8); (2) a class indexed for another reason and carrying that stereotype gets
  `@Dependent` instead of the stereotype's `@ApplicationScoped`. Same result with the stereotype in the same compilation
  or in a separate library jar.
- **Minimal repro**: a `@WebServlet @Managed` class plus a control class carrying only `@Managed`, compiled with
  `vauban-processor` + foy-cdi-vauban on the processor path: the first is a `@Dependent` bean, the second is no bean.
  (Foy dropped the harness tests and covers its logic with `FoyWebExtensionScopeTest`.)
- **Root cause**: not investigated.
- **Investigations**:
  - 2026-10-08: observed on `0.4.0-SNAPSHOT` from `feat/dependency-providers` @ `7384ac10`; not re-run on `main`
    (`main` gained build-time annotation-type indexing in vauban#70, which may change part (2)).
  - 2026-10-08: `BceCompileTimeTest.applicationStereotypeIsBeanDefining` fails on `main` `76f530bc` with part (1):
    the class carrying only the stereotype is not in the bean list. Root cause: the processor only looked at the
    classes carrying an annotation of a fixed list (`ApplicationScoped`, `RequestScoped`, `Dependent`, `Singleton`,
    `Produces`, `Interceptor`) plus the extensions' triggers, so a class whose only bean-defining annotation was a
    stereotype, a custom scope, `@SessionScoped` or `@Model` never entered the index. Part (2) no longer reproduces
    on `main`: a class indexed through its producer gets the stereotype's `@ApplicationScoped`, with the stereotype
    in the same compilation or in a library (the annotation-type indexing of vauban#70 sees it).
- **Fix**: the processor supports every annotation (`*`, never claimed) and indexes a class carrying one of the fixed
  list, an extension trigger, or an annotation meta-annotated `@Stereotype`, `@NormalScope` or `@Scope`. Tests:
  `BceCompileTimeTest.applicationStereotypeIsBeanDefining`, `libraryStereotypeIsBeanDefining` (stereotype compiled
  separately, on the class path); both also check that the beans are normal-scoped (they get a client proxy).
