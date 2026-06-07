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
