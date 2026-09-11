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
      application main inside) + `VaubanApp` callback form.
- [x] 5. vauban-producer-module-it: layer probe (Pooled forwards `internal:real` with zero opens,
      Handle resolves, proxy module is it.liba, counting external ctor runs once) + launched-main test.
- [x] 6. Docs: new `se.adoc` (Vauban in Java SE), nav/index/getting-started links, internals strategy
      table + weaving tiers, reference (Launch, VaubanApp), whats-new (vidocq repo).
- [ ] 7. Gate: full reactor, CDI Lite TCK 774, mutation (no placement → red), adversarial review.
