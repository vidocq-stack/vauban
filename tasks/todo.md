# vauban#70 — Annotation metadata at build time, no reflection on the matching hot path — PLAN, awaiting confirmation

Issue: https://codefloe.com/Vidocq/vauban/issues/70 · Mapped on `main` @ ceac964, 2026-09-14.

## Findings that shape the plan

- **Two qualifier engines, two rule sets.**
  - Boot validation (`QualifierMatcher`, data from the boot bytecode scan) calls `Class.forName`
    (vauban-core's own loader), `getDeclaredMethods` and `isAnnotationPresent(@Nonbinding)` on every
    comparison, and never applies member defaults (`@Q` against `@Q("default")`).
  - Run-time injection (`VaubanBeanManager.getBeans`) scans every bean, rebuilds bean qualifiers as
    `java.lang.reflect.Proxy` on every `getQualifiers()` call, and reads members with `Method.invoke`
    on both sides.
- **Qualifiers re-read on every bean creation**: constructor, initializer, producer and disposer
  parameters go through `Parameter.getAnnotations()`; field injection points build
  `QualifierHelper` proxies.
- **Observers and interceptors**: one proxy per observer qualifier per fire; the generated
  intercepted subclasses call `getDeclaredMethod` and `resolveChainForMethod` on every business call.
- **Five annotation proxy handlers**, none honouring `java.lang.annotation.Annotation#equals/hashCode`
  (hashCode 0, equals always false or identity, arrays as `Object[]`, nested members as `null`).
- **Nothing crosses from build time to run time.** The processor index never leaves javac, neither
  scanner records member defaults, and the processor names nested annotation, enum and class values
  by their canonical name (`Outer.Q`) where the bytecode scan uses the binary name (`Outer$Q`).
- **`AnnotationLiteral` (CDI API 4.1.0) reflects** in its own `equals`, `hashCode` and `toString`
  (`getDeclaredMethods`, `setAccessible`, `invoke`): the container must never depend on them.
- **Verification gaps.** The CDI TCK runner deploys through the boot path (no processor), so a green
  TCK proves the fallback, never the generated path. The only TCK tests of literal/container
  equivalence (`QualifierEquivalenceTest`, `InterceptorBindingEquivalenceTest`) belong to `cdi-full`
  and are excluded. PR CI runs no TCK, and the main CI never reads the TCK reports
  (`testFailureIgnore=true`).
- **Suspected latent bugs**, each confirmed by a failing test before it is fixed, then logged in
  BUG.md: defaults ignored by boot validation; `@Nonbinding` silently lost when `Class.forName` uses
  the wrong loader; extension-declared non-binding members ignored by events and interceptors; async
  observers never comparing members; `QualifierResolver.toAnnotationInfo` and `EnhancementApplier`
  dropping or stringifying array, nested, enum and Class members; the proxy contract bugs above.

## Target design

1. **Annotation type metadata as data**: `AnnotationTypeInfo(name, members)` holding, per member, its
   type, default value and whether it is non-binding. It lives in vauban-indexer and both scanners
   produce it (`ExecutableElement.getDefaultValue()`, the `AnnotationDefault` attribute).
2. **One normalized key**, `AnnotationKey`: type plus binding members, defaults applied, `@Nonbinding`
   and extension non-binding members removed, nested annotations normalized. Record equality is the
   CDI matching rule, so matching becomes set membership on keys computed once.
3. **`AnnotationTypes` registry** (vauban-core): generated metadata first, then a reflective read of
   the type's declaration (never `invoke`), once per type, cached, with the right class loader.
4. **Annotations in and out.** Programmatic qualifiers (`Instance.select`, `Event.select`,
   `BeanManager`) are converted once, when selected: generated reader, then built-in readers for the
   CDI and `jakarta.inject` types, then the reflective fallback. `Bean#getQualifiers` and the other
   API getters return cached instances: generated literal, then built-in literal, then one fallback
   proxy that honours the `Annotation` contract.
5. **Generated on the processor path**, for each annotation type used as a qualifier or an
   interceptor binding: metadata (always, it is data), a member reader and a literal class when the
   type is accessible from the generated package, exposed through new default methods on
   `VaubanComponentProvider`.
6. **`-Dvauban.annotations.reflection=allow|warn|forbid`**: `forbid` makes every reflective fallback
   throw. It proves the acceptance criterion in tests and shows a native-image user what still
   reflects.

Out of scope, filed as follow-ups: the Maven-plugin bytecode providers (they keep the fallback), the
per-call `getDeclaredMethod` in generated intercepted subclasses, class loading by name in discovery.

## Stages, one PR each

Every PR: TDD; `./mvnw -T1C clean install`; changed modules run under
`-Dsurefire.runOrder=alphabetical` and `reversealphabetical`; CDI TCK 774/774 read from
`vauban-tck-runner/target/surefire-reports`; AtInject TCK green; fixtures that could hide a
regression proven by mutation (lesson 16).

### PR 1 — Safety net and baseline (no behaviour change)
- [x] `vauban-bench` module: JMH 1.37, processor wired as in vauban-module-it, never installed or
      deployed, excluded from release, named in `reference.adoc`
  - [x] `Instance.select(literal).get()` on member qualifiers, `@Nonbinding` included
  - [x] creation of a `@Dependent` bean with qualified fields and constructor parameters
  - [x] event fired with member qualifiers; intercepted call with a member binding
- [x] `BENCH.md` created with the "before" entry (log-bench format, in English): BENCH-20260914-01
- [x] Characterization tests of today's correct behaviour on both engines: String, primitive, enum,
      Class, array and nested members; defaults; `@Nonbinding`; extension non-binding; `@Named`
      default name; repeatable qualifiers (`QualifierMemberResolutionTest`, `QualifierMemberEventTest`,
      `InterceptorBindingMemberTest`, `BeanQualifiersContractTest`, `ElementScannerMemberValueTest`,
      `SameModuleQualifierValidationTest`)
- [x] A failing test for each suspected bug, disabled with its BUG id, logged OPEN in BUG.md

PR 1 outcome: 13 bugs confirmed, BUG-20260914-01 to -13. Two readings had to be corrected on the way:
the extension non-binding interceptor test passed only because extension-declared bindings compare no
member at all (-08), and the first `@Inherited` String failure came from a fixture whose bean type sat
on an unindexed superclass. Where each bug gets fixed:
- PR 2: -01 member defaults, -04 class loader, -09 inherited members, -12 processor names
- PR 3a: -02 field members, -03 container-built instances, -10 enhancement-added members, and the two
  its own tests turned up: -15 synthetic components, -16 `AnnotationBuilder.member(name, Class)`
  (-02 was mapped to the run-time stage; field injection rebuilds its qualifiers through the very
  conversion 3a fixes, so it is fixed there)
- PR 3b: -05 context class loader, -06 async observers, -07 extension non-binding on observers
- PR 4: -13 compile-time validation of the module's own qualifiers
- PR 5: -08 extension-declared interceptor bindings
- Outside #70: -11 default name of a nested bean class

### PR 2 — Type metadata and normalized keys (boot validation)
- [x] `AnnotationTypeInfo`, member defaults in `ClassFileScanner` and `ElementScanner`
- [x] Processor names member value types by binary name, keeps primitive and array class literals
  - [x] superclass and interface names too, which were still canonical
- [x] `AnnotationTypes` registry: reflective read fallback, cache, class loader
- [x] `AnnotationKey` normalization: defaults, `@Nonbinding`, extension non-binding, nested
- [x] `QualifierMatcher` on keys; `QualifierResolver.toAnnotationInfo` lossless

PR 2 outcome: BUG-20260914-01, -04, -09 and -12 fixed, their 11 disabled tests enabled. Boot validation
compares `AnnotationKey`s built by one `AnnotationTypes` per container; `getBeans`, `InstanceImpl`,
`EventImpl`, `BeanInjector` and `EventDispatcher` still match reflectively until PR 3. Every fix proven by
mutation: defaults out of the key, `@Nonbinding` kept in it, vauban-core's loader alone, the former lossy
conversion of inherited members, canonical processor names. Sequential `mvn clean install` (`-T1C` races
in example-test): 748 tests, 0 failures. vauban-indexer, vauban-processor and vauban-core green in both
surefire orders. CDI TCK 774/774 and AtInject green, read from the reports.

### PR 3 — Run-time hot path on keys
Mapped on `main` @ 1ae44b7. Run-time matching today: `VaubanBeanManager#getBeans` rebuilds every
bean's qualifiers as proxies on each call and compares members with `Method.invoke`; field injection
rebuilds its qualifiers through `QualifierHelper` proxies that load types through the context class
loader and drop most member kinds; `EventDispatcher` compares observer proxies member by member,
and only names on the asynchronous path. Five proxy handlers build annotation instances, none of
them honouring `Annotation#equals`. Two PRs, because the instances come first: the run-time engine
compares what they return, and every later stage returns them from `getQualifiers()`.

#### PR 3a — Annotation instances and lossless member values
- [x] `AnnotationInstances`: one instance honouring `Annotation#equals`, `hashCode` and the declared
      member types (primitive arrays, enums, `Class`, nested annotations, defaults). It replaces the
      handlers of `QualifierUtils`, `QualifierHelper`, `EnhancementApplier`,
      `VaubanSyntheticBeanBuilder`, `VaubanSyntheticObserverBuilder` and `VaubanParameters`
- [x] Lossless lang-model conversion (`LangModelAnnotations`): `EnhancementApplier`, the synthetic
      builders and the registrar keep member values; `AnnotationBuilder.member(name, Class)` records a
      class, not its name; `SyntheticBeanConverter` reads members through `AnnotationValues`, the one
      reader left
- [x] `getQualifiers()` cached; CDI literals for `@Default`, `@Any` and `@Named`
- Fixes -03 and -10, and the two defects its tests turned up: -15 (synthetic components lose their
  qualifier's members) and -16 (`AnnotationBuilder.member(name, Class)` records a string)

#### PR 3b — Matching on keys
- [x] `AnnotationTypes#key(Annotation)`: `@Default`, `@Any`, `@Named` and container-built instances
      without reflection; any other annotation read once
- [x] Beans and observers hold keys, computed once
- [x] `getBeans`, `InstanceImpl`, `EventImpl`, `BeanInjector`, `EventDispatcher` (both paths) and
      `resolveObserverMethods` match keys; `Instance` and `Event` convert their qualifiers once
- [x] Field injection resolves the keys of the descriptor's injection point: no instance, no class
      loading
- [x] Static non-binding state removed from `QualifierMatcher`
- [x] `vauban.annotations.reflection=allow|warn|forbid`, a test per fallback
- Fixes -05, -06 and -07 (-02 went with PR 3a, which fixed it)

PR 3 outcome: one matching rule left at run time, on `AnnotationKey`s, and one annotation instance
behind every CDI getter. Sequential `mvn clean install`: 788 tests, 0 failures; both surefire orders
green; CDI TCK 774/774 and AtInject green; one mutation proof per fix. BENCH-20260914-02 against the
PR 1 baseline: dependent creation 2.3× faster and 41 % less allocated, programmatic lookup 3.2× faster
and 32 % less, the qualified event and the intercepted call unchanged within the noise of a machine
that was not idle. Four tests stay disabled: -08 (PR 5), -11 (outside #70, twice), -13 (PR 4).
- Moved to PR 4: parameter qualifiers from the index instead of `Parameter.getAnnotations()`. It is
  performance only, touches nine call sites in six classes, and pairs with the generated metadata.

### PR 4 — Generated metadata, readers and literals (processor path)
Mapped on `main` @ 981889e. Two PRs again: the compile-time bug is self-contained and blocks users
today, the generated path is the large one.

#### PR 4a — Compile-time validation knows the module's own qualifiers
- [x] The processor indexes the annotation types its classes name, resolved through `Elements`, which
      sees both this compilation and the compile classpath
- [x] Compile-time matching on an `AnnotationTypes` built on that index: defaults and `@Nonbinding`
      decide as they do at run time (a second test, written for it)
- [x] `SameModuleQualifierValidationTest#sameModuleQualifier` runs (BUG-20260914-13)
- [x] `vauban-bench` compiles with validation on: its `-Avauban.validation=false` goes away, which is
      the end-to-end proof

#### PR 4b — Generated metadata, readers and literals
Where each piece plugs in, from the map of `main` @ 981889e:
- the SPI is `VaubanComponentProvider` (vauban-api), five methods, the optional ones `default`;
  a module's provider is `<pkg>._VaubanComponents`, one per package that has beans
- it is rendered as Java source by `ComponentProviderGenerator#generateFrom`, which emits each
  optional method only when its input list is non-empty — the seam a new one follows
- the run time consults providers through `ComponentProviders`, loaded once by the container builder
- the three fallbacks to put behind generated data: `AnnotationTypes#load` (index, class bytes,
  declaration), `AnnotationValues#infoOf` (container-built instance, else a reflective read),
  `AnnotationInstances#create` (the instance itself)
`vauban-api` requires nothing but `jakarta.cdi`, so the SPI speaks plain Java — member values as
`Object`, type names as `String` — and vauban-core converts them with `AnnotationValues.of`, the
lossless conversion of PR 2. Three generated artefacts per annotation type used as a qualifier or an
interceptor binding:
- [x] `VaubanComponentProvider` default methods, each returning `null` when the provider does not own
      the type: the metadata a type declares (its members, their declared types, their defaults, which
      are `@Nonbinding`), a reader that turns an instance into its member values with direct calls, and
      a literal factory
- [x] `AnnotationTypes` consults them before anything else — for the metadata, for the key of an
      instance and for the instance a bean or an observer exposes — and converts a nested annotation
      through the providers too; the builder passes the deployment's providers to it
- [x] Processor renders them (PR 4c)
#### PR 4c — The processor generates them — DONE
- [x] `AnnotationArtefacts` renders, for every annotation type **this compilation declares**, the
      metadata, a reader that calls its members directly and a literal class; they go into that
      type's own package provider, so a package-private qualifier is covered like any other.
      `Elements#getFileObjectOf` tells a type compiled here from one on the compile path.
      A package that declares annotation types but holds no bean now gets a provider of its own
- [x] Only runtime-retained types are rendered — the others the container never sees — and a type
      whose defaults cannot all be written as Java expressions is left out whole rather than by halves
- [x] The generated `toString` renders what `AnnotationInstances` renders (members sorted by name,
      strings quoted, `X.class`, arrays in braces), so a qualifier reads the same in a message
      whichever side built it. Arrays are copied in as well as out; `equals`/`hashCode` follow
      `Annotation` (floats by their bits, arrays element by element, each hash term parenthesised)
- [x] Verified: 423 unit tests + 6 new, both surefire run orders, CDI TCK 774/774, AtInject green.
      Four mutations, each red on the test that claims it: retention filter dropped, `@Nonbinding`
      not reported, declared defaults ignored, and the precedence bug put back in PR 4b's hand-written
      fixture — which that fixture's new `hashCode` assertion catches (it was wrong, and unnoticed)

#### PR 4d — The last reflective reads on the qualifier path — DONE (bench pending)
- [x] **The switch guards the fallback it was missing.** `forbid` covered the three vauban fallbacks
      but not the raw `param.getAnnotations()` / `field.getAnnotations()` scattered in the container,
      so it proved nothing about the injection path. `AnnotationReflection.checkParameter/checkField`
      now declare that read wherever it happens — four near-duplicate copies of it, in
      `QualifierHelper`, `ManagedBean`, `EventDispatcher` and `VaubanInjectionPoint`
- [x] It throws `AnnotationReflection.ForbiddenException`, a type of its own, and both catches that
      swallowed it let it through: a diagnostic mode a `catch (Exception)` can silence is worthless.
      The swallowing itself is BUG-20260914-17, deliberately left alone
- [x] Every injection shape now resolves from the descriptor: field, constructor parameter,
      initializer parameter, producer method parameter, observer non-event parameter, `Instance` and
      `Event` fields, `Bean#getInjectionPoints()`. A member no descriptor describes still falls back
- [x] **`InjectionPointInfo` records both sets.** What discovery stored was the *completed* set —
      `@Default` when nothing is written, and always `@Any` — where injection needs what the
      annotations actually say; using the first for the second made an unqualified event reach
      qualified observers. `declaredQualifiers()` is the declaration, `qualifiers()` what resolution
      compares. **The CDI TCK caught this and the CI does not run it**, so
      `whatAPointDeclaresIsNotWhatResolutionCompares` now catches it in the reactor
- [x] Descriptions are built by `InjectionPointInfo.parameterDescription`/`fieldDescription` on both
      sides instead of being spelled twice, matched whole (the old `endsWith("." + name)` would pick a
      superclass field of the same name), and an overloaded initializer — two parameters described
      identically — falls back rather than guessing
- [x] `vauban-module-it` runs under `-Dvauban.annotations.reflection=forbid` for the whole module,
      over a **package-private** qualifier with a binding member, a `@Nonbinding` one and one left at
      its default. Verified: 431 tests, both run orders, CDI TCK 774/774, AtInject; three mutations,
      each red on the test that claims it
- [x] BENCH-20260914-03: dependent creation 2.97× faster than the pre-#70 baseline (−47 %
      allocation), programmatic lookup 3.47× (−40 %). The prediction BENCH-20260914-02 made
      about `qualifiedEvent` did not hold — allocation fell 7 %, time did not move; what is
      left there is the dispatch, not the annotations

#### Left for later
- [ ] A qualifier from a dependency **not** built with the Vauban processor: its metadata comes from
      the class bytes (no reflection), but the instance a bean exposes still falls to
      `AnnotationInstances.create`, which `forbid` refuses. Either render a literal in the consuming
      package when the type is public and accessible, or say plainly that `forbid` requires every
      qualifier's module to be compiled with the processor
- [ ] Three sites keep reading the member, having nothing to read instead: a disposer's non-`@Disposes`
      parameters (`DisposerDescriptor` does not model them), `getInjectionTargetFactory`'s
      `AnnotatedType` (supplied by the caller, by construction), and the `Annotated` SPI facades
      (the specification requires those to hand out the annotations themselves)

### PR 5 — Interceptor bindings — DONE
- [x] `InterceptorManager` compares binding members as `AnnotationKey`s; **BUG-20260914-08 fixed**,
      and it had two causes, both about values that never reached the comparison: discovery dropped
      every binding an extension registered (it asked the type for a physical `@InterceptorBinding`),
      and `VaubanMetaAnnotations` reported extension-declared `@Nonbinding` members for qualifiers
      only. The last test this repository disabled for #70 is enabled
- [x] Which interceptors apply is worked out once per (bean class, method) instead of on every call
- [x] Docs: internals §AOT rewritten (Antoine's text said exposed instances are always
      `reflect.Proxy` — no longer true since 4b/4c; and it credited key-compared interceptor bindings
      before they were, which this stage makes true), reference gains the
      `vauban.annotations.reflection` row and a `[#system-properties]` anchor, migration's
      native-image note corrected, README's reflection claim corrected. Rendered with asciidoctor to
      check the tables and the list continuations, not grepped
- [x] **BENCH-20260914-04**: `interceptedCall` 1.92× faster for 77 % less allocation than after 4d —
      what BENCH-20260914-03 predicted this stage would move. Against the pre-#70 baseline:
      dependent creation 3.05×, programmatic lookup 3.53×, intercepted call 1.81×
- [x] Follow-up tickets filed: #85 (the CI does not gate the TCK, and `run-tck.sh` reports success
      over failures — it hid 7 red TCK tests during PR 4d), #88 (what `forbid` should do about a
      qualifier from a dependency built without the processor), #89 (a disposer's non-`@Disposes`
      parameters are not modelled). BUG-20260914-17 stays in `BUG.md`: an injection failure is
      swallowed and the field left null

