# ELIMINATE_RUNTIME — close the runtime-generation fallbacks in Vauban

> Prompt for a Claude Code session working in this repository (`vauban/`).
> Read `CLAUDE.md` (repo root) and the workspace `../CLAUDE.md` first, and follow them.
> Work plan-first: explore, write a plan (`docs/superpowers/plans/`), get it validated, then TDD.
> All file:line references below were verified on 2026-09-08 against `main` — re-verify before relying on them, they may have drifted.

## Context

Vauban is a build-time-first CDI 4.1 Lite implementation with native Java Modules support.
The default path is 100 % static: the annotation processor (`vauban-processor`) emits
`<Bean>_ClientProxy` and `<Bean>$$Intercepted` **as Java sources in the bean's own package**,
plus one `_VaubanComponents` per package (implements `io.vidocq.vauban.api.VaubanComponentProvider`,
registered via `provides` in the app's `module-info.java`). ServiceLoader instantiates the
provider, so the container needs **no `opens`, no `exports`** on application packages.

However, a **runtime fallback** still exists, and it is exactly the pain point discussed in
the CDI spec issue jakartaee/cdi#1015. The goal of this task is to eliminate (or fence off
with build-time diagnostics) every runtime-generation path.

## Current state — the fallback paths (verified map)

1. **Normal-scoped producer returning a class type** (the big one).
   - APT only generates proxies for managed beans: `bean.kind() == MANAGED` gate at
     `vauban-processor/.../VaubanProcessor.java:409`. Producer beans fall through to runtime.
   - Runtime path: `vauban-core/.../InterceptorBeanWrapper.java:313-338`
     (`resolveProxyTargetClass` at :359-380 picks the concrete produced type) →
     proxy targeted at the produced type's **own package** → `loadOrDefineClassRobustly`
     (:454-463) → `VaubanLookup.lookupFor()` → `MethodHandles.privateLookupIn` +
     `Lookup.defineClass`. On the module path this **requires the produced type's module to
     `opens` its package to `io.vidocq.vauban.core`** (error surfaced at
     `VaubanLookup.java:319-327`), which contradicts the zero-`opens` story.
   - Interface-typed producers are fine: `java.lang.reflect.Proxy`
     (`InterceptorBeanWrapper.java:224`), needs only `exports`.
   - The APT split-package guard (`VaubanProcessor.java:130-136, 414-425`) rightly forbids
     generating into another module's package — keep it.

2. **The `super()` problem resurfaces in that fallback.**
   `RuntimeClientProxyGenerator.findSimplestConstructor`
   (`vauban-core/.../proxy/RuntimeClientProxyGenerator.java:107-120`) prefers the opt-in
   side-effect-free `(ProxyLink)` entry constructor (vauban#24 contract, see
   `vauban-api/.../ProxyLink.java` javadoc), but an **external produced class was never
   woven**, so the fallback calls the simplest non-private constructor **with default
   values (null/0/false)** — `ClientProxyEmitter.java:86-102`. Real constructor side
   effects + null arguments = latent NPEs.

3. **Minor residual reflection** (list them, decide per-case: eliminate, or keep as
   documented last-resort):
   - `ClientProxyEmitter.java:226-259` — emits `privateLookupIn().findVirtual` into the
     proxy `<clinit>` for protected/package-private super methods across packages
     (`RuntimeClientProxyGenerator.needsMethodHandleDispatch` :139-145). Note the APT
     source path never needs this (`ClientProxyShapeFromElements.java:51`).
   - `VaubanLookup.java:260-269` — `trySetAccessible` for non-public members.
   - `SyntheticBeanConverter.java:161` — `setAccessible(true)` for BCE synthetic beans.
   - `InterceptorBeanWrapper.java:447-457` — `instantiatePreferProvider` reflective
     fallbacks (provider → `publicLookup` → `privateLookupIn`).

Existing build-time machinery you will reuse (do NOT reinvent):
- `vauban-weaver/.../ProxyLinkWeaver.java` — Class-File API transforms:
  `addMarkerConstructor` (weaves synthetic `protected <init>(ProxyLink)` into a bean class)
  and `retargetProxyConstructor` (rewrites a proxy `<init>` to `super((ProxyLink) null)`,
  idempotent). Delivered via three channels: javac plugin `VaubanWeavingPlugin`
  (autoStart, runs at `COMPILATION finished`), `vauban-maven-plugin` at `process-classes`,
  and the load-time agent (`WeavingAgent` + `LoadTimeWeaving`, self-attach via `AttachBack`)
  for IDE builds where IntelliJ JPS rewrites classes after javac.
