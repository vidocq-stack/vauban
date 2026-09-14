# Eliminate the runtime-generation fallbacks Implementation Plan

> **SUPERSEDED — kept for the record, do not execute.** Every task below was implemented (27 commits,
> `git log --grep='#42'`), and the checkboxes were never ticked: reading them as work to do is wrong.
> Where things actually stand:
>
> - **What was built**, task by task, with outcomes: `tasks/todo.md`, sections *#42 Stage 4* and *#53*.
> - **What is left of the four residual runtime paths**, with the status of each on `main`:
>   `INTERCEPTORS.md` §10.3. Three are closed. The fourth — a non-public `@AroundInvoke`, which keeps
>   `privateLookupIn` and so keeps the interceptor module's `opens` — is an **explicit scope cut** of
>   vauban#42, not an oversight.
> - Two deliberate deviations from the plan below: `required-opens.list` became the *placement*
>   manifest rather than an opens list (`internals.adoc` explains why the name stayed), and the
>   boot-time opens of Stage 3b ship **opt-in** (`-Dvauban.opens.auto=true`) rather than as the
>   default.
>
> An unplanned Stage 4 — proxy placement through the Vauban class loader and the Java SE launcher —
> was added on top and is not described here at all.

**Goal:** Make Vauban's default path fully build-time (no runtime `defineClass`/`privateLookupIn`) for every producer whose type is fully public, turn the non-eligible cases into actionable build errors, and make the last irreducible `opens` computed at build and applied at boot — never hand-written.

**Architecture:** Four stages. (1) The annotation processor generates client proxies for normal-scoped *producer* beans at build time, placed in the producer's own package, when the produced class type is "fully public". (3b) A build-time `required-opens.list` descriptor drives an agent-applied `Instrumentation.redefineModule` at boot for the residue. (3) Non-eligible producers become configurable build diagnostics. (2) An opt-in `vauban:enhance-dependencies` Maven goal rewrites third-party jars for the package-private cases. Stage order of execution: 1 → 3 → 3b → 2 (each independently testable and TCK-gated).

**Tech Stack:** Java 25 (Temurin), Maven 3.9.16, JPMS strict, JDK Class-File API (JEP 484) + `javax.annotation.processing` APT, zero external dependencies.

**Spec:** `vauban/ELIMINATE_RUNTIME.md` (the plan argues from it; executors read both). Tracking issue: Vidocq/vauban#42.

## Global Constraints

- **Java 25 + Maven 3.9.16**, pinned via `.sdkmanrc` (`sdk env`). Build with `./mvnw` (blocked in the Bash tool — spawn it from a `node *.mjs` wrapper).
- **Zero new dependencies.** Class-File API + APT only. `ReflectionFactory` and `Unsafe` were evaluated and rejected — do not reintroduce.
- **JPMS strict**: minimal `exports`, no unjustified `opens`, every module has its own `module-info.java`.
- **TDD, red → green → refactor**, failing test first per task.
- **Gates before claiming a stage done** (run yourself, never trust a sub-agent report): `./mvnw -T1C clean install` at repo root (never `install` without `clean` — stale `target/` gives false results with the codegen modules); `./mvnw -ntp verify -pl vauban-tck-runner -Ptck` must stay **774/774**; `./mvnw -ntp verify -pl vauban-atinject-tck-runner -Ptck` must stay green. Baseline captured 2026-09-10: CDI Lite 774/774, atinject green.
- **English** everywhere (code, comments, tests, docs, commit messages).
- **Commits**: Conventional Commits + `#42`, GPG-signed (`-S`, configured), DCO `Signed-off-by` (`-s`, configured), AI provenance trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Branch `pr/ybl/eliminate-runtime-42`. Do NOT merge PRs.
- **Baseline is green**: skipTests `clean install` = BUILD SUCCESS (15 modules), TCK 774/774.

---

## Verified anchor map (main @ 2026-09-10)

