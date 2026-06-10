# Vauban - Work plan

## Completed phases
- **Phase 0**: Project bootstrap (Maven 4 parent POM, 9 JPMS modules) ✅
- **Phase 1**: Class indexer (ClassFileScanner, JarScanner, 64 tests) ✅
- **Phase 2**: CDI Language Model (declarations, types, AssignabilityRules, 33 tests) ✅
- **Phase 3**: Bean discovery and resolution (BeanDiscovery, BeanResolver, DependencyGraph, 25 tests) ✅
- **Phase 4**: Code generation (BeanFactoryGenerator, ClientProxyGenerator via Class-File API, 8 tests) ✅
- **Phase 5**: APT processor (VaubanProcessor, ElementScanner, full pipeline, 3 tests) ✅
- **Phase 6**: Runtime container (VaubanContainer, ApplicationContext, RequestContext, DependentContext, 7 tests) ✅
- **Phase 7**: JUnit 6 integration (@VaubanTest, VaubanExtension, 5 tests) ✅
- **Phase 8**: TCK Runner (TCK SPI, Arquillian adapter, 1826 tests executed) ✅
- **Phase 11**: Module tooling (ModuleAnalyzer, ModuleReport, 7 tests) ✅

## Phase 10: CDI Lite TCK validation 🚧
- [x] Deployment validation: proxyable types, constructors, interceptors
- [x] Maven 4 TCK run: 917 tests, 218 failures, 148 skipped
- [x] AroundConstruct support, interceptor bytecode (Class-File API)
- [x] Deployment exception handling (DefinitionException vs DeploymentException)
- [x] Constructor injection in interceptors, @Priority ordering
- [x] Matching interceptor bindings with @Nonbinding
- [x] Exclusion of disabled alternatives
- [x] Improved type assignability (raw vs parameterized, wildcards)

## Phase 12: Static BCE metadata — drop `opens … to io.vidocq.vauban.core` 🚧
Branch (local): `pr/ybl/wip-bce-static-metadata` (rename to `pr/ybl/{issue}-…` before push).
Goal: runtime never reflects a BCE nor a bean on the module-path → no `opens` needed.

### Brique A — Serialize the @Enhancement *result* (replace reflective replay)
Insight: runtime only applies `config.getAddedAnnotations()` as
`new AnnotationInfo(DotName.of(fqn), Map.of())` (VaubanContainerBuilder:766). The effective
enhancement result reduces to `target FQN → [annotation FQN…]` — trivially serializable.
- [x] (TDD) `EnhancementPatchSerializerTest` — round-trip `Map<String,List<String>>` (5 green)
- [x] `EnhancementPatchSerializer` (vauban-core/extensions), JDK-only, Properties,
      `META-INF/vauban-enhancements.properties`
- [x] APT writes the patch (`VaubanProcessor.writeEnhancementsPatch`) — Test 6 green
- [x] Runtime reads + applies the patch (`VaubanContainerBuilder.loadEnhancementPatch` +
      `effectiveReplayPairs`); reflective replay kept only as legacy fallback for
      patch-less targets — Test 7 proves scope applied with NO BCE on the classpath
- [x] Full `clean install` green (no regression); no `instantiateBce` on the patched path
- Note: the **reflective fallback path itself is permanent by design** — it is the only way to
  handle jars not frozen by the APT/plugin (class path, open/automatic modules, third-party
  CDI jars), and it works *without* opens for the class path / automatic modules; it only fails
  for an explicit module that neither opens its package nor ships a provider (unavoidable for any
  container, Weld included). What is removable is just the **legacy `vauban-bce-runtime.list`
  artifact** (now superseded by the enhancement patch), once Brique B/D cover our own modules.
- [x] Surface the residual instead of leaving it silent: `instantiate()` logs once-per-class at
      DEBUG when it falls back to reflection; `VaubanLookup` failure message now points to *both*
      options (generate a `_VaubanComponents` provider **or** add the `opens`).

### Brique B — Co-located instantiation via VaubanComponentProvider (contract = option A)
Contract chosen: APT generates `app._VaubanComponents implements VaubanComponentProvider`
(in-module `new X()`), exposed via `provides … with` in the app module-info; vauban loads it
via ServiceLoader and consults it before any reflective `newInstance`.
- [x] SPI `io.vidocq.vauban.core.VaubanComponentProvider` (exported) + `uses` in module-info
- [x] `ComponentProviders` resolver (ServiceLoader + explicit) — 5 unit tests
- [x] Runtime wiring of managed-bean + BCE-discovered `newInstance` sites via `instantiate()`
- [x] `addComponentProvider(...)` programmatic API; runtime test proves bean built by provider
- [x] clean install green, no regression
- [x] Wire custom Context site (provider-first, in build())
- [ ] Synthetic creator/disposer/observer sites are in `static` methods — need
      `componentProviders` threaded through `registerSyntheticBean`/`buildSyntheticObserver`
      AND the generator to emit entries for those classes; deferred (no observable effect alone)
