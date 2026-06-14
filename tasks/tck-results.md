# CDI TCK 4.1 Results — Vauban

> Source of truth: the `vauban-tck-runner` module, executed on every build via
> `mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck`.
> This file tracks the historical progression of CDI Lite TCK conformance.

## Current status (2026-06-14)

| Metric | Value |
|--------|-------|
| Profile | CDI Lite (4.1) |
| CDI Lite tests (non-skipped) | 774 |
| **Passed** | **774 (100%)** |
| Failed | 0 |
| Errors | 0 |
| Wall-clock time | ~5 s |

**100% of the official CDI 4.1 Lite TCK passes.** The Full profile is not targeted
(deferred to a possible future `vauban-full` module).

## Progression

CDI Lite conformance was reached in ~8 days of focused work, from the first runner
execution (2026-03-29) to the full Lite pass (2026-04-05).

| Date | Passed | % | Milestone |
|------|--------|------|-----------|
| 2026-03-29 | — | — | CDI TCK runner bootstrapped (Arquillian adapter), first real execution |
| 2026-03-29 | 246 | 20.7% | Injection between beans, BeanManager, TestEnricher *(basis: full suite)* |
| 2026-03-29 | 290 | 24.8% | RequestContext activated under Arquillian |
| 2026-03-29 | 340 | 29.0% | `CDI.current()` provider *(basis: full suite)* |
| 2026-03-29 | 291 | 38.2% | **`cdi-full` group excluded** — tracking rebased on the CDI Lite subset |
| 2026-03-30 | 330 | 43.3% | Class extraction from nested ShrinkWrap JARs |
| 2026-03-30 | 385 | 50.0% | 🎯 Half the Lite profile — interceptors operational |
| 2026-03-30 | 404 | 52.5% | Stable end-of-day: unique proxy names, type resolution |
| 2026-03-31 | ~426 | 55.4% | CDI parameter resolution in test methods |
| 2026-04-01 | ~559 | 72.7% | `@Dependent` CreationalContext + destroy chain, client proxies |
| 2026-04-02 | 617 | 80.0% | BCE, Invokers, assignability, validations |
| 2026-04-03 | ~669 | 87.0% | Interceptors excluded from bean resolution |
| 2026-04-05 | 774 | 100% | ✅ EventMetadata runtime type resolution + `@Dependent` producer lifecycle |

```
2026-03-29  38.2%  ████████████████░░░░░░░░░░░░░░░░░░░░░░░░  (CDI Lite basis)
2026-03-30  52.5%  █████████████████████░░░░░░░░░░░░░░░░░░░
2026-03-31  55.4%  ██████████████████████░░░░░░░░░░░░░░░░░░
2026-04-01  72.7%  █████████████████████████████░░░░░░░░░░░
2026-04-02  80.0%  ████████████████████████████████░░░░░░░░
2026-04-03  87.0%  ███████████████████████████████████░░░░░
2026-04-05 100.0%  ████████████████████████████████████████  (774/774)
```

> **Note on the early percentages.** The 2026-03-29 figures up to 29.0% were computed
> against the full TCK suite (Lite + Full). The `cdi-full` group was excluded on
> 2026-03-29, after which all percentages are computed against the CDI Lite subset
> (~769, later 774 non-skipped tests). This is why the count appears to "drop" from
> 340 to 291 while the percentage rises: the denominator changed.

## Key features delivered to reach 100%

- Runtime interceptors (Class-File API `@AroundInvoke` / `@AroundConstruct`)
- Runtime client proxies (Class-File API, normal-scoped beans)
- `TypeVariable` resolution across the generic hierarchy
- `EventMetadata` injection in observer methods
- `@Dependent` producer lifecycle and `CreationalContext` destroy chain
- Build Compatible Extensions (BCE): `@Discovery`, `MetaAnnotations`, `ScannedClasses`
- `@Repeatable` qualifiers

## Reproduce

```bash
cd vauban
sdk env
mvn install -DskipTests -q && mvn test -pl vauban-tck-runner -Ptck
```

The runner uses Arquillian + TestNG; the Surefire report lands in
`vauban-tck-runner/target/surefire-reports/`.
