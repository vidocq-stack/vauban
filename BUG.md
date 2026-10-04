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
- **Date**: 2026-09-11 — **Status**: OPEN
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
- **Status**: OPEN
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
- **Status**: OPEN
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
- **Status**: FIXED 2026-10-04 (`pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`VaubanInvocationContext#getMethod`)
- **Surfaced by**: a review of the MicroProfile 7.2 upgrade (Humboldt names spans and sets `code.function.name` from `getMethod()`).
- **Symptom**: an interceptor bound to a bean sees `ctx.getMethod()` as `<Bean>$$Intercepted.$$super$<name>` instead of the bean's method whenever the method is inherited: declared on the direct superclass, on any class above it, or as an interface default method. Every interceptor gets the wrong `Method` (wrong declaring class, `$$super$` name, no annotations), so `getInterceptorBindings()` also loses the method-level bindings of the inherited method. Both front-ends are affected: the run-time Class-File subclass and the processor's source subclass. Only a method the bean class declares itself was resolved.
- **Minimal reproduction**: `InheritedInterceptedMethodTest` (vauban-core, run-time subclass) and `InheritedMethodModulePathTest` (vauban-module-it, processor subclass on the module path): `C extends B extends A`, interceptor recording `ctx.getMethod()`. Before the fix, the vauban-core run had 8 tests, the six kept plus two temporary probes of an inherited protected and package-private method (since removed, see BUG-20261004-03): 6/8 failed, the four inherited cases with `getMethod() leaked the generated bridge` and the two probes with no interception at all. The vauban-module-it run failed 6/8 with `getMethod() leaked the generated bridge`.
- **Cause**: the generated subclass hands the context its `$$super$<name>` bridge, and `getMethod()` looked the original up with `getSuperclass().getDeclaredMethod(…)` on the bean class only, falling back to the bridge on `NoSuchMethodException`. `InterceptorManager` already walked the whole superclass chain to collect the method's bindings, so the chain was right and only the method reported to interceptors was wrong.
- **Fix**: `getMethod()` resolves the bridge up the whole superclass chain (most derived declaration first, any access), then to the interface default method, once per bridge: the result is kept per generated subclass in a `ClassValue`. Resolving at run time rather than capturing the declaring class in the generated code keeps every already-compiled `$$Intercepted` correct, and does not name a class that may be inaccessible from the bean's package or module. The walk now lives in `BusinessMethods`, which `InterceptorManager` shares, so the method reported and the bindings applied cannot diverge (BUG-20261004-04).

## BUG-20261004-02 — A client proxy does not forward an interface default method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`RuntimeClientProxyGenerator#shapeOf`), `vauban-processor` (`ClientProxyShapeFromElements#from`, `#fromColocated`)
- **Symptom**: calling an interface default method that a normal-scoped bean does not override, through its client proxy, runs the default body on the proxy instance: the contextual instance is bypassed and the interceptors bound to the bean do not fire. The bean's own methods are forwarded and intercepted as expected.
- **Minimal reproduction**: an `@ApplicationScoped @Audited` bean `implements Greeting` (default `greet`), no override; `select(…).greet("y")` returns `"hi y"` and the interceptor records nothing. Seen on both proxies while working on BUG-20261004-01 (run-time proxy in vauban-core, processor proxy in vauban-module-it); the regression tests of BUG-20261004-01 use a `@Dependent` bean to reach the generated subclass directly.
- **Cause**: all three shapes walk the superclass chain only; methods inherited from interfaces are never listed.
- **Fix**: the three shapes also forward the interface default methods no class of the bean overrides: `Class#getMethods()` filtered on `isDefault()` in `shapeOf`, `Elements#getAllMembers` filtered on `DEFAULT` in both processor shapes (as `InterfaceProxySourceRenderer` already did for an interface-typed proxy). The producer proxies built from the same shapes forward them too. Tests: `DefaultMethodInterceptionTest#defaultMethodThroughTheClientProxy` (vauban-core), `InheritedBindingModulePathTest#defaultMethodThroughTheClientProxy` (vauban-module-it), `ClientProxyInheritanceCrossCheckTest#defaultMethodsAreForwarded` (vauban-processor: all three shapes, an overridden default and a default re-declared by a sub-interface). The indexer-based `ClientProxyGenerator` fallback (beans with no non-private constructor, unproxyable per CDI 4.1 §3.10) still overrides declared methods only.

## BUG-20261004-03 — The run-time intercepted subclass skips an inherited protected or package-private method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorSubclassGenerator#fromClass`, also used by the Maven plugin's `VaubanGenerator`)
- **Symptom**: a protected or package-private business method that the bean inherits from a superclass is not intercepted when the `$$Intercepted` subclass comes from the run-time generator (class-path fallback, Maven plugin). The processor's subclass intercepts it (`InterceptedShapeFromElements` lists every member through `Elements#getAllMembers`), so the two front-ends do not produce the same override set, although both are documented to.
- **Minimal reproduction**: a `@Dependent @Traced` bean `extends Parent extends Grandparent`, where `Grandparent` declares `protected String inheritedProtected()` and `String inheritedPackagePrivate()`; calling either from the same package reaches no interceptor, while the public inherited method is intercepted. Seen while working on BUG-20261004-01.
- **Cause**: `fromClass` takes the bean's declared methods, then only the inherited *public* ones (`Class#getMethods()`).
- **Fix**: `fromClass` then walks the superclasses for the inherited protected and package-private methods, with the rules `Elements#getAllMembers` applies (JLS 8.4.8): the most derived declaration of a signature decides, so a `final` or `private` one hides the rest, and a package-private method counts only when every class from the bean up to its declaring class shares that class's runtime package — no subclass elsewhere can override it. They are appended after the existing passes, so the shape of a bean without such methods is unchanged (`GoldenBytecodeTest`). Tests: `InheritedInterceptedMethodTest#inheritedProtected` and `#inheritedPackagePrivate` (vauban-core, through the run-time client proxy and subclass), `InterceptedShapeFromElementsTest#inheritedNonPublicMethodsAgree` (vauban-processor: both front-ends select the same set, including a protected and a package-private method of a superclass in another package and a `final` override).

## BUG-20261004-04 — An interceptor bound to an interface default method never runs, while `getInterceptorBindings()` lists its binding

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-core` (`InterceptorManager#matchingForMethod`, `#resolveInterceptorDescriptorsForMethod`, `InterceptorBeanWrapper#wrapInterceptedBeans`)
- **Surfaced by**: the review of the BUG-20261004-01 fix, which made `getMethod()` — and with it `getInterceptorBindings()` — answer the interface default method.
- **Symptom**: an interceptor binding declared on an interface default method the bean does not override is listed by `getInterceptorBindings()`, but the interceptor bound to it does not run: the chain ignores it. A bean whose only binding sits on such a method is not intercepted at all.
- **Minimal reproduction**: `interface Greeter { @Marked default String markedGreet(String w) {…} }`, a `@Traced @Dependent` bean implementing it, a `@Marked` interceptor: `markedGreet("z")` runs the `@Traced` interceptor, which sees `@Marked` in its bindings, but not the `@Marked` one. `DefaultMethodInterceptionTest#defaultMethodBindingApplies` and `#beanBoundOnlyByADefaultMethod` (vauban-core) failed before the fix.
- **Decision**: the binding applies. CDI 4.1 does not mention default methods; it gives the bindings of inherited methods for superclasses (§4.2: inherited unless a class below overrides the method) and never inherits type-level metadata from interfaces (§4). A default method the bean does not override is a member of the bean class (JLS 8.4.8) exactly as a non-overridden superclass method is, so its own bindings apply by the same rule, and stop applying once a class of the bean overrides it. Weld does the same: its annotated type lists the interface default methods next to the class hierarchy's methods (`BackedAnnotatedType`), and the interception model reads each business method's own bindings (`InterceptionModelInitializer`). The class-level bindings of an interface still never apply.
- **Cause**: `InterceptorManager` resolved the method's declaration on the superclass chain only, while `getMethod()` also fell back to the interface default method; the bean wrapper looked for method-level bindings on the superclass chain only, and resolved each method from its declaring class instead of the bean class.
- **Fix**: one helper, `BusinessMethods#declarationOf`, gives the declaration a call runs on an instance of the bean class — the most derived one up the superclass chain, else the inherited default method — and the method-level bindings are read off it. `VaubanInvocationContext#getMethod()` (hence `getInterceptorBindings()`), the chain and the wrapper's "is this bean intercepted" check all use it; the wrapper now also checks the inherited default methods. Tests: `DefaultMethodInterceptionTest` (vauban-core, five cases including an overridden default method that drops its binding), `InheritedInterceptedMethodTest#overriddenMethodDropsTheSuperclassBinding` and `#overloadOnTheGrandparent`/`#overloadOnTheParent`, `InheritedBindingModulePathTest` (vauban-module-it).

## BUG-20261004-05 — On the module path, a bean bound only through an inherited method cannot be deployed

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-processor` (`VaubanProcessor#isInterceptedTarget`)
- **Surfaced by**: the tests of BUG-20261004-04.
- **Symptom**: a bean with no class-level binding whose only binding sits on a method it inherits — a superclass method, or (since BUG-20261004-04) an interface default method — gets no `$$Intercepted` subclass from the processor. On the strict module path the container then has to define the subclass itself and cannot (no `opens`): `DeploymentException: Could not define interceptor subclass`. On the class path the run-time fallback hid it.
- **Minimal reproduction**: `@Dependent class InheritedBindingService extends BoundBase`, `BoundBase` declaring `@Audited public String inheritedBound()`; booting a container with it in vauban-module-it throws the exception above. `InheritedBindingModulePathTest#inheritedMethodBindingApplies` and `#defaultMethodBindingApplies` failed before the fix.
- **Cause**: `isInterceptedTarget` looked for method-level bindings on the methods the bean class declares only.
- **Fix**: it also looks at the methods the bean inherits, as `Elements#getAllMembers` lists them: a method a class of the bean overrides is left out, so its binding does not count (CDI 4.1 §4.2). A supertype the compiler cannot complete leaves the bean to the run-time fallback, with a note, as before. A bean bound through a *generic* superclass method also needed BUG-20261004-06.

## BUG-20261004-06 — The processor's generated sources do not compile when the bean binds a type variable of an inherited method

- **Date**: 2026-10-04
- **Status**: FIXED 2026-10-04 (`pr/ybl/inherited-interceptor-method`)
- **Module**: `vauban-processor` (`InterceptedShapeFromElements`, `InterceptedSourceRenderer`, `ClientProxyShapeFromElements#from`), `vauban-core` (`MethodShape`)
- **Surfaced by**: a probe written while fixing BUG-20261004-02 and -05, which would otherwise have widened it (`fv2-probe-generic.log`, probe files deleted).
- **Symptom**: a bean that inherits a method whose parameter or return type is a type variable its supertype binds — `echo(T)` of `class Bean extends Base<String>`, or a default `label(T)` of `implements Labeled<String>` — breaks the build: the `$$Intercepted` source the processor renders for an intercepted bean, and the `_ClientProxy` source it renders for a normal-scoped bean, declare `echo(java.lang.Object)`, which javac rejects (`name clash: echo(Object) … and echo(String) … have the same erasure, yet neither overrides the other`, then `incompatible types`). Pre-existing for generic superclass methods on both sources and for generic default methods on the `$$Intercepted` source; BUG-20261004-02 would have added the default methods of the client proxy, and BUG-20261004-05 every bean bound through a generic base-class method (a repository bound through its base class), which until then fell back to the run-time generator and worked on the class path.
- **Minimal reproduction**: `GenericInheritanceModulePathTest` with `GenericScopedService` (`@ApplicationScoped @Audited extends GenericBase<String> implements Labeled<String>`) and `GenericBoundService` (`@Dependent extends GenericBase<String>`, bound only through `@Audited store(T)`): vauban-module-it did not compile (`fv2-red-generic-moduleit.log`).
- **Cause**: the source front-ends render each method with the erasure of its declaration, which is the right descriptor for a class file (the bytecode emitters override by descriptor) but not an override Java source accepts when the bean sees the type variable as a concrete type.
- **Fix**: `MethodShape` also carries the method's signature as a member of the bean (`Types#asMemberOf`, erased), identical to the declaration unless a type variable is bound. `InterceptedSourceRenderer` declares the override with it (javac adds the bridge for the descriptor) and keeps the `$$super$` bridge on the erased declaration — the run-time front-end's descriptor, which `getMethod()` resolves to the declaration — casting each argument to its member type for `super.<name>(…)`. `ClientProxyShapeFromElements#from`, rendered as source only, uses the member signature; `#fromColocated` and the run-time shapes, emitted as bytecode, keep the descriptor. Tests: `GenericInheritanceModulePathTest` (four cases: a generic superclass method and a generic default method through the build-time client proxy, a generic return type, a bean bound only through a generic superclass method; the interceptor sees the erased declaration), `ClientProxyInheritanceCrossCheckTest#genericMethodsUseTheMemberSignatureInSource`.