- [ ] Producers (invokeMethod) / producer fields / disposers — generated invokers (B2)
- [x] @Inject-constructor beans (args) — `create(String, Object[])` SPI overload; container
      resolves args (qualifiers/generics stay in vauban-core), provider runs `new X(args…)`
      in-module. Wired in `VaubanContainer.createManagedBeanFactory` (provider-first); runtime
      test `instantiatesConstructorBeanViaProvider` green
- [x] **APT generation** of `_VaubanComponents` (source switch `new X()`) + class-path service
      file + NOTE advising the module-info `provides` — covers public no-arg managed beans
      (generator unit test + APT integration test: generated source compiles)
- [x] Extend generation to @Inject-constructor beans (resolve args) — B2: `instantiableCtorParams`
      selects the injected ctor (mirrors bean discovery), emits `new X((Dep) args[i]…)` casting
      the container-resolved args; non-nameable params (primitive/type-var/wildcard) fall back to
      reflection. Generator + APT integration tests green
- [x] Module-path proof via jlink — cassini-rest example: `provides VaubanComponentProvider
      with …_VaubanComponents` (APT-generated class) compiles (javac round-ordering OK) and the
      jlink image boots + serves `/api/todos` CRUD with the provider path active, no
      ServiceConfigurationError / InaccessibleObjectException. Also surfaced + fixed the nested-bean
      package-clash regression. NOTE: cassini-rest keeps an *unqualified* `opens` for JSON-B, so it
      proves the provider LOADS/instantiates in module-path but not the *opens removal* — that needs
      a CDI-only wrapper (cervantes-jwt / knock) whose sole opens is `to io.vidocq.vauban.core`.

### Brique C — ServiceLoader fallback for residual BCE instantiation ✅
(= existing backlog item "ExtensionLoader via ServiceLoader")
- [x] `uses BuildCompatibleExtension` in vauban-core/module-info
- [x] `BceProcessor.instantiateBce` ServiceLoader-first; reflection only as fallback
      (class path / unnamed module / not-declared-as-service)
- [x] clean install green; BCE-service tests (replay Test 2/3/4) + ExampleAppEndToEnd pass
- Note: ServiceLoader vs reflection is indistinguishable on the class path; the no-opens
  proof comes from the jlink module-path validation (Brique verify).

### Brique D — Build/packaging plugins freeze non-APT modules
Note: the CDI-vauban wrapper modules (cervantes/cyrano/knock/dirac) are NOT processed by the APT
— they use `vauban-maven-plugin`'s `generate` goal (which already runs the BCE @Enhancement to
enrich its index). So freezing them is this plugin's job, not only `vidocq-runtime-maven-plugin`.
- [x] `vauban-maven-plugin` (`VaubanGenerator`) serializes the @Enhancement result to
      `META-INF/vauban-enhancements.properties` (Brique A for plugin-processed modules) — it
      already computed `enhMods`, now it writes the patch. Test:
      `shouldEnrichNonCdiBeanViaBceEnhancement` asserts the patch records the added @RequestScoped.
- [x] `vauban-maven-plugin` generates a bytecode `_VaubanComponents` (no javac in the plugin phase)
      for the module's public no-arg beans/contexts → lets cervantes' `JsonWebTokenContext` & JAX-RS
      providers be instantiated in-module. `ComponentProviderClassGenerator` (vauban-core, Class-File
      API) emits `create(String)` as an equals-chain; `VaubanGenerator` collects this module's no-arg
      beans (in projectClassesDir, top-level), writes `<pkg>/_VaubanComponents.class` + the class-path
      service file. Tests: bytecode loads & instantiates; plugin writes provider+service for a project
      bean. STILL TODO to actually drop the opens: hand-add `provides … with <pkg>._VaubanComponents`
      to each module-info, and a module-path vehicle (jlink JWT / docker compose) to prove it.
- [ ] Producer-method invokers (Brique B producers) → needed to drop `opens …cdi.internal`.
- [ ] `vidocq-runtime-maven-plugin` (`VidocqGenerateMojo`) writes patch + factories + marker for
      untreated deps brought at packaging time.
