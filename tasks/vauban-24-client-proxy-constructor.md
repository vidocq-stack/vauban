# vauban#24 — re-study: the bean constructor runs when the client proxy is created

Status: **phases 0, 1 and 2 implemented** (`pr/ybl/vauban-24-proxy-link`, 2026-08-10) —
index-based unproxyable diagnostic, opt-in `ProxyLink` marker constructor, and **automatic
weaving by the plugin** (`ProxyLinkWeaver`/`ProxyLinkWeaving`, `process-classes`): the
application never declares the marker, per the maintainer requirement of 2026-08-10. The
"missing constructor" case is downgraded to a compile-time warning (kind
`UNPROXYABLE_BEAN`) and remains a boot error without the plugin. CDI Lite TCK unchanged at
774/774. Follow-up architecture (javac plugin, universal loader): see
`tasks/vauban-classloader-universal.md`. Still optional post-0.3.0: pure-delegate
`implements` proxies for interface-typed injection points.
Date: 2026-08-09. Original reporter: Sébastien Blanc (Rossignol).

Implementation note (phase 0): the spec checks already existed in `DeploymentValidator`
but relied on `Class.forName(TCCL)` with a silent `catch (ClassNotFoundException)` —
inert at compile time and on the module path, which is precisely the Rossignol deployment.
Phase 0 therefore consisted in making them index-based (reflection as fallback), not in
creating them.

## 1. What is established

The client proxy extends the bean, and its no-arg constructor chains into a bean
constructor — on both sides of the codegen:

- source: `ClientProxySourceRenderer` → `public Bean_ClientProxy() { super(<defaults>); }`
- bytecode: `ClientProxyEmitter` → `invokespecial Bean.<init>` with one `pushDefault` per
  parameter

Measured (throwaway probe on `RuntimeClientProxyGenerator`, not committed):

| Case | Observed result |
|---|---|
| `@ApplicationScoped` bean with a no-arg constructor | **2 constructions** for a single contextual instance — exactly the report |
| bean whose only constructor is `@Inject Foo(Collaborator c)` **and which dereferences `c`** | **`NullPointerException`** at proxy creation: `Cannot invoke "Collaborator.name()" because "collaborator" is null` |

**The second case was not in the report and changes the nature of the problem.** This is
not merely a duplicated side effect the user could avoid through discipline: constructor
injection — the style the CDI specification recommends — makes any normal-scoped bean
**crash** as soon as its constructor uses one of its parameters. The proxy passes it
`null` (`findSimplestConstructor` + `pushDefault`).

That invalidates the "document it and recommend `@PostConstruct`" option as a sufficient
answer: no side-effect discipline saves `this.name = collaborator.name()`.

## 2. The unavoidable constraint

