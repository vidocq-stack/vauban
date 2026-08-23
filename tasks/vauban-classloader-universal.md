# Study — Universal Vauban ClassLoader (sources + transformers)

Status: **M1 + M2 + M2b + M3 implemented locally (2026-08-11, uncommitted)** — engine
`vauban-classloader`, cdi-proxifier transformer, sjar fold-in, `VaubanLayerFactory`
(with layer `Controller`), Vidocq `-Dvidocq.app.path`/`-Dvidocq.app.main` launch mode,
launch vehicles switched (package `app/` layout, dev mode, CLI/embedders), and **M3
in-JVM hot reload by layer re-creation** (`vidocq.dev.reload.file` loop in the runtime,
mojo signals instead of respawning, cassini discovery caches reset through the new
`CassiniMaintenance` API): proven with a real code edit served after the swap, same
child PID across cycles, previous deployment stopped in 1 ms, zero agent. See
`tasks/todo.md` for the M2b/M3 reviews and found-bugs list. Remaining: sjar-in-layer.
Do not commit without explicit approval.
Date: 2026-08-10. Origin: the vauban#24 discussion (client-proxy constructor) — maintainer
decision: the loader code lives in **Vauban** (it is the container), with a plugin
mechanism where SJAR is one plugin among others and the "CDI proxifier" another.

Upstream context: `tasks/vauban-24-client-proxy-constructor.md` (the three walls, the
`ProxyLink` marker, build-time weaving), branch `pr/ybl/vauban-24-proxy-link`, and
`tasks/plan-sjar-classloader.md` (the original plugin-based classloader plan that produced
`vauban-classloader-spi` + `vauban-sjar` — this study is its generalization).

---

## 1. Problem and intent

Three needs converge on the same mechanism:

1. **vauban#24** — make normal-scoped beans proxyable **without touching the application**
   (neither source nor pom) and **regardless of the compiler**: build-time weaving (javac
   plugin / Maven plugin) covers javac but not ECJ; nothing covers an unwoven third-party
   jar, let alone a third-party sjar.
2. **SJAR** — decrypt-at-load already exists (`SjarClassLoader`, `ByteSourcePlugin`) but as
   a hardwired special case, not as one plugin of a general mechanism.
3. **Future** — dev mode, hot reload, instrumentation: all require owning the loading of
   application classes.

Intent: a **single control point for application class loading**, owned by Vauban,
extensible through plugins, consumed by the Vidocq launcher.

## 2. Principle

Two plugin natures, chained by a single engine:

```
             ┌─ sources ─────────────┐   ┌─ transformers ────────────┐
archive ───▶ │ dir | jar | sjar | …  │ ─▶│ cdi-proxifier | (future) │ ─▶ defineClass
             └───────────────────────┘   └───────────────────────────┘
```

- **Source** (`ByteSourcePlugin`, exists): *where the bytes of an archive come from*.
- **Transformer** (`ClassTransformerPlugin`, to create): *what happens to the bytes*
  before definition.

An unwoven third-party sjar can **only** be fixed here: nobody else can write into an
encrypted jar. The decrypt → weave composition falls out of the chaining for free.

Fundamental invariant: **a transformer applies the same transformation as the build**
(the proxifier reuses `ProxyLinkWeaver` as-is, idempotent). Two application points, one
semantics — AOT and JVM stay equivalent.

## 3. Proposed SPI contracts

### 3.1 `ClassTransformerPlugin` (new, in `vauban-classloader-spi`)

```java
/**
 * Transforms class bytes before they are defined by the Vauban class loader.
 * Discovered via ServiceLoader; ordered by priority() (lower first).
 */
public interface ClassTransformerPlugin {

    /** Unique name, for logs and diagnostics (e.g. "cdi-proxifier"). */
    String name();

    /**
     * Cheap pre-filter: whether this transformer wants to see {@code className} from
     * {@code archive}. Called once per class; must not parse the bytes.
     */
    boolean interested(String className, ArchiveContext archive);

    /**
     * Returns the transformed bytes, or {@code null} when the class is left unchanged.
     * MUST be idempotent (a class already transformed at build time passes through
     * untouched) and MUST NOT change the observable semantics of the class.
     */
    byte[] transform(String className, byte[] bytes, ArchiveContext archive);

    /** Lower runs first. Default 1000. */
    default int priority() { return 1000; }
}
```

`ArchiveContext` (new, same module) gives the transformer what it can know **without the
container**: the archive path, access to its entries (`ArchiveReader`), and lightweight
metadata — in particular a parsed `META-INF/vauban-beans.list`. No full index, no
`BeanDiscovery`: the loader precedes the container.

