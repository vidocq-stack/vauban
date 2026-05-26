# Vauban — Reproducible bugs

Internal tracker. Minimalist format: short id, date, symptom, repro,
suspected cause, status. Updated on every investigation.

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