- `vauban-maven-plugin/.../VaubanGenerator.java:257-266` — already post-processes
  **external jars** with the bytecode generators.
- `ComponentProviderGenerator.java:264-283` — `_VaubanComponents.createClientProxy(...)`.
- `vidocq:check-module-info` (workspace `vidocq` repo) — consumer `module-info` diagnostics.

## The task — three stages

Implement stages 1 and 3 first (one PR, or two stacked). Stage 2 is a separate PR.
Each stage must land with its tests, in TDD order (red → green → refactor).

### Stage 1 — build-time producer proxies for "fully public" types

When compiling a bean archive B that declares a normal-scoped producer (method or field)
whose type `Foo` is a **class from another module/package**, the APT must generate the
client proxy at build time instead of deferring to runtime, **placed in the producer's own
package in B** (never in `Foo`'s package — split-package guard stays).

This is only correct when `Foo` is *fully public*, meaning ALL of:
- public, non-final class, not sealed (or proxy fits the permits), top-level or public
  static nested, with a type usable as superclass;
- every virtual method a client can see is public or protected and non-final
  (package-private methods of `Foo` are invisible to other packages, so a proxy in B's
  package forwards everything a client can legally call — document the known edge case:
  code *inside* `Foo`'s package calling a package-private method on the proxy instance
  would bypass delegation; detect and refuse if `Foo` has non-private package-private
  virtual methods, same restriction the CDI issue's question 3 contemplates);
- an accessible (public/protected) constructor exists to chain `super()` to. Prefer, in
  order: an existing `(ProxyLink)` constructor (external lib may adopt the contract), a
  no-arg constructor, else the simplest accessible constructor with default values —
  reusing the exact selection semantics of `findSimplestConstructor`, but computed and
  **reported at build time** (warning naming the constructor that will run).

Implementation sketch:
- Lift the `MANAGED`-only gate in `VaubanProcessor` for normal-scoped producer beans.
- New shape derivation from `javax.lang.model` elements for an *external* supertype
  (extend `ClientProxyShapeFromElements` or add a sibling): target package = producer's
  package, proxy name collision-safe (e.g. `<ProducerBean>_<Foo>_ClientProxy` or
  hash-suffixed — pick one and keep it deterministic for incremental builds).
- `ClientProxySourceRenderer` must handle a superclass in another package: no
  package-private access, explicit `super(...)` argument list with default values, no
  MethodHandle dispatch (only public/protected methods, invocable from a subclass).
- Register the proxy in the package's `_VaubanComponents` (`createClientProxy`) keyed by
  the produced type, and make `InterceptorBeanWrapper` look up the provider-generated
  proxy **before** attempting runtime generation (extend the existing
  provider-first logic at `InterceptorBeanWrapper.java:313-322`).
- Bytecode parity: mirror the same capability in the Maven-plugin generator path
  (`VaubanGenerator`) — Vauban rule: APT-source first, Class-File API as fallback, keep
  both in parity (see workspace CODEGEN-AUDIT rule).

### Stage 2 — opt-in dependency enhancement (`vauban:enhance-dependencies`)

New goal in `vauban-maven-plugin` (opt-in, off by default), for the cases stage 1 refuses
(package-private members, no accessible constructor, final methods that matter):
- For each configured dependency jar containing produced/injected types that need it:
  produce a **woven copy** under `target/vauban-enhanced-deps/`:
  - `ProxyLinkWeaver.addMarkerConstructor` on the target classes;
  - generate `Foo_ClientProxy` and a `_VaubanComponents` **inside `Foo`'s own package**
    (legal here: we are rewriting that jar, no split package);
  - rewrite `module-info.class` (Class-File API) to add the
    `provides io.vidocq.vauban.api.VaubanComponentProvider with ...` entry
    (and `requires io.vidocq.vauban.api` if absent);
  - automatic-module / classpath jars: document behavior, don't crash.
- Substitution on the module path follows the copy-dependencies pattern used by the
  Vidocq CLI (transitive closure copied to a directory; the enhanced copy shadows the
  original). Never republish enhanced jars; note that jar signatures are invalidated —
  that is why the goal is opt-in.
- Keep the plugin zero-dep (Class-File API only, no ASM).

### Stage 3 — build-time diagnostics instead of runtime `IllegalAccessException`

- APT: when a normal-scoped producer's class type is neither stage-1-eligible nor covered
  by an enhanced dependency, emit a **compile-time error** (configurable down to warning
  via a processor option, e.g. `-Avauban.producerProxy=error|warn`) with an actionable
  message: produce an interface type, or add the `(ProxyLink)` constructor upstream, or
  `opens <pkg> to io.vidocq.vauban.core` in the owning module, or enable
  `vauban:enhance-dependencies`.
- Runtime: keep the fallback (SE classpath users, synthetic beans) but make the error
  message at `VaubanLookup.java:319-327` carry the same actionable guidance.
- Optional follow-up (separate commit): feed the producer-type analysis into
  `vidocq:check-module-info` so missing `opens` for producer types are reported by the
  existing diagnostic goal (that plugin lives in the `vidocq` repo — if touched, separate
  PR there).

### Stage 3b — auto-apply the residual `opens` (no hand-written `--add-opens`)

For the genuinely irreducible residue (a class from a CDI-agnostic third-party module,
with package-private virtuals, whose jar we do NOT rewrite), the `opens` cannot be removed:
the runtime proxy does `MethodHandles.privateLookupIn(Foo.class, vaubanCoreLookup)`, so
`Foo`'s package must be open to `io.vidocq.vauban.core`. The goal here is not to remove it
but to make it **computed, never hand-written**.

**Build-time descriptor (shared by all mechanisms).** The producer-type analysis of Stage 3
already knows every `{ module, package }` that will need an `opens` at runtime. Emit it as a
resource, e.g. `META-INF/vauban/required-opens.list` (one `<moduleName> <packageName>` per
line), in the bean archive that declares the offending producer. Nothing is guessed at boot.

**Primary mechanism — boot agent `Instrumentation.redefineModule` (preferred default).**
An agent can open any modifiable named module at runtime without the third party
cooperating, and Vidocq already ships the agent machinery: `vauban-weaver`'s `WeavingAgent`
(`premain`/`agentmain`), self-attached by `LoadTimeWeaving.attach` via `AttachBack`
(`VirtualMachine.attach`, `jdk.attach.allowAttachSelf` is false so it spawns a child that
attaches back). `core -> weaver` is already a compile dependency
(`LoadTimeWeaving` reads `WeavingAgent.ATTACHED_PROPERTY`), so an `Instrumentation` holder
placed in `vauban-weaver` is reachable from `vauban-core`.

Verified prerequisites this mechanism must add (checked against `main` 2026-09-10, do NOT
assume the agent is already attached):

- **The agent is NOT attached at every boot.** `LoadTimeWeaving.prepare`
  (`vauban-core/.../weaving/LoadTimeWeaving.java`) only attaches when there are unwoven
  normal-scoped beans (typical of IDE builds). A Maven/Gradle build (compile-time weaving)
  and universal-loader mode (a `VaubanClassLoader` in the chain, `doPrepare` returns early)
  attach **no agent**. So Stage 3b needs its **own trigger**: when a `required-opens.list` is
  non-empty on the module path, attach the agent even with an empty weaving plan, purely to
  obtain an `Instrumentation`. Reuse the existing `attach()`/`AttachBack` path.
- **`WeavingAgent.install` currently discards `inst`** (it only calls `addTransformer`). Add a
  static holder (`WeavingAgent.instrumentation()`), set it in `install`, so the opens applier
  can retrieve it. A `VaubanClassLoader` weaving classes at definition does NOT grant
  `redefineModule` power, so an agent is required for the foreign-module opens **even in
  universal-loader mode**.
- **Not needed on the classpath.** In SE-classpath the target lives in the unnamed module,
  already open to everyone; gate the whole mechanism on the target module being named.

Apply, **after** the application `ModuleLayer` is built and **before** the first bean is
resolved (the natural hook is next to the existing `LoadTimeWeaving.prepare(weavingLoader)`
call in `VaubanContainerBuilder` boot, or the layer-creation code in `vauban-classloader`
which Vidocq owns): read every `required-opens.list` on the module path and, for each entry,
resolve the target `Module` from the app layer and call:

  ```java
  inst.redefineModule(
      fooModule,
      Set.of(),                                       // extraReads (none: the runtime proxy
                                                      //   references only java.* types)
      Map.of(),                                       // extraExports
      Map.of("com.acme.lib", Set.of(vaubanCoreModule)), // extraOpens
      Set.of(), Map.of());
  ```

  Guard with `inst.isModifiableModule(fooModule)`; skip unnamed/automatic modules with a
  warning (they are open enough already). Add an IT that resolves a target `Module` **in the
  child app layer** and asserts `redefineModule` from the boot-layer agent succeeds there.
  No `--add-opens` on the command line, no jar rewrite, no invalidated signature.

**Fallbacks (document, do not implement unless asked):**
- **B — launcher-computed `--add-opens`.** The Vidocq CLI / jlink image reads the same
  descriptor and injects `--add-opens <module>/<pkg>=io.vidocq.vauban.core` (argfile or
  `JDK_JAVA_OPTIONS`). Explicit and agent-free, but it is exactly the pattern JEP 8305968
  (integrity by default) discourages; use it only as the SE/classpath safety net.
- **C — descriptor-only jar rewrite (Stage 2 subset).** Class-File API adds
  `opens <pkg> to io.vidocq.vauban.core;` to `Foo`'s `module-info.class` in a shadowing copy.
  Zero runtime opens and no agent (good for a locked-down jlink image / attestation), but it
  touches the jar and invalidates its signature, hence opt-in.

Recommendation to encode: **A** is the default (Vidocq already owns both the agent and layer
creation, and it is the most silent), **C** is the opt-in agent-free path, **B** is the SE
last resort. All three consume the same build-time `required-opens.list`, so the residual
`opens` is generated at build and applied at boot, never written by hand.

**Integrity note.** A and B assume an agent or `--add-opens` is permitted at deployment,
which is an accepted trust decision, not a hidden bypass. State this in the docs alongside
the claim, so the zero-opens story stays honest about its one deployment-time assumption.

## Constraints (non-negotiable)

- **TDD**: failing test first, per stage. Unit tests + `vauban-module-it` /
  `vauban-jpms-it` style integration coverage. Add an IT reproducing the cdi#1015
  scenario: module A (plain library, class `Foo`, NOT a bean archive, no `opens`),
  module B (bean archive, `@Produces @ApplicationScoped Foo`), consumer app on the
  **module path** — proving stage 1 works with zero `opens`.
- **Gates before claiming done** (run yourself, do not trust sub-agent reports):
  `./mvnw clean install` at repo root (never `install` without `clean` — stale `target/`
  gives false results with the codegen modules), the CDI Lite TCK runner
  (`-Ptck`, expected 774/774) and the atinject runner must stay green, and the existing
  weaving ITs must pass. If IDE-related weaving behavior is touched, keep the
  `beans.list` gate intact.
- **Zero new dependencies**, JPMS strict, Class-File API + APT only (ReflectionFactory
  and Unsafe were evaluated and rejected — do not reintroduce them).
- **English** for all code, comments, tests, docs, commit messages.
- Commits: Conventional Commits, GPG-signed, `Signed-off-by` (DCO), with AI provenance
  trailer per workspace `CLAUDE.md`. Branch names `pr/ybl/...`. Do not push or open PRs
  without being asked.
- Update docs: `docs/en/.../internals.adoc` (proxy generation section) and the README
  claims — once stage 1+3 land, rephrase "Zero reflection" as "zero reflection on the
  default path" if any fallback remains, or keep the strong claim only if it becomes true.
- If you find a reproducible bug on the way, log it in `BUG.md` per workspace convention.

## Definition of done

- The cdi#1015 scenario (class-typed cross-module producer, fully public type) runs on
  the module path with **zero `opens`** and **zero runtime class definition**.
- Non-eligible producers fail the **build** with an actionable message (no more
  first-discovery-at-runtime).
- `enhance-dependencies` covers at least the package-private-members case end-to-end in
  an IT (stage 2 PR).
- TCK 774/774 + full reactor green in `clean install`, docs updated.
