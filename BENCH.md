# BENCH.md — vauban

Performance measurement history. Convention: see `../CLAUDE.md` (workspace root).

Every published number (README, commit, post) must point to an entry here.

---

## BENCH-20260914-01 — Qualifier resolution before vauban#70 (reflective matching)

- **Date**: 2026-09-14
- **Commit**: ceac964 (`main`), measured from branch `pr/ybl/70-safety-net`, which adds only the benchmark and tests
- **JVM**: Temurin 25+36-LTS, default flags
- **Hardware**: Apple M5 Max / 18 cores (6 performance, 12 efficiency) / 128 GB RAM
- **OS**: macOS 26.6.2 (arm64)
- **Exact command**:
  ```bash
  mvn -ntp clean install        # Maven 3.9.16
  java -jar vauban-bench/target/benchmarks.jar -f 3 -wi 5 -i 5 -w 2s -r 3s -prof gc
  ```
- **Results**:
  ```
  Benchmark                                                           Mode  Cnt      Score     Error   Units
  QualifierResolutionBenchmark.dependentCreation                      avgt   15  41561,524 ± 437,998   ns/op
  QualifierResolutionBenchmark.dependentCreation:gc.alloc.rate.norm   avgt   15  65245,527 ± 116,849    B/op
  QualifierResolutionBenchmark.interceptedCall                        avgt   15    650,594 ±  16,936   ns/op
  QualifierResolutionBenchmark.interceptedCall:gc.alloc.rate.norm     avgt   15   3250,670 ± 111,120    B/op
  QualifierResolutionBenchmark.programmaticLookup                     avgt   15   6590,274 ±  70,278   ns/op
  QualifierResolutionBenchmark.programmaticLookup:gc.alloc.rate.norm  avgt   15  11690,697 ±  80,598    B/op
  QualifierResolutionBenchmark.qualifiedEvent                         avgt   15   4672,965 ±  58,453   ns/op
  QualifierResolutionBenchmark.qualifiedEvent:gc.alloc.rate.norm      avgt   15   6941,355 ±  86,235    B/op
  ```
- **Comparison with the previous run**: first run.
- **Notes**:
  - The benchmark beans are compiled with the Vauban annotation processor: instantiation, field assignment and client proxies go through the generated `_VaubanComponents`. Qualifier matching is the reflective path vauban#70 replaces.
  - `dependentCreation` creates, then destroys, a `@Dependent` bean with three qualified fields and two qualified constructor parameters. `programmaticLookup` is `Instance.select(@Channel, @Region).get()`. `qualifiedEvent` fires an event with a member qualifier to three observers, two of which match. `interceptedCall` calls an `@ApplicationScoped` bean bound to its interceptor by a member binding.
  - Only paths that resolve correctly today are measured: an enum member on a field is left out (BUG-20260914-02). `QualifierResolutionBenchmark#boot` checks every path before the first iteration and fails the trial otherwise.
  - The literals are written by hand. `AnnotationLiteral` reflects in its own `equals` and `hashCode`, which would add the literal's cost to the container's.
  - The 3.2 KB allocated per intercepted call is consistent with the generated subclass looking its method up and resolving the interceptor chain on every call (`InterceptedSourceRenderer`); not profiled further.
  - Compile-time validation is disabled in `vauban-bench` (BUG-20260914-13).
