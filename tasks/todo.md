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
- Note: legacy `vauban-bce-runtime.list` still written as fallback; remove once Brique B/D
  cover all jars and module-path validation passes.

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

### Brique D — Packaging plugin freezes non-APT jars (vidocq-runtime-maven-plugin)
- [ ] `VidocqGenerateMojo` writes patch + factories + marker for untreated deps

### Verify (module-path, not classpath)
- [ ] jlink cassini-rest / mansart-h2 boot + endpoint without opens; post-jlink smoke test

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
