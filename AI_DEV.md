# AI-Assisted Development Report — Vauban

> Lessons learned from building Vauban with Claude Code (Sonnet 4.6 / Opus 4.7).
> Actual duration: **~1 month**. Developer: 1 senior (25 years of Java/Jakarta EE experience).

---

## What was built

| Module | Role |
|--------|------|
| `vauban-indexer` | Bytecode scanner with no external dependency (replaces Jandex) |
| `vauban-core` | CDI 4.1 Lite container — injection, scopes, events, interceptors, client proxies |
| `vauban-processor` | Annotation processor (APT) — discovery and code generation at compile time |
| `vauban-maven-plugin` | Maven plugin — `generate`, `encrypt`, `dist` |
| `vauban-classloader-spi` | Extensible SPI for custom class loading |
| `vauban-sjar` | In-JAR AES-256-GCM encryption based on `module-info.class` |
| `vauban-junit` | JUnit 6 extension for CDI tests |
| `vauban-tck-runner` | Official CDI TCK 4.1 runner |
| `vauban-test-suite` | Integration test suite |

**Cross-cutting characteristics**

- Native JPMS — each module has its own `module-info.java`
- Zero external bytecode dependency — generation via the JDK 25 Class-File API
- Virtual threads — `ScopedValue` (JEP 487) instead of `ThreadLocal` everywhere
- Build Compatible Extensions — 5 phases, compile-time + runtime + replay
- CDI TCK 4.1 Lite: **774/774 tests (100%)**

---

## Estimate without AI

### Solo developer (senior profile, 25 years of experience)

| Block | Estimated duration |
|------|--------------|
| Bytecode indexer + APT | 1.5 months |
| Core container (injection, scopes, events) | 3 months |
| Interceptors + proxies (JDK 25 Class-File API) | 2 months |
| Build Compatible Extensions — 5 phases | 2 months |
| Maven plugin + SJAR | 1.5 months |
| TCK — reaching 100% (774/774) | 2 months |
| Cross-cutting JPMS friction | +30% across the board |
| **Total** | **~15–18 months** |

> The TCK alone is chronically underestimated. Each failure on a CDI spec corner case
> can represent a full day of debugging. Reaching 100% (not 95%) is disproportionately expensive.

### Team of 2 seniors

About **8–10 months** — coordination, code reviews, and the absence of perfect parallelism
limit the linear gain.

---

## Acceleration factor

```
~15x
```

1 month with AI ≈ 15–18 months solo.

---

## What AI brought

### No blank page
Every component started from a solid base consistent with the existing code.
The cost of the "first draft" dropped from days to minutes.

### CDI spec availability
Questions about CDI 4.1 assignability rules, BCE phases, and the exact behavior
of scopes in the presence of interceptors — answered in seconds rather than hours
of spec reading.

### JDK 25 Class-File API
The API is recent and its documentation sparse. The AI had enough context to
generate correct bytecode (factories, proxies, intercepted subclasses) without
long iterations on class-format errors.

### Cognitive parallelism
While making architectural decisions (choosing a JPMS trade-off, designing a public API),
the code was already being written. The developer's thinking time no longer
blocked code production.

### Pattern recognition on TCK failures
Recurring TCK errors (BCE annotation propagation, validation false positives,
MethodHandle dispatch on cross-package protected methods) were recognized and
fixed without a prolonged debugging spiral.

---

## What AI did not replace

- **Architectural vision** — decisions about JPMS module structure,
  the compile-time / runtime boundary, the BCE extension model.
- **Judgment on elegance** — distinguishing a hack that passes the tests from a solution
  that is correct per the spec.
- **Domain knowledge** — knowing *what* to test, *which* CDI corner cases
  are worth covering, *where* the spec is ambiguous.
- **Product decisions** — scope (CDI Lite vs Full), the choice not to depend on ASM,
  prioritizing native JPMS from the start.

---

## Observations on the working method

### What worked well

- **Specialized agents in parallel** — codebase exploration, TCK analysis, bytecode
  generation, and review ran simultaneously.
- **Strict TDD** — red tests before implementation avoided silent regressions
  in such a dense codebase.
- **Systematic plan mode** — for any task with 3+ steps, writing the plan before
  coding reduced the back-and-forth.
- **Context-mode** — externalizing large outputs (build, TCK, logs) kept the
  context window clean over a long session.

### What cost time despite AI

- **Package migrations** (fr.vidocq → io.vidocq) across ~300 files — mechanical
  but a source of misses (files without extensions, hardcoded references).
- **Idempotence bugs** (SjarEncryptor, double encryption in CI) — detected
  only in the CI environment, invisible in local development.
- **Mermaid parsing errors** in the documentation — the `timeline` syntax
  and HTML entities inside `[]` nodes are frequent traps.

---

## Conclusion

For a project of this technical complexity — a formal spec (CDI 4.1), bytecode
generation, JPMS, an official TCK — AI assistance represented a
**~15x** multiplier on development speed.

The gain is not uniform: it is maximal on mechanical and structural code
(boilerplate, spec implementations, conformance tests), and zero on architectural
decisions and senior engineering judgment.

The most accurate model is not "AI codes in place of the developer" but
**"the senior developer steers at the speed of their thought rather than at the speed
of their typing"**.
