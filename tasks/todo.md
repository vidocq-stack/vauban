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
- [ ] APT path (`VaubanProcessor`/`ComponentProviderGenerator`) still emits a single common-package
      provider — mirror the per-package split for consistency (the spec-wrapper proofs use the
      plugin; examples use APT and still work with the single provider, so non-urgent).

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