The proxifier decides with that alone:

- `interested()`: the class is listed in its archive's `vauban-beans.list` (O(1), no
  analysis); when the list is absent (archive not pre-processed), fallback: always
  interested, the fine-grained decision moves into `transform`.
- `transform()`: Class-File parse (built-in normal-scope annotations
  `@ApplicationScoped`/`@RequestScoped`, plus `@NormalScope` meta-annotated *if the
  annotation lives in the same archive* — documented limit §8), checks the entry
  constructor is absent, applies `ProxyLinkWeaver.addMarkerConstructor`, and retargets any
  `*_ClientProxy` it encounters (`retargetProxyConstructor`).

### 3.2 `ByteSourcePlugin` (existing — minor evolutions)

Unchanged in substance. `SjarPlugin` becomes an *ordinary* plugin of this mechanism (it
almost is already); the sjar-specific wiring in `ContainerScanner` disappears in favour of
the engine (§4).

## 4. Module layout

Layering trap to avoid: the loader must exist **before** the container (it loads the app
that contains the beans). The engine therefore cannot live in `vauban-core`.

```
vauban-classloader-spi   contracts only: ByteSourcePlugin, ClassTransformerPlugin,
                         ArchiveReader, ArchiveContext, PluginContext — zero dependencies
vauban-classloader       NEW — the engine: VaubanClassLoader (defineClass, source →
                         transformer chaining, plugin ServiceLoader discovery, caches),
                         VaubanLayerFactory (§6). Depends on the SPI only.
vauban-sjar              pure source plugin (decryption) — depends on the SPI only
vauban-core              provides the "cdi-proxifier" transformer (depends on the SPI and
                         on core/proxy for ProxyLinkWeaver); the container CONSUMES the
                         loader but no longer owns it
```

Note: `ProxyLinkWeaver` currently lives in `vauban-core/proxy`. Two options — (a) the
transformer lives in core (core depends on the SPI; the launcher does not need core to
create the loader, transformers arrive via ServiceLoader whenever core is present);
(b) move the weaver to a lower module. **Recommendation: (a)** — nothing moves, the
ServiceLoader decouples.

## 5. Engine semantics

- Order: resolved source (first that `handles()`, by priority) → transformers in priority
  order → `defineClass`.
- **Mandatory idempotence** of every transformer (documented contract + tested): a class
  already woven at build time passes through unchanged. This is what makes the loader tier
  CDS/AOT-friendly: on the nominal path (woven build) it touches nothing.
- Scope: **application archives only**. Never the Vauban/Vidocq modules, never the
  platform. The exclusion list lives in the engine, not in plugins.
- Diagnostics: every transformation logged (class, transformer, archive); a
  `-Dvauban.classloader.dump=<dir>` option writes the transformed bytes (debugging).
- A transformer failure fails the class load (nothing silent) — unless an explicitly
  configured degraded mode says otherwise.

## 6. Module path: the Vidocq layer (work package M2)

The universal loader on the module path = the Vidocq launcher creates a **child
`ModuleLayer`** for the application archives, whose defining loader is
`VaubanClassLoader` (`defineModulesWithOneLoader` in V1; one loader per module only if a
real need shows up).

- Parent layer: platform + Jakarta specs + vauban/vidocq modules (never transformed).
- Child layer: application archives + extension wrappers that carry beans.
- Exports/opens **remain enforced** inside the child layer: the strict Java Modules
  philosophy survives as-is.
- Hard points to work through during M2 (the real cost of the package):
  - `ServiceLoader` across layers (child-layer providers visible from the parent — pattern
    already met on the TCK runners, cf. the "ServiceLoader layer" lesson);
  - resources (`getResource*` must traverse correctly);
  - stack traces / debugging (classes come from a named loader — take care of
    `getName()`);
  - interaction with `vidocq dev` (restart = new layer → the hot-reload foundation, M3).
- **Who loads first**: nothing may touch an application class before the layer exists.
  Guaranteed through `vidocq start`/`dev` (the main lives in the runtime). A bare `main()`
  run bypasses the mechanism → covered by the build tier, documented.

## 7. Place in the stack (reminder of the overall vauban#24 target)