## Review
_(to fill in when #70 closes)_

# Universal Vauban class loader — M1…M3 + trampoline — DONE 2026-08-11

## Trampoline (@VidocqMain / Vidocq.run) — study `vidocq-run-trampoline.md`, all boxes done

- [x] T0 spike: child layer shadows a parent-layer module name (test green)
- [x] T2 services promotion: `VaubanLayerFactory.promotingServices` rebuilds module
      descriptors with `META-INF/services/*` promoted to synthetic `provides`; the
      example's `module-info.java` lost EVERY generated-provider declaration
      (maintainer pain point resolved); cassini's APT now emits the services files
      (it did not — found during E2E)
- [x] T1: `@VidocqMain` + `VidocqApp` (spi), `Vidocq.run(args)` /
      `run(Class, args)` / `waitForExit()` (core), boot-layer detection
      (caller + beans.list modules + scanned-dependency packages,
      `-Dvidocq.app.modules` override), `ComponentProviders` twin dedup,
      agent-hint diagnostic
- [x] **Self-first layer loader** (the big finding): parent-first resolved layer-class
      references against un-woven boot-layer twins ("module app does not read module
      app"); parametrized — scanner/sjar keeps parent-first (a broad self-first broke
      the sjar clear-API case: ClassCastException on CryptoService)
- [x] T3: RestExampleApp = real trampoline implementing VidocqApp; E2E on the exact IDE
      shape `java -p all -m app/RestExampleApp` (unwoven, no provides): re-layered,
      woven, all endpoints 200, zero agent
- [x] T5: dists launch the trampoline (`-p lib:app --module mod/Class`), one launch
      shape everywhere; `mainModule` param + manual plugin.xml
- [x] Docs vidocq usage+internals EN/FR (entry-point contract, class-loading section
      with diagram); vauban docs already covered the engine
- [x] Verified: vauban 2650 + TCK 774/774, cassini 354, vidocq 280 + IT 5/5,
      dev hot reload green (same PID, zero agent)

# Previous: M1 (engine) + M2 (Vidocq app layer) — DONE 2026-08-11

Previous campaign (load-time weaving agent, verified green) is archived in
`vauban-24-client-proxy-constructor.md` §Phase 3 and the auto-memory. This plan executes
`vauban-classloader-universal.md` §9 with the §10 decisions of 2026-08-11.
Standing rule: NOTHING is committed or pushed.

## M1 — engine + sjar fold-in + cdi-proxifier (vauban only)

- [x] `vauban-weaver`: extract the byte-level analysis shared by LoadTimeWeaving and the
      proxifier into `BeanWeavingAnalysis` (normal-scope detection incl. meta-annotation
      lookup through a byte-resolver function, super-chain computation); core's
      `LoadTimeWeaving` switches to it (no behaviour change).
- [x] `vauban-classloader-spi`: `ClassTransformerPlugin` (name/interested/transform/
      priority, experimental Javadoc) + `ArchiveContext` (archive path, `ArchiveReader`,
      parsed `vauban-beans.list`).
- [x] New module `vauban-classloader` (engine, depends on SPI + weaver only):
  - [x] built-in sources: exploded-directory + plain-jar `ArchiveReader`s
  - [x] `VaubanClassLoader`: source resolution (ByteSourcePlugin by priority, built-ins
        last) → transformer chain (priority order, per-name disable property, dump
        option) → `defineClass`; parent-first delegation for everything outside its
        archives; engine-owned exclusions (`java.*`, `jdk.*`, `jakarta.*`,
        `io.vidocq.vauban.*`); resource lookup over the archives
  - [x] `CdiProxifierTransformer` (name `cdi-proxifier`): interested via beans.list
        (bean or `<bean>_ClientProxy`; archive without list → interested in all),
        transform via `BeanWeavingAnalysis` + `ProxyLinkWeaver` (idempotent)
  - [x] TDD: chain order/priorities, idempotence on woven input, exclusions, disable
        property, unwoven bean + proxy woven at load, resources
- [x] `vauban-core`: `ContainerScanner.scanSjar` switches from its private
      defineClass loop to the engine loader (core depends on `vauban-classloader`);
      existing sjar tests must stay green; new test: **unwoven bean inside an encrypted
      sjar is proxyable** (the decrypt → weave composition, impossible anywhere else)
- [x] Full vauban reactor green + CDI Lite TCK 774/774 unchanged

## M2 — the application layer (vauban factory + vidocq launcher)

- [x] `vauban-classloader`: `VaubanLayerFactory` — resolves app archives with
      `ModuleFinder`, child `Configuration` on top of the boot layer, single
      module-aware `VaubanClassLoader` passed to `ModuleLayer.defineModules`; classes,
      resources and `ServiceLoader` catalogs served through it (§6 hard points)
- [x] `vidocq-runtime-core`: layer launch mode — app archives come from
      `--app-path` / `-Dvidocq.app.path` (NOT the module path), `Vidocq.main` creates
      the layer, sets the TCCL and boots; `ContainerScanner` enumerates the layer
      loader's archives; `LoadTimeWeaving` stands down when the TCCL chain contains a
      `VaubanClassLoader` (the loader does the weaving — no agent, no JDK warning)
- [x] Module-path IT / E2E proof on cassini-rest-example in the simulated-IDE state
      (unwoven beans, business-chaining proxies): launched through the layer, boots with
      NO load-time-weaving warning and NO dynamic agent, `/api/stats` → 200
- [x] Docs: vauban `internals.adoc` class-loading section updated (engine + layer,
      tier table); FR mirror; studies updated
- [x] vidocq launch vehicles (package scripts, dev ChildJvm, CLI start) switch: follow-up
      package (M2b) — documented, not in this pass

## M2b — launch vehicles (2026-08-11, same session)

- [x] `Vidocq.main` / `VidocqAppLayer`: re-entrant `installIfConfigured()` (no-op when a
      `VaubanClassLoader` already sits in the TCCL chain), `-Dvidocq.app.main=<class>`
      runs the application main through the layer (package exported to the runtime via
      the layer `ModuleLayer.Controller`, same technique as the JDK launcher);
      `VidocqBootstrap.configure()` also installs the layer, covering the in-process
      CLI `start`/`dev` and every embedder
- [x] `VaubanLayerFactory.AppLayer` now carries the layer `Controller`
- [x] Dev mode: `ChildJvm` layer shape (`-Dvidocq.app.path=target/classes` +
      `-Dvidocq.app.main`, root module = runtime, module path = dependencies only),
      `VidocqDevMojo.layerMode` (default true, `-Dvidocq.dev.layer=false` opt-out),
      manual `plugin.xml` updated (JDK25 gotcha), command-shape unit tests
- [x] Packaging: `VidocqPackageMojo.layerMode` (default true,
      `-Dvidocq.package.layer=false` opt-out) — app jar ships in `app/`, scripts boot
      `-p lib --add-modules ALL-MODULE-PATH -Dvidocq.app.path="$BASEDIR/app"
      [-Dvidocq.app.main=…] -m io.vidocq.runtime.core/…Vidocq`; fixed a pre-existing
      NPE on `jvmArgs` in direct CLI invocations
- [x] vauban-core `buildCompositeClassLoader`: a `VaubanClassLoader` present among the
      bean loaders becomes the composite's PARENT — module-mode `ServiceLoader` only
      walks the parent chain's catalogs, and a composite parented on the app loader hid
      the layer's providers (found via the dist run: `_VaubanComponents` invisible →
      null field injections)