- [ ] Optional `failOnReflectiveFallback` build flag: fail packaging if any bean would still need
      the reflective path → guarantees a fully static / AOT-pure (GraalVM-ready) image when wanted,
      while the permissive reflective fallback stays the default for robustness/repackaging.

### SPI placement + Weld portability (done)
- [x] Moved `VaubanComponentProvider` from `vauban-core` to **`vauban-api`** — a module names it in
      `provides`, so the service type must be compile-visible; wrappers have vauban-core at test scope
      only. Wrappers now `requires static io.vidocq.vauban.api` (dep in `provided` scope = non-transitive).
- [x] `@Vetoed` on the generated `_VaubanComponents` (both generators) — Weld in bean-discovery-mode=all
      skips it (would otherwise NoClassDefFoundError on the absent SPI super-interface). Empirically
      verified: a module with `requires static api` + `provides api.Svc with …` boots with the api
      module ABSENT (provides inert, no `uses`) → confirms Weld pulls neither vauban-api nor vauban-core
      at runtime.

### Cervantes wiring (done — provider path active, opens still kept)
- [x] cervantes-cdi-vauban + cervantes-jaxrs: `requires static vauban-api` + `provides … with
      <pkg>._VaubanComponents`; build green, plugin round-ordering holds (provider .class generated
      before module-info compiles). Context (cdi) & @Provider beans (jaxrs) now provider-instantiated.
- [ ] Producer-method invokers (Brique B producers) — still reflective, so `opens …cdi.internal` stays.

### Verify (module-path, not classpath) — DONE for cervantes
- [x] Module-path JWT vehicle built: `vidocq/…/vidocq-runtime-cervantes-jwt-example` (jlink image,
      bundles all cervantes modules). Proven: `/api/secured/admin` with an RSA-signed admin token →
      200, no-token → 401, user token → 403, `/public` → 200 — WITH the `opens … to
      io.vidocq.vauban.core` REMOVED from cervantes-jaxrs and cervantes-cdi (kept `cdi.internal`).
      → the vauban opens are confirmed droppable; committed on cervantes branch.
- [ ] **cassini residual reflection (NEW, separate from vauban)**: the vehicle surfaced that
      `io.vidocq.cassini.core` (AdapterRegistry / FieldInjector) reflects on the `@Provider` beans
      and wants `opens … to io.vidocq.cassini.core` — non-fatal (reflective fallback), pre-existing.
      To reach truly zero-opens, cassini must generate an in-module adapter/field-injector for
      `@Provider`/resource beans (cassini-codegen concern), OR these modules add the cassini-qualified
      opens. Not a vauban task.
- [ ] Producer-method invokers (Brique B producers) → still needed to drop `opens …cdi.internal`,
      and to let a resource `@Inject JsonWebToken` (the example uses SecurityContext to avoid the
      cross-module producer, which the APT does not resolve at compile time — also worth fixing).
- [ ] jlink cassini-rest / mansart-h2 boot + endpoint without opens; post-jlink smoke test

## Phase 13 — Brique B field-injector codegen (zero-reflection field injection)

Field injection (`@Inject` on a package-private field) was the last forced `setAccessible` —
it kept the `opens … to io.vidocq.vauban.core` even after in-module instantiation (Phase 12).
cervantes side-stepped it with `@Context`; this phase removes it generally.

- [x] SPI: `VaubanComponentProvider.injectField(bean, className, fieldName, value)` default → false
      (vauban-api). Generated impl does a plain in-package `putfield` — no reflection.
- [x] `ComponentProviders.injectField(...)` delegator (first provider that returns true wins).
- [x] `BeanInjector.writeField(...)` choke-point: tries the generated provider first (keyed on the
      field's declaring class), falls back to `VaubanLookup.setField` (reflective) — all 5 inject
      sites routed through it. Default behaviour unchanged when no provider owns the field.
- [x] Bytecode generator (`ComponentProviderClassGenerator`, plugin path): `FieldInject` record +
      3-arg `generate` overload emitting the `injectField` putfield chain. 1-arg overload kept.
- [x] APT source generator (`ComponentProviderGenerator`): `FieldInject` record + `generateFrom`
      overload emitting the nested `switch(className)/switch(fieldName)` injectField.
- [x] Plumbing: `VaubanGenerator` (plugin) + `VaubanProcessor` (APT) collect each managed bean's
      non-private, non-static `@Inject` fields IN THE PROVIDER'S OWN PACKAGE (a putfield can't reach
      a package-private field across packages) and pass them to the generators. Private @Inject
      fields are skipped with an explicit NOTE/warning (no silent cap) — they still need the opens.
