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
- [ ] Wire remaining no-arg sites: custom Context (~l.1031), synthetic creator/disposer/observer
      (~1234/1298/1325)
- [ ] Producers (invokeMethod) / producer fields / disposers — generated invokers (B2)
- [ ] @Inject-constructor beans (args) — provider `create(name, args)` overload
- [ ] **APT generation** of `_VaubanComponents` + emit `provides` lint/suggestion (the big piece)
- [ ] Module-path proof via jlink (ServiceLoader path, no opens)

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