`vauban-processor/.../VaubanProcessor.java`
- L410 `if (bean.kind() == BeanDescriptor.BeanKind.MANAGED)` — SOURCE `_ClientProxy` emission gate (producers skip it).
- L568 `if (bean.kind() != BeanDescriptor.BeanKind.MANAGED || !bean.scope().isNormal()) continue;` — weave-plan proxy list.
- L136 `externalClassNames`, L419 `if (externalClassNames.contains(bean.beanClass()))`, L416 split-package comment; populated L805/L857.
- L104 `BEANS_LIST_PATH="META-INF/vauban-beans.list"` (write L1034); L1300-1303 services-file write; L459 `ClientProxySourceRenderer` invocation.

`vauban-core/.../container/InterceptorBeanWrapper.java`
- L360 `resolveProxyTargetClass` (called L220); producer concrete-type branch L361-368.
- L312-316 comment: in-module provider does NOT cover producer-bean proxy.
- L459 `loadOrDefineClassRobustly` (used L329/L637/L823).
- L449-452 provider-first: `container.componentProviders() … providers.create(ctor.getDeclaringClass().getName(), args)`.
- L254 `java.lang.reflect.Proxy.newProxyInstance(` (interface producer); L369-372 VAU-PROXY-INTERFACE.

`vauban-core/.../container/VaubanLookup.java`
- L254 `makeAccessible`; L262-268 F3 rule (`isPubliclyInvocable` → `ensureReads`, skip `privateLookupIn`).
- L318 `privateLookupIn`; L320-326 not-opened error (`opens <pkg> to io.vidocq.vauban.core;`).

`vauban-core/.../proxy/` — `RuntimeClientProxyGenerator.findSimplestConstructor` L108, `needsMethodHandleDispatch` L140; `ClientProxyEmitter` `<clinit>` privateLookupIn+findVirtual L229-257, default-value ctor L86-102 (`pushDefault` L131).

`vauban-processor/.../codegen/proxy/` — `ClientProxyShapeFromElements` (L51 `needsMethodHandle` always false); `ClientProxySourceRenderer.render` (header L81).

`vauban-processor/.../codegen/provider/ComponentProviderGenerator.java` — `createClientProxy` L269-273, keyed by **proxy class name** via `switch (proxyClassName)`; `SIMPLE_NAME="_VaubanComponents"` L72.

`vauban-maven-plugin/.../generate/VaubanGenerator.java` — `generate` L105; jar scan L110-131; step 8 pre-gen proxies L231-243 (`RuntimeClientProxyGenerator.generate` L260, `writeClassFile` L261); step 9 provider gen L295/L353.

`vauban-core/.../container/VaubanContainerBuilder.java` — `LoadTimeWeaving.prepare(weavingLoader)` L667; weavingLoader L664-665; `classLoader` field L51 / setter L181; `buildCompositeClassLoader` L393; discovery TCCL L506-507.

`vauban-weaver/.../WeavingAgent.java` — `install(String, Instrumentation)` L52 **discards `inst`** (only static is `ATTACHED_PROPERTY` L40). `ProxyLinkWeaver.addMarkerConstructor` L106, `retargetProxyConstructor` L131.

IT reality: `vauban-jpms-it` is a DEAD stub (no pom, not in root). `vauban-module-it` (root pom) is single strict module, zero opens, `provides _VaubanComponents`, module-path via surefire. **No multi-named-module + separate consumer IT exists — Stage 1 must create one.**

---

## File Structure

New:
- `vauban-producer-module-it/` — multi-submodule IT reproducing cdi#1015 (Stage 1 acceptance): `lib-a` (plain module, class `Foo`, no opens, no APT), `bean-b` (bean archive, `@Produces @ApplicationScoped Foo`, APT-compiled), and a test that launches a child JVM on the module path and asserts behaviour. Not in the reactor until it compiles green.
- `vauban-processor/.../codegen/proxy/ProducerProxyShapeFromElements.java` — shape derivation for an external produced supertype (Stage 1).
- `vauban-processor/.../ProducerProxyEligibility.java` — the "fully public" predicate + reason enum (Stages 1 & 3).
- `vauban-weaver/.../AgentAccess.java` — static `Instrumentation` holder (Stage 3b).
- `vauban-core/.../opens/RequiredOpens.java` + `OpensApplier.java` — read `required-opens.list`, apply via `redefineModule` (Stage 3b).
- `vauban-maven-plugin/.../enhance/EnhanceDependenciesMojo.java` — opt-in goal (Stage 2).