- [x] Unit tests both generators; vauban `clean install` green.
- [x] **PROVEN on knock (module path, jlink)**: dropped `opens io.vidocq.knock.jaxrs to
      io.vidocq.vauban.core` (added `requires static vauban.api` + `provides … with
      io.vidocq.knock.jaxrs._VaubanComponents`). Vehicle `vidocq/…/vidocq-runtime-knock-health-example`:
      `GET /api/health` → 200 `{"status":"UP","checks":[{"name":"app","status":"UP"}]}`,
      `/health/live` → 200, `/health/ready` → 200. The resource was instantiated AND its package-private
      `@Inject HealthCheckRegistry registry` field injected by the generated provider — zero reflection,
      zero `opens … to vauban.core`. Only residual warning is `io.vidocq.cassini.core` (separate).
- [ ] Limitation (documented, not silent): multi-package modules need one provider per package for
      field injection (the common-package provider only injects beans in its exact package). Today the
      other packages fall back to reflection. Per-package providers = follow-up.
- [ ] knock-cdi-vauban `.internal` opens STAYS: its beans are field-injected AND have `@Observes`
      (method invocation) — needs the field-injector for package-private fields PLUS an observer-method
      invoker codegen. Tracked under Brique B producers / observer-invoker.

## Phase 14 — Method-invoker codegen (producers / observers / disposers / lifecycle / initializers)

Reflective method invocation was the next forced `setAccessible`/`unreflect` keeping the
`opens … to vauban.core` on `.internal` packages (cervantes producers, knock observer).

- [x] SPI: `VaubanComponentProvider.invoke(target, className, methodId, args)` (default →
      `NOT_INVOKED` sentinel). Generated impl does a direct in-package call (invokevirtual/
      invokestatic) — no reflection. Exceptions propagate as-is.
