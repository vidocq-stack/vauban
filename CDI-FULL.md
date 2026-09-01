# CDI 4.1 Full — planning notes (Portable Extensions vs. static codegen)

> Short version: most of what CDI Full adds on top of Lite (decorators, conversation
> scope, EL integration, the rest of `BeanManager`) fits the **same static codegen
> pipeline** already used for CDI Lite (APT + Class-File API, cf. `INTERCEPTORS.md`).
> The one piece that genuinely does not is **Portable Extensions**
> (`jakarta.enterprise.inject.spi.Extension`) — a runtime-dynamic SPI by design, not
> by implementation laziness. Tracking: [vauban#38](https://codefloe.com/Vidocq/vauban/issues/38)
> (CDI Full) and [vauban#39](https://codefloe.com/Vidocq/vauban/issues/39) (Portable
> Extensions isolated module). Part of the global
> [Jakarta EE Web Profile 11 plan](https://codefloe.com/Vidocq/vidocq/issues/67)
> (`vidocq/WEB-PROFILE.md`).

---

## 1. What CDI Full adds over CDI Lite

Vauban implements CDI 4.1 Lite today (774/774 TCK — see `README.md`). Jakarta EE
Web Profile 11 requires CDI 4.1 **Full**, which adds:

| Feature | Static generation possible? |
|---|---|
| Decorators (`@Decorator`, `@Delegate`) | ✅ yes — same shape as interceptor chains |
| Conversation scope (`@ConversationScoped`) | ✅ yes — a context implementation, no different in kind from the existing normal-scope contexts |
| Expression Language integration (`ELResolver` over CDI beans) | ✅ yes — a resolver adapter, generated or hand-written, no dynamic proxying involved |
| Full `BeanManager` API (beyond the Lite subset) | ✅ yes — API surface completion, not an architectural problem |
| **Portable Extensions** (`jakarta.enterprise.inject.spi.Extension`) | ❌ no — see §3 |

The first four rows are, in effect, "more of the same": extensions of the existing
APT + Class-File API pipeline that already generates client proxies and interceptor
subclasses at build time (`INTERCEPTORS.md` documents that pipeline in detail). They
do not require dynamic bytecode generation, `opens`, or reflection any more than the
CDI Lite path already accepted.

## 2. Why decorators are not the hard part

A decorator is declared statically (`@Decorator` on a class, `@Delegate` on one of
its injection points, enabled via `beans.xml` or a build-compatible extension). The
full decorator stack for a given bean type is therefore **known at APT time**, exactly
like the set of interceptor bindings on a method is known at APT time (but *not* which
concrete interceptor implementations apply at runtime — that stays deployment-dynamic,
per `INTERCEPTORS.md` §8). Generating the delegate-chaining subclass follows the same
recipe already implemented for interceptors:

1. APT emits the proxy *shape* (a subclass overriding the decorated methods).
2. Runtime wiring resolves the *policy* (chain order, which decorators are active)
   through the same reflection-free, `opens`-free in-module component provider used
   for interceptors (`_VaubanComponents`, B1/B2/F2/F3 in `INTERCEPTORS.md`).

Conversation scope is, architecturally, just another context implementation (like the
existing `@ApplicationScoped`/`@RequestScoped` contexts) with its own propagation
rules — no new codegen primitive needed.

## 3. Portable Extensions: the actual blocker

### 3.1 What they are

`jakarta.enterprise.inject.spi.Extension` is a `ServiceLoader`-discovered SPI that
observes container-boot lifecycle events — `ProcessAnnotatedType`,
`ProcessInjectionPoint`, `AfterTypeDiscovery`, `AfterBeanDiscovery`,
`AfterDeploymentValidation`, and more — and can, in response, veto types, alter
injection points, and **register synthetic beans, interceptors, decorators, and
observers** with arbitrary, extension-supplied `InjectionTarget`/`Bean` behavior.

This is fundamentally different from the **Build Compatible Extensions** (BCE)
mechanism already supported for CDI Lite: BCE is explicitly designed to run at build
time, against a static view of the classpath, producing no runtime surprises. Portable
Extensions are explicitly designed to run **at runtime, at container boot**, executing
arbitrary third-party Java code whose effect on the bean graph cannot, in general, be
predicted without actually running it — the CDI specification itself treats this as
the load-bearing difference between the two extension models (this is precisely why
CDI Lite mandates BCE and does not require Portable Extensions).

### 3.2 Why this resists a purely static pipeline

The ecosystem's rule ("no ASM/Byte Buddy, no on-the-fly reflection when it can be
generated at compile/process-classes") is about avoiding *unnecessary* dynamic
codegen, not about refusing to solve genuinely dynamic problems. Portable Extensions
are genuinely dynamic: an extension can, in principle, decide what beans to register
based on logic that only makes sense to execute once (at container boot), and nothing
prevents an extension author from writing one that behaves differently depending on
runtime state. Forcing 100% static resolution here means either:

- restricting what extensions are allowed to do (breaking compatibility with the
  existing Portable Extension ecosystem, and very likely failing CDI Full TCK tests
  that exercise exactly this dynamism), or
- executing extension code at build time as if it were BCE (workable for extensions
  that are in practice deterministic, but not a general solution — and not something
  the spec lets us assume for arbitrary third-party extensions).

### 3.3 Decision: isolated legacy runtime-codegen module

Rather than either compromise, Portable Extensions get their own, opt-in module
(name TBD, e.g. `vauban-full-portable-extensions`), **not** pulled in by default:

- An application on CDI Lite, Core Profile, or Web Profile *without* legacy Portable
  Extensions never sees this module on its module-path, and pays none of its cost.
- Inside that module, proxy/bean bytecode for extension-registered synthetic beans is
  generated **using the Class-File API (JEP 484)** — the same JDK-native tool already
  used by the APT pipeline — except invoked **at runtime** instead of at
  `process-classes` time. This is the key point: the *tool* does not change (still no
  ASM/Byte Buddy, still zero new external dependency), only the *timing* does, and
  only inside this one clearly-labeled, opt-in module.
- Generated classes are loaded via `MethodHandles.Lookup.defineHiddenClass`, also
  JDK-native — no `Unsafe`, no classloader tricks.
- `module-info.java` for this module scopes `opens` narrowly to the packages that
  actually need privileged `Lookup` access; never `opens ... to *`, consistent with
  the workspace's Java Modules guardianship rules.
- The exception is documented here, and cross-referenced from the module's
  `module-info.java` javadoc, precisely because it *is* an exception: the reason is
  that the SPI is dynamic by design, not a shortcut taken for convenience.

Tracking issue: [vauban#39](https://codefloe.com/Vidocq/vauban/issues/39).

## 4. Status

| Item | Status |
|---|---|
| Decorators | ❌ not started — [vauban#38](https://codefloe.com/Vidocq/vauban/issues/38) |
| Conversation scope | ❌ not started — [vauban#38](https://codefloe.com/Vidocq/vauban/issues/38) |
| EL integration | ❌ not started — [vauban#38](https://codefloe.com/Vidocq/vauban/issues/38) |
| `BeanManager` completion | ❌ not started — [vauban#38](https://codefloe.com/Vidocq/vauban/issues/38) |
| Portable Extensions (isolated module) | ❌ not started, architecture decided (§3.3) — [vauban#39](https://codefloe.com/Vidocq/vauban/issues/39) |
| CDI Full TCK | not targeted yet (superseded from `README.md`'s "future `vauban-full` module" note once this plan lands) |

See also: `README.md` (current CDI Lite feature/TCK status), `INTERCEPTORS.md` (the
static codegen pipeline this plan builds on).
