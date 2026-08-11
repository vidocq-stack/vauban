# Study — `@VidocqMain` / `Vidocq.run()`: the Quarkus-style trampoline entry point

Status: **IMPLEMENTED locally** (2026-08-11, nothing committed). Maintainer decisions:
detection = caller + beans.list modules (§2 step 2, plus the scanned-dependency
refinement: modules owning packages listed in the caller's beans.list); `VidocqApp`
callback in V1; T5 done (dists launch the trampoline); no strip-final mode — `ProxyLink`
stays the only deviation, `final` members remain spec errors.

Two implementation findings beyond the plan: (1) the layer loader must be
**self-first for its archives** (parent-first resolved layer-class references against
their un-woven boot-layer twins — "module app does not read module app"), parametrized
so the scanner/sjar path keeps parent-first (clear sjar API types stay unique);
(2) cassini's APT did not emit `META-INF/services` files — added
(`CassiniResourceProcessor` now writes them at `processingOver`, like Vauban's).
Verified: vauban 2650 tests + TCK 774/774, cassini 354, vidocq 280 + IT 5/5; E2E green
on the IDE shape, the trampoline dist and dev hot reload — all zero-agent.
Origin: maintainer feedback on the M1–M3 user experience — the IDE story must not
require special launch configuration, and hand-written `provides …$$CassiniAdapter`
declarations in application `module-info.java` are unacceptable.

Upstream context: `vauban-classloader-universal.md` (M1–M3 implemented),
`vauban-24-client-proxy-constructor.md` (weaving tiers). Quarkus references:
lifecycle guide, class-loading reference, Arc CDI reference.

---

## 1. The remaining UX gap

After M1–M3, the universal loader covers every *Vidocq-owned* launch vehicle
(`vidocq:package` dists, `vidocq:dev`, CLI). What it does NOT cover is the plain IDE
gesture — right-click → Run on the application's `main` — which launches
`java -p everything -m app/Main`: the application is resolved into the **boot layer**
before any Vidocq code runs, the loader never owns it, and only the instrumentation
agent (with its JDK warning) saves the day.

Quarkus solves the same problem *contractually*, not technically:

1. the recommended `main` is a **trampoline** (`@QuarkusMain` + `Quarkus.run(args)`),
   documented as containing no business logic because the application will run in
   another class loader;
2. `Quarkus.run()` performs the real bootstrap (`QuarkusBootstrap` → curated app →
   isolated class loaders; the runtime CL is re-created on dev reload);
3. Arc transforms unproxyable classes during augmentation
   (`quarkus.arc.transform-unproxyable-classes=true` — strips `final`, adds no-arg
   constructors), assumed as a documented deviation from strict CDI.

Point 3 we already do **better**: the `cdi-proxifier` transformer adds the synthetic
`(ProxyLink)` entry constructor — no semantic change to `final` members, spec-mandated
errors preserved for non-APT archives. Point 2 is our `VaubanLayerFactory`. Point 1 is
what this study adds.

## 2. Proposed contract

```java
@VidocqMain
public final class App {
    public static void main(String[] args) {
        Vidocq.run(args);          // no business logic before this line — documented
    }
}
```

- `@VidocqMain` — marker annotation (new, `vidocq-runtime-spi`), documentation +
  future APT lint ("a @VidocqMain main should contain nothing but Vidocq.run").
- `Vidocq.run(String[] args)` — the official IDE-compatible entry point:

```
IDE launches  java -p <everything> -m app/App          (no special config)
  boot layer loads ONLY App (the trampoline) lazily
  → Vidocq.run():
      1. already inside a Vauban layer (TCCL check)?  → plain boot (re-entrant)
      2. locate the application modules IN the boot layer
         (caller module via StackWalker + every boot-layer module carrying
          META-INF/vauban-beans.list, excluding runtime/platform prefixes)
      3. collect their archive locations (ModuleReference.location())
      4. create the child ModuleLayer over those SAME archives
         (VaubanLayerFactory — module names MAY shadow boot-layer names)
      5. TCCL = VaubanClassLoader → boot (or dev reload loop)
  → application classes are (re)loaded through the layer: woven, no agent
```

- The trampoline `main` is **not re-executed** inside the layer (V1): it has no logic
  by contract. A later `Vidocq.run(Class<? extends VidocqApp>, args)` can bring the
  Quarkus-style "application logic after boot" callback, instantiated layer-side.
- No promise is made about business code executed before `Vidocq.run()` — same wording
  as Quarkus.
- Explicit `-Dvidocq.app.path` keeps priority (M2 vehicles unchanged); the boot-layer
  self-detection is the *fallback* that makes the bare IDE run work.
- The **agent tier is demoted to a compatibility net** for legacy mains that boot the
  runtime directly; `LoadTimeWeaving` gains a diagnostic suggesting the trampoline when
  it has to attach.

## 3. Feasibility notes (checked against the module system)

- **Same-name module in a child layer**: allowed — a child configuration resolves its
  own `io.vidocq.runtime.examples.rest` even when the boot layer has one; child
  resolution prefers the local finder. (This is the standard plugin-layer pattern.)
  Must be validated by an early spike test — risk #1 of the package.
- **Archive locations**: `bootLayer.configuration().findModule(name).reference()
  .location()` yields the `file:` URI of the jar/exploded dir the IDE put on `-p`.
- **Only the trampoline is loaded twice** (boot + child): harmless as long as it holds
  no state — the documented contract. Any application class the trampoline references
  *before* `run()` would be loaded un-woven in the boot layer: this is exactly what the
  contract forbids, and the diagnostic (§5) makes it visible.

## 4. The duplicate-provider hazard (and its fix)

Because the IDE puts the app module in the boot layer as a **root**, its `provides`
land in the boot-layer service catalogs. After re-layering, `ServiceLoader.load(svc)`
(TCCL = layer loader) iterates **child catalogs first, then parent catalogs** — so
every generated provider appears twice: the layer one (woven world) first, the
boot-layer twin (un-woven world) second.

- Cassini registries key by `resourceClass()` and look up with layer classes → the
  boot twins are inert pollution.
- `ComponentProviders` (vauban-core) tries providers in order until one succeeds → the
  layer provider wins normally; but if it ever failed, the boot twin could hand out an
  object of the WRONG layer (ClassCastException downstream at best).

**Fix (localized, vauban-core):** when the effective loader is a `VaubanClassLoader`,
`ComponentProviders.load` keeps only providers actually defined by that loader:

```java
if (effectiveLoader instanceof VaubanClassLoader vcl) {
    providers.removeIf(p -> p.getClass().getClassLoader() != vcl);
}
```

## 5. Diagnostics

- `VidocqBootstrap`: booting without a Vauban layer while unwoven `vauban-beans.list`
  beans exist → the current agent path, plus an actionable message:
  *"Tip: annotate your main with @VidocqMain and call Vidocq.run(args) — the
  application then runs inside the Vauban class loader (no agent, no JVM warning)."*
- `Vidocq.run()` on the class path (unnamed module) or with non-`file:` locations →
  INFO + fallback to the current chain (agent).

## 6. Killing the hand-written `provides …$$CassiniAdapter` (maintainer pain point)

Why they exist today: the generated `$$CassiniAdapter`/`$$CassiniRoutes`/
`_VaubanComponents` classes are ServiceLoader providers. On the module path their
`provides` are sealed into `module-info.class` by the cassini/vauban Maven plugins at
`process-classes` — but an IDE rebuild recompiles `module-info.java` from source and
loses the sealing, which forced the manual declarations into the example's source.

**Proposal: promote `META-INF/services/*` to synthetic `provides` at layer-resolution
time.** The layer is OURS — `VaubanLayerFactory` wraps its `ModuleFinder` and rebuilds
each application `ModuleDescriptor`:

```java
// inside the wrapping ModuleFinder
ModuleDescriptor original = ref.descriptor();
Builder b = copyOf(original);                       // name, requires, exports, opens…
for (String service : servicesDeclaredIn(archive)) {           // META-INF/services/*
    List<String> impls = readServiceFile(archive, service);
    b.provides(service, mergedWithExisting(original, service, impls));
}
return wrappedReference(b.build(), ref);
```

Effects:

- application `module-info.java` never mentions a generated class again — the example's
  manual `provides …$$CassiniAdapter/Routes` (both Todo and Stats) are DELETED;
- the standard `META-INF/services` files (already emitted by the APTs for class-path
  mode) become the single source of truth;
- generic by construction: works for `_VaubanComponents`, Cassini adapters/routes, and
  any future generated SPI — no `$$` naming convention in the mechanism;
- the Maven-plugin sealing remains for the *legacy* module-path launch (packaged jars
  running without the layer), untouched.

JPMS constraint respected: a synthetic `provides` only names classes living in the same
archive/module — which is always the case for APT output.

## 7. Work plan

- **T0 — spike (risk #1)**: child layer shadowing a boot-layer module name, resolved
  from the same archive; ServiceLoader child-first ordering confirmed by test.
- **T1 — trampoline**: `@VidocqMain` (spi), `Vidocq.run(args)` (core: TCCL re-entry
  check, caller module via StackWalker, boot-layer app-module detection by
  `vauban-beans.list` + prefix exclusions, `-Dvidocq.app.path` priority, fallback +
  diagnostics), `ComponentProviders` layer filter (§4).
- **T2 — services promotion**: descriptor-rewriting `ModuleFinder` in
  `VaubanLayerFactory` (§6); delete the manual `provides` from the example's
  `module-info.java`; E2E must stay green *without* them (layer mode) and the sealed
  jars keep the legacy mode green.
- **T3 — example + E2E**: `RestExampleApp` becomes a real trampoline (`@VidocqMain`,
  body = `Vidocq.run(args)`; the JUL `readConfiguration` call moves out — it is the
  documented layer limitation anyway). E2E: bare `java -p all -m app/RestExampleApp`
  (the exact IDE shape) → re-layered, woven by the loader, zero agent, all endpoints
  green; dev/hot-reload unchanged.
- **T4 — docs**: usage (the official main contract, copy Quarkus' wording about no
  business logic), internals (boot-layer re-layering diagram), tier table update
  (agent = compatibility net).
- **Optional T5**: packaged dists switch back to `--module app/App` (trampoline) —
  simpler scripts, one launch shape everywhere. To discuss.

## 8. Open questions for the maintainer

1. App-module detection default: caller module + every boot-layer module with
   `vauban-beans.list` (proposed), or caller module's `requires` closure only?
2. Should `Vidocq.run()` eventually re-execute an application callback layer-side
   (`VidocqApp` interface, Quarkus-style) in V1, or later?
3. T5 (dists launch the trampoline instead of `-m runtime`) — now or later?
4. A Quarkus-like *non-strict* option (`transform-unproxyable: strip final`) as an
   extra transformer — wanted at all, or does `ProxyLink` stay the only deviation?