- [x] `ComponentProviders.invoke(...)` delegator (does NOT swallow the target method's exceptions).
- [x] Choke-point A: `VaubanLookup.invokeMethod` consults the provider first (keyed on declaring
      class + `methodId(Method)`), reflective fallback otherwise. `methodId`/`typeKey` match the
      generators' erasure exactly (binary names, `[]` arrays).
- [x] Choke-point B: `EventDispatcher` observer invocation now routes through
      `VaubanLookup.invokeMethod` (removed the eager `makeAccessible` + raw `method.invoke`; the
      reflective lookup is now lazy, in the fallback). Observer/event tests still green.
- [x] Both generators emit `invoke`: `ComponentProviderClassGenerator` (plugin bytecode) +
      `ComponentProviderGenerator` (APT source). Select methods with `@Produces`/`@PostConstruct`/
      `@PreDestroy`/`@Inject` or a param `@Observes`/`@ObservesAsync`/`@Disposes`; skip non-nameable/
      nested-`$` params and non-void primitive returns (warned, no silent cap). Unit-tested (the
      bytecode test actually invokes the generated methods).
- [x] vauban `clean install` green — capability complete + tested, zero regression.
- [x] **Per-package providers (plugin path) — DONE.** `VaubanGenerator` now emits ONE
      `_VaubanComponents` per package that has beans (co-located → can `new`/inject/invoke that
      package's package-private members), and lists them all in the class-path service file; the
      module-info lists all via `provides … with A, B;`. `isInstantiableNoArg` relaxed: a package-
      private top-level class with a non-private no-arg ctor is now instantiable. Removes the
      Phase-13 multi-package field-injection limitation too.
- [x] **PROVEN knock-cdi-vauban (module path, jlink)**: dropped `opens io.vidocq.knock.cdi.internal`.
      The `.internal` provider create()s the package-private `HealthCheckRegistrar`/
      `KnockCdiHealthCheckRegistry`, injects Registrar's package-private `@Inject` fields, and fires
      its `@Observes @Initialized(ApplicationScoped)` observer (invoke) — `/api/health/live` → 200
      with the discovered `app` check (observer ran). knock is now ZERO opens-to-vauban.core.
- [x] **PROVEN cervantes-cdi-vauban (module path, jlink)**: dropped `opens
      io.vidocq.cervantes.cdi.internal`. Two providers (`.cdi` + `.cdi.internal`); the `.internal`
      one invokes the `@Produces jwtValidator()` and `currentToken(JsonWebTokenContext)`. JWT
      endpoint: `/admin` admin-token → 200, user-token → 403, no-token → 401, `/public` → 200 — token
      validation ran in-module. cervantes is now ZERO opens-to-vauban.core. (Both still show only the
      separate `io.vidocq.cassini.core` adapter-generation warning — a cassini-codegen concern.)
- [x] APT path (`VaubanProcessor`) mirrored to per-package: groups components/fields/methods by
      package, emits one `_VaubanComponents` source per package, lists all in one service file;
      `instantiableCtorParams` relaxed to package-private (co-located source provider). Removed the
      now-unused `commonPackage`/`commonPrefixBySegments`. Build green. (End-to-end module-path proof
      comes when a wrapper actually adopts APT — see the plugin-vs-APT note below.)
- [x] **Plugin = external jars, APT = own code (maintainer's intent) — pilot DONE.** knock-cdi-vauban
      (no interceptor beans) migrated from the vauban-maven-plugin to `vauban-processor` on the
      `annotationProcessorPath`: the APT now emits its `_ClientProxy`/`_Factory`/`vauban-beans.list`/
      per-package `_VaubanComponents` at compile time; the separate module-info compile runs
      `proc=none`. PROVEN on the knock-health jlink example (/api/health 200, no vauban.core opens).
      Remaining wrappers (cervantes/dirac/ravel/heisenberg/cyrano/humboldt/cassini-cdi) can follow the
      same pattern; ones WITH interceptor beans still need the plugin for `$$Intercepted` (the APT
      doesn't generate interceptor subclasses) — keep the plugin for those, or for external dep jars.
- [x] **Shared collector — plugin & APT now reuse one selection logic.** Extracted the descriptor
      records (`Component`/`FieldInject`/`MethodInvoke`/`PackageProvider`/`ProvidedClass`) and the
      `ComponentCollector` (erasure, instantiable-no-arg, field/method selection, per-package
      grouping) into `io.vidocq.vauban.indexer.codegen` (the module both paths already depend on).
      Both `ComponentProviderGenerator` (APT source) and `ComponentProviderClassGenerator` (plugin
      bytecode) consume the shared records; only the EMISSION (source vs bytecode) stays separate, so
      a change to selection now benefits both. Erasure unified (type-variable/wildcard fields/params
      now handled on the APT side too). Byte-for-byte behavior preserved — both vehicles re-proven.

## Phase 15 — `$$Intercepted` generation in the APT (own code → APT, plugin → external jars)

So a module's OWN intercepted beans are pre-generated at compile time (no runtime class definition,
no `opens … to io.vidocq.vauban.core` on the module path), making the APT feature-complete vs the
plugin. The runtime `InterceptorSubclassGenerator` emits a GENERIC subclass (chain resolved at
runtime), so only method SHAPES are needed → a ClassInfo/Elements front-end is feasible.

- [x] `MethodInfo.isFinal()` (vauban-indexer).
- [x] **Shared emitter** (vauban-core): split `InterceptorSubclassGenerator` into a neutral shape
      model (`TypeRef`/`MethodShape`/`CtorShape`/`InterceptedShape`) + `InterceptedEmitter.emit(shape)`,
      with `fromClass(Class)` front-end. `generate(Class)` = `emit(fromClass(...))` — runtime/plugin
      API unchanged. Golden + structural tests (note: `getDeclaredMethods()` order varies across JVM
      runs so the guarantee is within-run determinism + roundtrip + the existing interception tests).
- [x] **APT front-end** `InterceptedShapeFromElements.from(TypeElement, Elements, Types)`
      (vauban-processor) — inherited override-set via `Elements.getAllMembers`; cross-check test vs
      `fromClass` (same override set).
- [x] **APT wiring** (`VaubanProcessor`): detect interception targets via the **Elements API**
      (`getAnnotation(@InterceptorBinding)` on the annotation element — works for custom + method-level
      bindings, unlike the index, which doesn't resolve custom binding annotations), then emit
      `<bean>$$Intercepted` bytecode via the Filer. Compile-test proves the `.class` is emitted.
- [x] **Runtime skip** (`InterceptorBeanWrapper`): probe `Class.forName(<bean>$$Intercepted)` on the
      bean's loader first; only generate/define at runtime if absent. This is what removes the
      module-path deep-access for pre-generated targets.
- [ ] **Module-path jlink proof** — STILL NEEDED. `vauban-examples` E2E runs on the CLASSPATH (`-cp`),
      so it can't exercise opens-removal. Build a jlink vehicle (vidocq, like knock/cervantes) with an
      intercepted bean compiled by the APT, target package NOT opened to vauban.core, interception
      fires. Fold into the dirac migration proof.
- [ ] **Étape 5 DEFERRED — interceptor-package opens via in-module `@AroundInvoke`.** Routing the
      interceptor's `@AroundInvoke` through the generated `invoke()` is blocked by the SPI `invoke`
      having NO `throws` clause while `@AroundInvoke` methods declare `throws Exception` (and @Retry /
      fault-tolerance depend on exact exception propagation). NOT needed for the migration goal — the
      interceptor beans keep their package open for reflective `@AroundInvoke` invocation. Revisit with
      a throwing-variant SPI or a wrapper-unwrap scheme.
- [ ] **Étape 6 — migrate the 3 interceptor wrappers** dirac/heisenberg/humboldt plugin→APT (same as
      the knock-cdi pilot), prove interception still works on the module path. Then the 7 no-interceptor
      wrappers (cervantes/ravel/cyrano/cassini/grimm/foy/…) as a mechanical sweep.

## Phase 16 — Java modernization + interceptor codegen hardening 🚧 (2026-06-10)

> Source: FABLE_REPORT.md workspace quality review. Baseline verified green before start
> (`./mvnw clean install` — all modules SUCCESS, BUG.md 10/10 FIXED, no open bug).

### 16a — Pattern-matching modernization (55 `else if instanceof` chains) ✅ 2026-06-10
- [x] `VaubanAnnotationBuilder` (23 chains) → exhaustive `switch` pattern matching
      (note: `Enum<?>[]` / `Class<?>[]` are not parseable as case labels — kept as
      instanceof inside the `default` branch)
- [x] `ManagedBean` (12 chains) — includes merging the three near-identical
      `collectTypes*` walkers into one `collectSupertypes(..., boolean addSelf)`
- [x] `BeanDiscovery` (7 chains) — includes deleting the private duplicate of
      `ManagedBean.resolveType` (~80 lines) and delegating to it
- [x] `DeploymentValidator` (3), `DisposerInvoker` (2) — `AssignabilityRules` (2) left
      as-is: chains are inner refinements inside already-modern pattern switches
- [x] Singles: `VaubanContexts`, `TypeHierarchyResolver` converted —
      `VaubanProcessor` (tests two different expressions, not a dispatch),
      `VaubanContainerBuilder` / `VaubanBeanManager` / `BeanInjector` (guards mixed with
      non-type conditions, marginal gain) deliberately left as-is
- [x] **Bug found & fixed along the way**: VAU-TYP-001 — divergent equals/hashCode
      across the 3 synthetic ParameterizedType copies (see BUG.md)
- [x] Constraint respected: every converted switch carries `case null` (or yields the
      original null behavior) and preserves guard order
- [x] Full `./mvnw clean install` green + CDI 4.1 Lite TCK **774/774 PASS**

### 16b — Shared IR between InterceptedEmitter (bytecode) and InterceptedSourceRenderer (source) ✅ 2026-06-10
- [x] Study: the IR (`InterceptedShape`/`MethodShape`/`CtorShape`/`TypeRef`) was already shared;
      the residual duplication was the **semantic decisions** repeated in both renderers:
      `targetInvokerNames()` copy-pasted verbatim (the VAU-INT-001 fix had to land twice),
      the primitive→wrapper table, the unbox-accessor table, and the `$$…` name literals
- [x] Hoisted into the IR as single authority:
      `InterceptedShape.targetInvokerNames()` / `subclassName()` / `superBridgeName()` +
      naming constants (`SUBCLASS_SUFFIX`, `SUPER_BRIDGE_PREFIX`, `TI_PREFIX`, `INIT_METHOD`,
      `FIELD_*`); `TypeRef.wrapperBinaryName()` / `unboxAccessorName()` / `sourceName()`
      (`wrapperClassDesc()` now derives from `wrapperBinaryName()`)
- [x] Both renderers consume the authority; core-module consumers aligned too
      (`InterceptorManager`, `InterceptorBeanWrapper`, `InterceptorSubclassGenerator`,
      `VaubanInvocationContext`). The `$$Intercepted` literal stays in vauban-indexer /
      vauban-maven-plugin recognition sites — module direction (core depends on indexer)
      forbids importing the constant there; it is a cross-module protocol constant
- [x] Out of scope (separate chantier): the client-proxy pair
      (`RuntimeClientProxyGenerator` / `ClientProxySourceRenderer`) works on `TypeMirror`,
      not `TypeRef` — unifying it means migrating it to the IR first
- [x] TDD regression tests: `InterceptedShapeTest` (5 — incl. VAU-INT-001 overload cases),
      `TypeRefTest` (4 — incl. VAU-INT-002 int[] guard, VAU-PRX-003 nested-class names)
- [x] Validation: `GoldenBytecodeTest` 4/4 (byte-for-byte output preserved), full reactor
      green, CDI TCK **774/774 PASS**, cross-project smoke heisenberg `clean install` green

### 16c — Client-proxy triple unified on a shared IR (ClientProxyShape) ✅ 2026-06-10
> Study result: THREE generators — `RuntimeClientProxyGenerator` (core, bytecode from
> `Class<?>`, full hierarchy walk, simplest-ctor defaults, MH dispatch),
> `ClientProxyGenerator` (processor, bytecode from indexer `ClassInfo`, declared methods
> only, no-arg ctor), `ClientProxySourceRenderer` (processor, source from `TypeElement`,
> declared only, keeps `throws`). ~300 lines of Class-File emission duplicated between the
> two bytecode generators. The semantic differences are intentional per-front-end choices —
> they belong in the DATA (the shape), not in duplicated emitters.
- [x] `ClientProxyShape` + `ProxyMethodShape` IR in `core.proxy` — naming authority
      (`_ClientProxy`, `$$delegate`, `$$setDelegate`, `$$mh_<name>_<n>` sequence) +
      `ClientProxyShapeTest` (TDD)
- [x] `TypeRef.fromTypeInfo(TypeInfo)` factory (erasure mapping mirrors the old `toClassDesc`)
- [x] Single `ClientProxyEmitter.emit(shape)` in core; both bytecode generators became
      shape-building front-ends with unchanged public APIs (`generate(Class)` /
      `generate(ClassInfo)` / `proxyClassName(Class)`); ~280 duplicated emission lines deleted
- [x] `ClientProxyShapeFromElements` (reuses `InterceptedShapeFromElements.typeRefOf`, made
      public); `ClientProxySourceRenderer` renders from the shape (TypeRef.sourceName,
      thrownTypes for the `throws` contract)
- [x] Front-end predicates (`shouldProxy` on Method / MethodInfo / ExecutableElement) stay
      per-front-end — input models differ; the OUTPUT shape is the shared contract
- [x] JPMS: `core.proxy` gets a **qualified** export to `io.vidocq.vauban.processor` only
      (non-modular Maven plugin reads the jar from the classpath, unaffected)
- [x] Validation: full reactor green, CDI TCK **774/774 PASS**, heisenberg `clean install` green

### 16d — Module-path regression vehicle (vauban-jpms-it) ✅ 2026-06-10
> Every historical JPMS bug (VAU-INT-001..005, VAU-PRX-003) was caught by DOWNSTREAM TCKs,
> never by the vauban suite — the class path does not enforce opens/exports. New reactor
> module `vauban-jpms-it`, modeled on `mansart-transactions-cdi-jpms-it`.
- [x] Named module, **zero `opens`**, `provides VaubanComponentProvider with _VaubanComponents`
      (APT build-time); surefire runs the test ON the module path (main module-info)
- [x] Fixtures pin the whole historical bug surface: `work()` overloads ×3 (VAU-INT-001),
      `int[]`/`long`+`double` params (VAU-INT-002), `throws IOException` through the
      source-rendered subclass (VAU-INT-003), `@Audited` MARKER binding (VAU-INT-005) +
      `@Interceptor` instantiated in-module (VAU-INT-004), `Outer.Inner` nested types
      (VAU-PRX-003), in-module field injection (Brique B putfield)
- [x] Sentinel test: asserts `module.isNamed()` + `opens().isEmpty()` — fails loudly if
      surefire ever silently degrades to the class path
- [x] 7/7 green on module path, full reactor green, CDI TCK 774/774 PASS

### 16e — God classes, slice 1: BeanDiscovery decomposition 🚧 2026-06-10
- [x] `ObserverDisposerDiscovery` (275 L) extracted — observers + disposers discovery,
      `BeanDiscovery` keeps the public facade and delegates
- [x] `InterceptorDiscovery` (270 L) extracted — interceptor discovery + binding resolution
      (the VAU-INT-004/005 zone); dead code dropped on the way (`seenClasses` set never
      read, empty TCCL block in `discoverInterceptors`)
- [x] Shared predicates relaxed to package-private (`isVetoed`, `hasBeanDefiningAnnotation`,
      `isStereotype`, `isDisabledAlternative`, `isQualifierAnnotation`, `extractPriority`,
      `hasAnnotation`, the DotName constants) — collaborators hold a host back-reference
- [x] BeanDiscovery: **2185 → 1677 lines (−23%)**; full reactor green, TCK 774/774 PASS
- [x] Slice 2: `StereotypeResolver` (280 L) extracted — is-a-stereotype detection + the four
      transitive stereotype lookups (@Alternative, @Priority, @Named defaulting, scope),
      all sharing the index-first/reflection-fallback duality and cycle guards.
      BeanDiscovery: **1677 → 1465 lines** (cumulative −33%); reactor green, TCK 774/774
- [x] Slice 3: `QualifierResolver` (266 L) extracted — computeQualifiers* family with their
      CDI-spec variations (bean vs injection-point vs observer defaulting), repeatable
      unwrapping, @Named defaulting, @Inherited lookup, is-a-qualifier detection.
      BeanDiscovery: **1465 → 1270 lines** (cumulative −42%); reactor green, TCK 774/774
### 16f — God classes, slice 2: BceProcessor decomposition ✅ 2026-06-10
- [x] `EnhancementApplier` (440 L) extracted — applies recorded @Enhancement modifications
      back onto bean/interceptor/observer descriptors + the annotation bridge only it used
      (qualifier/binding detection, AnnotationInfo↔QualifierInstance, BuiltAnnotationInfo proxy)
- [x] `SyntheticBeanConverter` (185 L) extracted — @Synthesis builder → BeanDescriptor
      (shared runtime/APT), with the VAU-BCE-001 qualifier-member capture helpers
- [x] `BceTypeMatcher` (186 L) extracted — the seven matches* filters (index-based and
      classloader-based); BceEnhancementTest now calls it directly (reflection dropped)
- [x] `ExtensionMethodValidator` (163 L) extracted — @Enhancement/@Registration signature
      validation + invoker argument-lookup validation, with their parameter-type sets
- [x] BceProcessor: **1541 → 739 lines (−52%)** — now pure phase orchestration
      (discovery/enhancement/registration/synthesis/validation invocation)
- [x] Each slice: full reactor green + CDI TCK 774/774; heisenberg (BCE consumer) smoke green
### 16g — God classes, slice 3: VaubanContainerBuilder decomposition ✅ 2026-06-11
- [x] `ContainerScanner` (390 L) extracted — package/dir/JAR scanning, vauban-beans.list,
      BCE ServiceLoader contract, forced beans.xml discovery, encrypted SJAR + plugin SPI;
      public fluent API stays on the builder as one-line delegators (scanLocal kept whole:
      StackWalker caller detection needs the builder as walked frame)
- [x] `SyntheticComponentRegistrar` (340 L) extracted — @Synthesis bean/observer registration
      (descriptor + factory + disposer) shared by the runtime BCE path and the APT-frozen
      metadata path; BceEnhancementTest reflection retargeted
- [x] BCE artifact loaders (runtime replay list, enhancement patch) and
      buildCompositeClassLoader stay: they are build()'s own helpers
- [x] VaubanContainerBuilder: **1417 → 804 lines (−43%)** — fluent config + build() orchestration
- [x] Full reactor green + CDI TCK 774/774 per slice; heisenberg smoke green

**God-classes campaign complete**: BeanDiscovery 2185→1270, BceProcessor 1541→739,
VaubanContainerBuilder 1417→804. Remaining large-but-cohesive: build() (~400 L, documented
orchestrator), VaubanContainer (runtime API surface).

## Backlog (deferred)
- [ ] `DirectoryScanner` - directory scanning
- [ ] Binary serialization IndexWriter/IndexReader
- [ ] Complete `InterceptorSubclassGenerator`
- [ ] `DecoratorSubclassGenerator`
- [ ] `ObserverInvokerGenerator`
- [ ] Complete BCE pipeline (5 phases @Discovery etc.)
- [ ] `ExtensionLoader` via ServiceLoader
- [ ] Event system (Event<T>, @Observes)
- [ ] Complete `Instance<T>` programmatic lookup
- [ ] Built-in beans (BeanManager, Event, Instance)
- [ ] `@MockBean` for JUnit
- [ ] Maven goals (vauban:index, vauban:module-analyze)