The JVM requires every `<init>` to chain to an `<init>` of its own class or of its
**direct superclass**. The proxy must be assignable to the injected type; when that type
is a concrete class (`@Inject Oidc` in the reporter's case), the proxy must extend it and
can therefore only call `Oidc.<init>`. **It is impossible to avoid the bean constructor
without acting on the bean itself.** Every solution goes through one of the families
below.

## 3. The evaluated families

### A. Marker constructor added to the bean (post-compile, Class-File API)

`vauban-maven-plugin`, `process-classes` phase: add to every proxyable bean a synthetic
`Bean(VaubanProxyMarker)` constructor with an empty body (recursive chaining when the
superclass has a constructor), then rewrite the single `invokespecial` of the — already
compiled — `Bean_ClientProxy.<init>` to target it.

- ✅ 100% static, AOT/GraalVM-safe, zero reflection: matches the project philosophy.
- ✅ Preserves the in-module `new Bean_ClientProxy()` generated in `_VaubanComponents`
  (no `opens` or reflection reintroduced).
- ✅ Solves both cases of the table, including the NPE.
- ⚠️ Modifies user bytecode. The added constructor must be `ACC_SYNTHETIC` and filtered by
  the indexer, otherwise it becomes a constructor-injection candidate.
- ⚠️ Beans coming from an **already-compiled jar** cannot be patched by the current build:
  they need the marker produced by their own build. Acceptable in the Vidocq ecosystem
  (every module goes through the plugin), with a fallback to today's behaviour otherwise.
- Existing infrastructure: `ModuleAnalyzer` already parses compiled classes with
  `ClassFile.of().parse(...)`; `InterceptedEmitter` produces some. Only the transformation
  is missing.
- Estimated effort: 2–4 d with the inheritance and fallback tests.

### B. Instantiation without calling `<init>` (the Weld way)

`ReflectionFactory.newConstructorForSerialization(proxyClass, Object::new)` allocates the
proxy without running any constructor.

- ✅ ~10 lines, solves everything, including the NPE.
- ❌ Runtime reflection + `jdk.unsupported`: against the project's "static generation, no
  runtime reflection" rule, and requires dedicated GraalVM configuration.
- ❌ Breaks the current static path: `_VaubanComponents` does `new Bean_ClientProxy()`
  in-module precisely to avoid reflection. Giving that up would undo the gains of the BCE
  static-metadata effort (removed `opens`).

To be discarded, except as a runtime fallback for beans from unpatchable jars (cf. limit
of A). **2026-08-10 update: definitively abandoned — the universal loader
(`tasks/vauban-classloader-universal.md`) makes it unnecessary.**

### C. Interface-based proxy

Only works when the injection point is typed by an interface. The reported case
(`@Inject Oidc`, a concrete class) is not covered. **Does not solve the problem**, only a
part of it; to be considered only as a later optimisation.

### D. 100% APT solution, without the Class-File pass (re-studied 2026-08-10 — impossible)

Legitimate question: the proxy is already generated as source by the APT
(`ClientProxySourceRenderer`), why not solve the problem there and avoid the post-compile
transformation? Three walls:

1. **JSR 269 can only create, never modify.** The official API (`Filer.createSourceFile`,
   `createClassFile`) produces *new* files; touching an existing compilation unit is
   outside the API. Yet the §2 constraint requires the marker constructor to live **in
   the bean class** — user code, out of the APT's reach. Lombok only manages it by
   mutating the AST through javac internals (`com.sun.tools.javac.tree.TreeMaker` +
   agent / `--add-opens jdk.compiler`): unsupported, fragile under JDK 25 strong
   encapsulation, broken under ECJ, and contrary to the project rule (no internal APIs, no
   hidden magic).
2. **Even the proxy side cannot stay pure source.** A constructor written in Java
   necessarily calls an *existing* superclass constructor:
   `super(new VaubanProxyMarker())` does not compile while the bean lacks that
   constructor — and it never gets it through APT (wall 1). Chicken-and-egg inside a
   single compilation round.
3. **Bytecode itself offers no escape.** The JVM verifier requires every `<init>` to chain
   to an `<init>` of its class or direct superclass; "call no constructor" is expressible
   neither in source nor in verifiable bytecode — only the runtime routes of B
   (`ReflectionFactory`/`Unsafe`) bypass it, with the flaws already listed.

What the APT *can* contribute to **A**: emit at compile time the exact list of proxyable
beans (a manifest), so the `process-classes` pass is a targeted O(beans) transformation
rather than a jar scan. To fold into the A effort.

Also worth noting: the Class-File pass is **not a new build mechanism** — the
`vauban-maven-plugin` `GenerateMojo` already runs at `process-classes`; A adds a
transformation of existing classes to it, nothing more.

## 4. Correction about the spec (2026-08-10)

The first version of this document claimed that rejecting a normal-scoped bean without a
no-arg constructor "would restrict the spec". It is the **opposite**: the *Unproxyable
bean types* section of CDI 4.1 explicitly lists — `final` class, non-private `final`
methods, **absence of a non-private no-arg constructor** — and requires the container to
treat them as a **deployment error**. The reporter's bean (single parameterized `@Inject`
constructor) is therefore already unproxyable in the strict sense of the spec: the honest
error is the *portable* answer, and the marker constructor is a **convenience extension**
(like Weld's relaxed construction), not an obligation. Consistent with the CDI Lite TCK
passing 774/774 without covering this point.

## 5. Adopted plan (2026-08-10, after maintainer discussion)

Three incremental phases; nothing is thrown away between phases.

### Phase 0 — spec-mandated diagnostic (with 0.3.0)

Detect unproxyable normal-scoped beans (no non-private no-arg constructor, `final` class,
non-private `final` method) and **reject with a clear message**: at compile time through
the APT when the bean belongs to the current compilation unit, at boot otherwise. Turns
today's silent NPE into an explicit error — the behaviour the spec requires. ~1 d.

*As implemented*: the checks became index-based (reflection fallback); the
missing-constructor case uses the dedicated `UNPROXYABLE_BEAN` kind, downgraded to a
compile-time warning because phase 2 weaves it later in the same build; it remains a
`DeploymentException` at container start. Final members stay fatal.

### Phase 1 — manual, opt-in `ProxyLink` convention (with 0.3.0)

The developer may declare `Bean(ProxyLink)` (empty body, blank `final` fields assigned to
their defaults); the bean then becomes cleanly proxyable:

- `vauban-api`: `ProxyLink` marker type (not instantiable outside the container);
- `ClientProxyShapeFromElements`/`ClientProxySourceRenderer` (source) and
  `ClientProxyEmitter`/`RuntimeClientProxyGenerator` (bytecode): target that constructor
  when it exists, instead of `super(<defaults>)`;
- indexer: exclude the marker constructor from injection candidates;
- the phase-0 diagnostic accepts a bean carrying the marker (documented extension).

100% APT on the build side, no bytecode transformation: wall 2 of option D falls as soon
as the constructor exists in the source. TDD: construction counter + `@Inject` bean
dereferencing its parameter, red first. Also verify `@PostConstruct` does not fire on the
proxy. ~1–2 d. Unblocks Rossignol (at the cost of an app change — superseded by phase 2).

### Phase 2 — automatic weaving (2026-08-10 requirement: no app change at all)

`vauban-maven-plugin`, `process-classes` phase: weave the `ACC_SYNTHETIC` marker into
every proxyable bean that does not declare it (`ProxyLinkWeaver` in core,
`ProxyLinkWeaving` in the plugin; superclasses compiled by the same module woven
recursively), and retarget APT-emitted proxies onto it. The manual phase-1 convention
becomes unnecessary but stays honoured. Real-instantiation paths never select the marker:
`BeanDiscovery`, `ComponentCollector`, `InterceptorBeanWrapper` and both `$$Intercepted`
mirrors exclude it.

*Follow-up decided in discussion (2026-08-10)*: the Maven plugin does not cover ECJ or
IDE-internal builds (IntelliJ), and cannot weave third-party jars. The retained target
architecture is tiered — `autoStart` **javac plugin** embedded in the APT jar (nominal
static path: Maven, Gradle, bare javac), Maven plugin kept for ecosystem jar
builds and sjar encryption, and the **universal loader** with source + transformer plugins
for everything else (ECJ, unwoven third-party jars, sjars). Full design:
`tasks/vauban-classloader-universal.md`.

### Phase 3 — load-time weaving agent (2026-08-10, after the IntelliJ field test)

Empirical finding, running cassini-rest-example from IntelliJ with its native builder: the
javac plugin does NOT cover the IDE build. IntelliJ's build system (JPS) flushes the
hand-written classes to disk from memory ~4 s AFTER javac's `COMPILATION finished` event
(generated-source classes were flushed before it), so the plugin wove stale files, its
idempotence check saw the previous Maven output and self-cleaned, and JPS then overwrote
the beans unwoven — a hybrid state (proxies retargeted, beans without marker) that fails
at boot. Conclusion: post-hoc disk weaving cannot cover the IDE build, structurally.

`ReflectionFactory` relaxed construction (option B) was re-examined for this case and
stays rejected: Vidocq app modules keep bean packages fully encapsulated (zero
`opens`/`exports`), which blocks reflective instantiation under Java Modules entirely.

Adopted and implemented instead: a **load-time weaving tier**.

- New zero-dependency module `vauban-weaver` — the canonical `ProxyLinkWeaver`
  transformations moved there (core/processor/maven-plugin now depend on it), packaged as
  an instrumentation agent (`Premain-Class`/`Agent-Class`, plan-driven
  `ClassFileTransformer`, `AttachBack` child-process attacher). `requires
  java.instrument` is deliberately non-static: on the module path the dynamically
  attached agent classes resolve to the named module, and a static requires leaves
  `java.instrument` unresolved (`IllegalAccessError` — found the hard way).
- `vauban-core` `LoadTimeWeaving`: detection driven exclusively by
  `META-INF/vauban-beans.list` (present in every APT build, including IDE builds; absent
  from synthetic/TCK archives, which therefore keep the spec-mandated unproxyable
  errors), byte-level (never loads classes), self-attaches via a child process, per-JVM
  idempotent. Deployment validation skips the planned classes. Opt-out:
  `-Dvauban.weaving.loadtime=disabled`.
- Hook runs twice: **early in `VidocqBootstrap.start()`** — mandatory, because Cassini's
  scope extension loads `@Path` classes in `beforeStart`, and a class loaded before the
  attach can no longer be woven (adding a constructor is not a valid retransformation) —
  and as a no-op backstop in `VaubanContainerBuilder.build()` for plain Vauban SE usage.
- Proof: simulated IDE state on cassini-rest-example (full `javac -proc:none` recompile
  over a Maven build: beans unwoven, proxies chaining the business constructor), run on
  the module path with zero `opens` — boots through the agent and `/api/stats` answers
  200, identical to the woven build.

Docs: `docs/en|fr … internals.adoc#weaving-tiers` (tier table + mechanics) and
`usage.adoc` (IDE note).

## 6. Guardrails

- The CDI Lite TCK (774/774) covers neither the double construction nor the NPE: the
  phase-1/2 non-regression tests are the only net — keep them.
- Phases 0–2 are implemented on `pr/ybl/vauban-24-proxy-link` (PR #26, stacked on #25):
  reactor 526 tests green, weaving exercised for real by the examples modules, TCK
  774/774 unchanged.