Modified: `VaubanProcessor` (lift gate for eligible producers; write `required-opens.list`; diagnostics), `ComponentProviderGenerator` (key `createClientProxy` by produced type too), `InterceptorBeanWrapper` (provider-first by produced type before runtime gen), `VaubanGenerator` (parity), `WeavingAgent` (retain `inst`), `VaubanContainerBuilder` (opens hook next to L667).

---

## Stage 1 — build-time producer proxies for fully-public types

**Goal:** a `@Produces @ApplicationScoped Foo` where `Foo` is a public class from a module that does not open its package runs on the module path with **zero opens** and **zero runtime class definition**, provided `Foo` is fully public.

### Task 1.1: cdi#1015 reproduction IT (RED — proves the gap)

**Files:**
- Create: `vauban-producer-module-it/pom.xml` (aggregator), `.../lib-a/` (module `it.liba`, `public class Foo` with a public method, `module-info.java` = `exports it.liba;` only — NO opens, NO vauban), `.../bean-b/` (module `it.beanb`, `@Produces @ApplicationScoped Foo produce()`, `requires io.vidocq.vauban.core; requires it.liba; requires jakarta.cdi; requires jakarta.enterprise.lite?`), `.../consumer/` (module `it.consumer`, `@Inject Foo`, a `main` that calls a `Foo` method through the injected proxy and prints a sentinel).
- Test: `.../bean-b/src/test/java/.../Cdi1015ReproTest.java` (or a harness module) that launches `java -p <closure> -m it.consumer/it.consumer.Main` and captures stdout/exit.

- [ ] **Step 1: Write the failing test** — launch the child JVM on the module path (no `--add-opens`), assert the sentinel is printed and exit 0. Initially this FAILS: today the container falls to runtime generation and throws the L320-326 opens error.
- [ ] **Step 2: Run it, verify it fails** with the "opens it.liba to io.vidocq.vauban.core" runtime error (capture the exact message from the child stderr).
- [ ] **Step 3: (defer green to 1.2-1.6).** Commit the IT as `@Disabled("cdi#1015 — enabled by Stage 1")` OR keep it asserting the current failure with a `// TODO flip when Stage 1 lands`. Do NOT add it to the root reactor yet.
- [ ] **Step 4: Commit** `test(vauban-producer-module-it): reproduce cdi#1015 class-typed cross-module producer (#42)`.

### Task 1.2: `ProducerProxyEligibility` predicate (fully-public)

**Interfaces:** Produces `enum Reason { ELIGIBLE, NOT_PUBLIC, FINAL_CLASS, SEALED, NO_ACCESSIBLE_CTOR, PACKAGE_PRIVATE_VIRTUALS, FINAL_VIRTUALS }` and `static Reason of(TypeElement, Types, Elements)`.

- [ ] Step 1: unit test in `vauban-processor` src/test — feed model `TypeElement`s (via compile-testing or a small `javax.lang.model` fixture) for: public non-final class with public/protected non-final virtuals + accessible ctor → `ELIGIBLE`; final class → `FINAL_CLASS`; a package-private non-final virtual → `PACKAGE_PRIVATE_VIRTUALS`; no accessible ctor → `NO_ACCESSIBLE_CTOR`; sealed → `SEALED`.
- [ ] Step 2: run, verify fail (class absent).
- [ ] Step 3: implement `ProducerProxyEligibility.of(...)` mirroring CDI §3.10 unproxyable rules PLUS the cross-package restriction (any non-private package-private virtual ⇒ not eligible, because a proxy in B's package cannot override it and code inside `Foo`'s package could bypass delegation).
- [ ] Step 4: run, verify pass.
- [ ] Step 5: commit `feat(vauban-processor): producer-proxy eligibility predicate (#42)`.