| Tier | Covers | Status |
|---|---|---|
| `autoStart` javac plugin (in the APT jar) | Maven, Gradle, bare javac — static, AOT/CDS | implemented (local, 2026-08-10) |
| Maven plugin | ecosystem jar builds + sjar encryption | exists (PR #26) |
| **Load-time weaving agent (`vauban-weaver`)** | IDE builds (IntelliJ JPS flushes classes after javac — field-tested 2026-08-10), any unwoven-but-APT-built output | implemented (local, 2026-08-10) — see `vauban-24-client-proxy-constructor.md` phase 3 |
| **Universal loader (this study)** | sjar as of M1; ECJ / third-party jars as of M2 | M1/M2/M3 |
| `ReflectionFactory` | — | **abandoned** (re-examined 2026-08-10: also blocked by zero-`opens` app modules) |

The boot validator only raises an error when no tier can act. The native-image path stays
guaranteed by the build tier (no custom loader in native — a native build necessarily goes
through Maven, hence arrives woven).

**Interaction with this study**: the agent tier is a stopgap the universal loader can
absorb — once application classes are defined by the Vauban root loader, the same
`ProxyLinkWeaver` transforms run as an in-loader `ClassTransformerPlugin` and the
`java.lang.instrument` attach (and its JDK warning) disappears. The plan-driven
`WeavingPlan`/detection code in `vauban-core.weaving` is loader-agnostic on purpose.

## 8. Accepted limits and risks

- **Cross-archive custom `@NormalScope`**: without a full index, the transformer only
  resolves the meta-annotation when the annotation is visible (same archive, or parsable
  through the `ArchiveContext`). V1: built-in scopes + beans.list; the cross-archive custom
  case stays covered by the build tier and the validator. Revisit in M2 (the launcher can
  provide a lightweight multi-archive index).
- **Performance**: the `interested()` pre-filter via beans.list keeps the cost O(beans),
  not O(classes). Class-File parsing only for candidates. To be measured (BENCH.md) on a
  real application in M1.
- **CDS/Leyden**: a class transformed at load falls out of the CDS archive. Acceptable
  because the loader tier is a safety net: on a woven build it is a no-op. To document.
- **Security**: transformers run with runtime privileges; the SPI is ServiceLoader-based →
  only module-path jars can contribute one. Same trust model as Vidocq extensions. The
  exclusions (§5) prevent transforming Vauban itself.
- **Interaction with the BCE static-metadata effort**: the load-time proxifier reintroduces
  no `opens` (it acts before definition, not through reflection). Consistent.

## 9. Work plan

- **M1 — the engine and the sjar fold-in** (vauban only, no vidocq dependency)
  1. `ClassTransformerPlugin` + `ArchiveContext` SPI in `vauban-classloader-spi`.
  2. New `vauban-classloader` module: `VaubanClassLoader` generalized from
     `SjarClassLoader`; `ContainerScanner` switches over; sjar becomes a pure source
     plugin.
  3. "cdi-proxifier" transformer in `vauban-core` (reuses `ProxyLinkWeaver`).
  4. TDD: unwoven sjar → woven at load; idempotence; exclusions; priorities.
  Deliverable: third-party sjars are covered; the architecture is in place.
- **M2 — the app layer on the Vidocq side** (vidocq package, depends on M1)
  `VaubanLayerFactory` + `vidocq start`/`dev` integration; work through the §6 hard
  points; dedicated module-path IT (VID-001 lesson: only a real module-path run catches
  these bugs).
  Deliverable: the mechanism becomes universal — Rossignol covered whatever its build.
- **M3 — dividends**: dev mode / hot reload as layer re-creation; dev instrumentation
  (measurement points) as plain transformers.

Independent sibling package: the **`autoStart` javac plugin** (separate study if needed) —
it remains the nominal static path and the only one valid in native.

## 10. Open questions — resolved 2026-08-11 (maintainer said "go", defaults chosen)

1. Engine module name: **`vauban-classloader`** (module `io.vidocq.vauban.classloader`).
2. **One loader for the whole child layer** in V1 (a custom module-aware loader passed to
   `ModuleLayer.defineModules`); per-module loaders only if a real isolation need shows up.
3. Transformers **always on**, with per-name opt-out:
   `-Dvauban.classloader.transformers.disabled=<name,name>`.
4. Layer re-creation on reload: **M3** (dev mode), not M2.
5. The transformer SPI ships in `vauban-classloader-spi` (public by construction) but its
   Javadoc marks it **experimental** until a second client exists.

One design delta versus §4: `ProxyLinkWeaver` no longer lives in `vauban-core` — the
vauban#24 phase 3 moved it to the zero-dependency `vauban-weaver` module. The
"cdi-proxifier" transformer therefore lives in the **engine module** (depends on the SPI
and vauban-weaver only), not in core: the loader stack stays fully container-free, and
core's `LoadTimeWeaving` shares the same byte-level analysis helpers from vauban-weaver.