- [x] E2E dist: packaged zip's `run.sh` boots the layer, app main invoked through the
      Controller-exported package, `/api/*` + static all 200, zero agent
- [x] E2E dev: `mvn vidocq:dev` boots the layer (400 ms-ish), all endpoints 200, zero
      agent, hot reload respawns in ~1.7 s and stays green

Known limitation (documented): custom JUL handlers declared in the app's
`logging.properties` (e.g. the example's `StdoutHandler`) live inside the layer, but
`LogManager` instantiates handlers through the system class loader — they fall back to
the JDK default handler in layer mode. Keep log handlers in a module-path module, or
configure logging programmatically.

## M3 — in-JVM hot reload by layer re-creation (2026-08-11, same session)

- [x] `VidocqDevReloadLoop` (runtime-core): when `-Dvidocq.dev.reload.file=<path>` is
      set, `Vidocq.main` runs boot/stop cycles — polls the bootstrap shutdown latch
      (200 ms) and the signal file's mtime; on signal: `shutdown()` →
      `VidocqAppLayer.resetForReload()` (TCCL restored, old loader closed) → fresh layer
      → re-boot. Normal SIGTERM still wins. `vidocq.app.main` deliberately ignored in
      this mode (logged).
- [x] `VidocqBootstrap`: `awaitShutdown(timeoutMillis)`; `shutdown()` idempotent and
      deregisters its JVM hook (one hook per boot cycle otherwise).
- [x] `VidocqAppLayer.resetForReload()` + remembered pre-install TCCL.
- [x] Dev mojo: `hotReload` param (default true, `-Dvidocq.dev.hotReload=false` for the
      respawn cycle; effective only with `layerMode`) — touches
      `target/.vidocq-dev-reload` after a successful recompile instead of
      stop-and-respawn; falls back to respawn when the child died. Manual `plugin.xml`
      updated.
- [x] cassini-core: new `io.vidocq.cassini.runtime.CassiniMaintenance
      .resetDiscoveryCaches()` (the module's only unqualified export) —
      `RouteRegistry`/`AdapterRegistry` static caches are keyed by application `Class`
      objects and MUST be discarded between two in-JVM deployments; the Vidocq cassini
      extension calls it in `onStop()` (cassini-core promoted to compile scope there).
- [x] E2E: dev mode, REAL code edit → recompile → "Hot reload signalled after 1417 ms
      (in-JVM layer swap)" → new code served (`(hot)` marker), previous deployment
      stopped in 1 ms, **same child PID across two reload cycles**, all endpoints green
      after each swap (proving the cassini cache reset), zero agent.

## Review

Verified 2026-08-11 (all local, nothing committed):

- vauban reactor `clean install`: **1098 tests, 0 failure** (7 engine tests incl. the
  decrypt → weave headline and the layer module-mode regression guard).
- CDI Lite TCK: **774/774 unchanged**.
- vidocq `RestExtensionTest`: **5/5**.
- **M2 end-to-end** on cassini-rest-example in the simulated-IDE state (unwoven beans,
  business-chaining proxies), launched with the app OFF the module path
  (`-Dvidocq.app.path=target/classes:extlib.jar`, runtime with
  `--add-modules ALL-MODULE-PATH`): application layer created (2 modules), the
  cdi-proxifier weaves `StatsService` at definition, `/api/stats` → **200**, extlib and
  static resources served, **zero agent, zero JDK dynamic-agent warning**, boot 103 ms.

Bugs found and fixed along the way: the engine's excluded prefixes were too broad
(`io.vidocq.vauban.` blocked the example's own `…vauban.example.securized` namespace —
narrowed to the container's real module prefixes); custom layer loaders MUST override
`findClass(String moduleName, String name)` (inherited default returns `null` — every
layer class not already defined through the name-based path came back "Provider not
found" from ServiceLoader; misleadingly, classes pre-loaded by other paths still worked
via `findLoadedClass`); the example-test hand-crafted classpath needed the new jar.

Remaining, documented: M2b (switch the vidocq launch vehicles — package scripts, dev
ChildJvm, CLI start — to the layer mode), sjar-inside-a-layer (module-info readability),
M3 dev-mode layer re-creation.

# #42 Stage 4 — loader placement of in-package proxies + launcher (branch pr/ybl/42-stage4-loader-placement)

Design: a client proxy that must live in the produced type's own package (non-public overridable
member, or no accessible constructor) is generated at build time as a RESOURCE of the bean archive
(`META-INF/vauban/placed/<pkg>/<Type>_ClientProxy.class`, co-located shape). When the application
runs in a Vauban layer, the layer loader — which owns the external module — defines that class into
the module's package on `findClass` miss: full forwarding, no `opens`, no agent, no jar rewrite.
The existing `CdiProxifierTransformer` is extended so a type listed in the placement manifest
(`META-INF/vauban/required-opens.list`) gets the `(ProxyLink)` marker at definition and its placed
proxy is retargeted onto it — closing the #24 residual for third-party produced types.

- [x] 1. vauban-classloader: `ArchiveContext.placedProxyTypes()` (SPI default), manifest aggregation,
      `VaubanClassLoader.placesClass()`, placement in `findClass`, transformer extension — unit test
      proves: placed class defined by the loader, superclass woven, business ctor never runs (#24).
- [x] 2. vauban-processor: emit the placed proxy bytes as a resource for every produced type written
      to required-opens.list — compile-time test asserts the resource exists.
- [x] 3. vauban-core: `OpensApplier` placement-first (skip types the loader can place); the refusal
      warning names the launcher first.
- [x] 4. vauban-classloader: `Launch` main (re-layer the boot layer's application modules, invoke the
      application main inside). `Launch.run` returns a boolean and does nothing inside a layer, which
      gives the trampoline form; a separate `VaubanApp` callback interface was NOT added.
- [x] 5. vauban-producer-module-it: layer probe (Pooled forwards `internal:real` with zero opens,
      Handle resolves, proxy module is it.liba, counting external ctor runs once) + launched-main test.
- [x] 6. Docs: new `se.adoc` (Vauban in Java SE), nav/index/getting-started links, internals strategy
      table + weaving tiers, reference (Launch), whats-new (vidocq repo, PR #76).
- [x] 7. Gate: full reactor, CDI Lite TCK 774, mutations, adversarial review.

## Review — 2026-09-11

Adversarial review of PR #52 (6 lenses, 3 refuters per high/medium finding): 21 confirmed, 0 refuted.
All addressed on the branch, each fix written test-first and, where the behaviour is subtle, proven
by mutation:

- Placed proxy dropped inherited non-public members → `ClientProxyShapeFromElements.fromColocated`
  mirrors the bytecode shape (module IT: `hook:null` → `hook:real`).
- Self-first covered placed names only → a layer loader is self-first for every name of a package it
  owns (mutation: two `PlacedProxyTest` failures).
- A placed type whose superclass has only a business constructor ran it for the proxy → the
  transformer weaves the superclasses it defines, and warns when a foreign one blocks the chain
  (mutation: the IT counter reads 1).
- `superChain` candidates were wider than what `transform` weaves (NoSuchMethodError) → candidates are
  exactly the woven set.
- Launcher: the documented command could not work (`-m` makes the launcher the only root) →
  `--add-modules ALL-MODULE-PATH`; automatic modules, modules with `javax.`/`sun.`/`com.sun.`
  packages and what kept modules read stay in the boot layer; container modules by exact name; the
  main module must be re-layered; `run` no longer recurses.
- `OpensApplier` skips a placeable type only when its package is exported to the container; the skip
  is now covered by an IT.
- Two producers of one placed type → the emission is memoised per type (no FilerException).
- Docs: launcher command, kept rules, the `Vidocq.run` caveat, the TIP scoped, accurate forwarding
  limits.

Found before the review, by running the module IT in both surefire orders: a layer loader delegated a
placed name to its parent (d9a6361). An IT that shares a JVM with irreversible state (opened modules,
classes defined into a loader) can pass by test-class order alone.

Follow-up (branch pr/ybl/42-example-launcher): the CDI SE example (cdi#1015) runs through the
launcher. `Main` starts with the trampoline, and the library gains both in-package shapes:
`FraudScreen` (a package-private member called by `FraudPolicy` on the instance it is handed) and
`ReceiptPrinter` (no accessible constructor). `LauncherExampleTest` calls the real `Main.main` and
checks the printed output; removing the trampoline makes it fail (the container cannot build
`FraudScreen`'s proxy without opens). Verified in both surefire orders and with the plain `java -m`
command from the README.

Deferred: `Launch` and vidocq's `VidocqAppLayer` re-layer the boot layer with different policies —
one policy in vauban-classloader, used by both (follow-up ticket; the docs no longer claim otherwise).

# vauban#53 — one re-layer policy for Launch and Vidocq.run (branch pr/ybl/53-relayer-policy)

`Launch` and vidocq's `VidocqAppLayer` re-layered the boot layer with two policies: everything not
kept, versus only the modules carrying a beans list. Under `Vidocq.run`, a CDI-agnostic library
therefore stayed in the boot layer and its in-package proxies could not be placed.

- [x] vauban-classloader: the policy moves to `VaubanLayerFactory.applicationPaths(configuration,
      keptPrefixes, roots)`. A root is the application: the name rules (prefixes, container names)
      never keep it, so what it reads is not held back through it; the technical rules (automatic,
      excluded packages, read by a kept module) still apply. `Launch` delegates, with its named
      target module as the root.
- [x] vidocq-runtime-core (vidocq repo, same branch name): `VidocqAppLayer.applicationPaths` applies
      the policy with the Vidocq bricks as kept prefixes and the caller as the root; the beans-list
      detection is gone. `-Dvidocq.app.modules` still overrides.
- [x] Tests first: `LaunchSelectionTest` (roots escape the name rules only) and vidocq's
      `VidocqAppLayerSelectionTest` (a library without a beans list moves; a brick and what it reads
      stay; the caller moves under a runtime prefix). Mutations on both sides.
- [x] Docs: `se.adoc`, `internals.adoc`, `reference.adoc` (vauban); `usage.adoc`, `internals.adoc`,
      `whats-new.adoc` (vidocq).