### Task 1.3: `ProducerProxyShapeFromElements` (external supertype shape)

**Interfaces:** `ClientProxyShape forExternalProducer(TypeElement producedType, String targetPackage)` — target package = the producer bean's package (NEVER the produced type's), proxy simple name deterministic and collision-safe (`<ProducedSimpleName>_<hash8>_ClientProxy`), `needsMethodHandle=false` always, `superCtorParams` from the chosen accessible constructor (prefer existing `(ProxyLink)`, else no-arg, else simplest accessible — same selection semantics as `RuntimeClientProxyGenerator.findSimplestConstructor` L108 but computed here), only public/protected virtuals collected.

- [ ] Step 1: unit test — shape for a fixture produced type has the producer's package, no MethodHandle, correct super-ctor arity, only public/protected methods.
- [ ] Step 2: fail. Step 3: implement (reuse `ClientProxyShape` record; do not touch `ClientProxyShapeFromElements` L51 invariant). Step 4: pass. Step 5: commit.

### Task 1.4: render + emit the producer proxy, register keyed by produced type

**Files:** modify `VaubanProcessor` (lift the L410 MANAGED gate for normal-scoped producers whose produced type is `ELIGIBLE`; keep the L419 split-package guard — refuse if target would be the produced type's package), `ClientProxySourceRenderer` (handle a superclass in another package: explicit `super(<defaults>)`, no package-private access, no MethodHandle), `ComponentProviderGenerator` (add a `createClientProxy` entry **keyed by produced type FQN** in addition to the L269-273 proxy-name switch).

- [ ] Step 1: golden-source test — the generated `.java` extends `it.liba.Foo`, lives in the producer package, `super(...)` with defaults, delegates only public/protected virtuals.
- [ ] Step 2: fail. Step 3: implement. Step 4: pass. Step 5: `./mvnw -T1C clean install` green + commit.

### Task 1.5: provider-first lookup by produced type in `InterceptorBeanWrapper`

**Files:** modify `InterceptorBeanWrapper` around L449-452 / L312-316: before `loadOrDefineClassRobustly` (L459) for a producer, consult `container.componentProviders()` for a proxy keyed by the produced type; only fall to runtime if absent.

- [ ] Step 1: enable the 1.1 IT (flip `@Disabled`), add it to the reactor (root pom). Step 2: run — now GREEN (sentinel printed, zero opens). Step 3: refactor. Step 4: gate `clean install` + **TCK 774/774** + atinject. Step 5: commit.

### Task 1.6: Class-File API parity in `VaubanGenerator`

- [ ] Mirror 1.4 for external jars in `VaubanGenerator` step 8 (L231-243): when a scanned jar declares an eligible producer, pre-generate the producer proxy in the producer package + register it. Test via a plugin IT. Gate + commit.

**Stage 1 DoD:** cdi#1015 IT green on the module path, zero opens, zero runtime defineClass; TCK 774/774; atinject green.

---

## Stage 3 — build-time diagnostics for non-eligible producers

**Files:** `VaubanProcessor` + reuse `ProducerProxyEligibility`.

- [ ] Task 3.1: when a normal-scoped producer's class type is NOT eligible and NOT interface-typed, emit a compile diagnostic, severity from `-Avauban.producerProxy=error|warn` (default `warn` first to avoid breaking existing builds; flip to `error` once the ecosystem is clean). Message names the reason + the three outs (interface type / `(ProxyLink)` upstream / `opens … to io.vidocq.vauban.core` / `vauban:enhance-dependencies`). TDD with compile-testing asserting the diagnostic. Gate + commit.
- [ ] Task 3.2: enrich the runtime error at `VaubanLookup` L320-326 with the same actionable guidance (unchanged behaviour, better message). Unit test on the message. Gate + commit.

---

## Stage 3b — auto-apply the residual opens (option A, verified feasible)

**Files:** `vauban-weaver/.../AgentAccess.java` (static `Instrumentation` holder), `WeavingAgent.install` (set it — L52), `VaubanProcessor` (write `META-INF/vauban/required-opens.list` for producers that fall to runtime), `vauban-core/.../opens/{RequiredOpens,OpensApplier}.java`, `VaubanContainerBuilder` (call the applier next to L667).

Verified prerequisites (see ELIMINATE_RUNTIME.md §3b): the agent is NOT attached at every boot (only on unwoven beans), `install` currently discards `inst`, a `VaubanClassLoader` does NOT grant `redefineModule` power, and the classpath (unnamed module) needs nothing.

- [ ] Task 3b.1: `AgentAccess` holder + retain `inst` in `WeavingAgent.install`; unit test that after a simulated `install` the holder returns non-null. Gate + commit.
- [ ] Task 3b.2: APT writes `required-opens.list` (`<module> <package>` per line) for each producer that is interface-free, not eligible, and lacks an `opens` — computed from the model. Golden-resource test. Commit.
- [ ] Task 3b.3: `OpensApplier`: on the module path, if `required-opens.list` is non-empty and the target module is named + `isModifiableModule`, ensure an agent is attached (reuse `LoadTimeWeaving.attach` with an empty weaving plan when none present) and call `inst.redefineModule(fooModule, Set.of(), Map.of(), Map.of(pkg, Set.of(vaubanCore)), Set.of(), Map.of())`. IT: a produced type with a package-private virtual (Stage-1-ineligible) resolves on the module path via 3b with NO hand-written `--add-opens`, resolving the target `Module` in the child app layer. Gate (`clean install` + TCK) + commit.

---

## Stage 2 — opt-in `vauban:enhance-dependencies` (heaviest, last)

**Files:** `vauban-maven-plugin/.../enhance/EnhanceDependenciesMojo.java` (goal `enhance-dependencies`, off by default), reusing `ProxyLinkWeaver.addMarkerConstructor` (L106) + Class-File API module-info rewrite.

- [ ] Task 2.1: for each configured dependency jar, produce a woven copy under `target/vauban-enhanced-deps/`: marker constructor on target classes, generate `Foo_ClientProxy` + `_VaubanComponents` **inside `Foo`'s own package** (legal — we own the rewritten jar), add `provides io.vidocq.vauban.api.VaubanComponentProvider with …` (+ `requires io.vidocq.vauban.api`) to `module-info.class`. Note signatures are invalidated (why it is opt-in). TDD on a fixture jar. Commit.
- [ ] Task 2.2: module-path substitution via the copy-dependencies/shadow pattern; automatic-module/classpath jars documented, no crash. IT covering the package-private-members case end-to-end. Gate + commit.

**Stage 2 DoD:** `enhance-dependencies` covers the package-private case end-to-end in an IT; TCK still 774/774.

---

## Self-review

- Spec coverage: Stage 1 = ELIMINATE_RUNTIME §Stage 1; Stage 2 = §Stage 2; Stage 3 = §Stage 3; Stage 3b = §Stage 3b (option A). The four residual runtime paths in ELIMINATE_RUNTIME §"Current state" map to: producer-class → Stage 1/3b, `@AroundInvoke` non-public → out of scope here (separate follow-up, note in PR), inherited protected virtuals cross-module → Stage 1 eligibility refuses + Stage 3 diagnoses, non-APT jars → Stage 2.
- Type consistency: `ProducerProxyEligibility.Reason` used by 1.2/1.4/3.1; `ClientProxyShape` reused (not a new type); `createClientProxy` keyed by produced-type FQN consistently in 1.4 (generator) and 1.5 (consumer).
- Known scope cut stated in PR: interceptor non-public `@AroundInvoke` (site 4) and inherited-protected-virtual `<clinit>` MethodHandle are NOT closed here; they remain documented residues.
