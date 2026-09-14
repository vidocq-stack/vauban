# BENCH.md — vauban

Performance measurement history. Convention: see `../CLAUDE.md` (workspace root).

Every published number (README, commit, post) must point to an entry here.

---

## BENCH-20260914-03 — Qualifier resolution on generated metadata (vauban#70, PR 4b to 4d)

- **Date**: 2026-09-14
- **Commit**: 77c5ef3 (branch `pr/ybl/70-index-injection-points`, on top of PR 4c — vauban#83)
- **JVM**: Temurin 25+36-LTS, default flags
- **Hardware**: Apple M5 Max / 18 cores (6 performance, 12 efficiency) / 128 GB RAM
- **OS**: macOS 26.6.2 (arm64)
- **Exact command**: the same as BENCH-20260914-01
  ```bash
  mvn -ntp clean install        # Maven 3.9.16
  java -jar vauban-bench/target/benchmarks.jar -f 3 -wi 5 -i 5 -w 2s -r 3s -prof gc
  ```
- **Results**:
  ```
  Benchmark                                                           Mode  Cnt      Score     Error   Units
  QualifierResolutionBenchmark.dependentCreation                      avgt   15  14014,765 ± 124,690   ns/op
  QualifierResolutionBenchmark.dependentCreation:gc.alloc.rate.norm   avgt   15  34538,841 ±  58,460    B/op
  QualifierResolutionBenchmark.interceptedCall                        avgt   15    690,906 ±  19,595   ns/op
  QualifierResolutionBenchmark.interceptedCall:gc.alloc.rate.norm     avgt   15   3309,340 ±  98,137    B/op
  QualifierResolutionBenchmark.programmaticLookup                     avgt   15   1897,103 ±  44,220   ns/op
  QualifierResolutionBenchmark.programmaticLookup:gc.alloc.rate.norm  avgt   15   7016,018 ±  62,598    B/op
  QualifierResolutionBenchmark.qualifiedEvent                         avgt   15   4699,739 ±  50,200   ns/op
  QualifierResolutionBenchmark.qualifiedEvent:gc.alloc.rate.norm      avgt   15   7776,043 ±  40,245    B/op
  ```
- **Comparison with the previous run** (BENCH-20260914-02, matching on keys):

  | Benchmark | after 3b | after 4d | time | allocation |
  |---|---|---|---|---|
  | `dependentCreation` | 17796 ns, 38504 B | 14015 ns, 34539 B | **1.27× faster** | **−10 %** |
  | `programmaticLookup` | 2076 ns, 7960 B | 1897 ns, 7016 B | **−8.6 %** | **−12 %** |
  | `qualifiedEvent` | 4686 ns, 8381 B | 4700 ns, 7776 B | flat (+0.3 %) | −7.2 % |
  | `interceptedCall` | 708 ns, 3360 B | 691 ns, 3309 B | −2.4 % | −1.5 % |

- **Comparison with the baseline** (BENCH-20260914-01, reflective matching — the whole of #70 so far):

  | Benchmark | before #70 | after 4d | time | allocation |
  |---|---|---|---|---|
  | `dependentCreation` | 41561 ns, 65245 B | 14015 ns, 34539 B | **2.97× faster** | **−47 %** |
  | `programmaticLookup` | 6590 ns, 11691 B | 1897 ns, 7016 B | **3.47× faster** | **−40 %** |
  | `qualifiedEvent` | 4673 ns, 6941 B | 4700 ns, 7776 B | flat (+0.6 %) | **+12 %** |
  | `interceptedCall` | 651 ns, 3251 B | 691 ns, 3309 B | +6.1 % | +1.8 % |

- **Notes**:
  - The machine was not idle again: load average about 5 when the run started and 3.5 when it ended,
    the same conditions as BENCH-20260914-02, so the two compare like with like. The error bars are
    0.9 % to 2.8 % of each score; `interceptedCall` and `qualifiedEvent` move less than that and are
    not distinguishable from noise on time.
  - `dependentCreation` and `programmaticLookup` gain again, and this time on both axes: what PR 3b
    left was the qualifiers of an injection point being read off the field or the parameter on every
    creation. PR 4d takes them from the descriptor instead, and PR 4b/4c let the module hand out its
    own literal rather than a `reflect.Proxy` — one fewer object, and no proxy handler, per qualifier.
  - **BENCH-20260914-02 predicted that PR 4's generated readers would remove the conversion cost of
    `qualifiedEvent`. They did not.** Allocation fell 7.2 %, from 8381 to 7776 B/op, but the time did
    not move and allocation is still 12 % above the pre-#70 baseline. Firing on a fresh `Event` per
    iteration converts its qualifiers every time; the generated reader makes that conversion cheaper,
    not free. An `Event` injected once and fired many times pays it once. Whatever is left is in the
    dispatch itself, not in the annotations, and no stage of #70 addresses it.
  - `interceptedCall` is within noise of both earlier runs. Interceptor binding comparison is
    untouched so far — PR 5 moves it to keys, and that is where its number should change.
  - Same benchmark as the two earlier runs, unchanged.

---

## BENCH-20260914-02 — Qualifier resolution on normalized keys (vauban#70, PR 3a and PR 3b)

- **Date**: 2026-09-14
- **Commit**: a0110d4 (branch `pr/ybl/70-runtime-keys`, on top of PR 3a — vauban#77)
- **JVM**: Temurin 25+36-LTS, default flags
- **Hardware**: Apple M5 Max / 18 cores (6 performance, 12 efficiency) / 128 GB RAM
- **OS**: macOS 26.6.2 (arm64)
- **Exact command**: the same as BENCH-20260914-01
  ```bash
  mvn -ntp clean install        # Maven 3.9.16
  java -jar vauban-bench/target/benchmarks.jar -f 3 -wi 5 -i 5 -w 2s -r 3s -prof gc
  ```
- **Results**:
  ```
  Benchmark                                                           Mode  Cnt      Score     Error   Units
  QualifierResolutionBenchmark.dependentCreation                      avgt   15  17795,844 ± 512,285   ns/op
  QualifierResolutionBenchmark.dependentCreation:gc.alloc.rate.norm   avgt   15  38504,230 ± 162,649    B/op
  QualifierResolutionBenchmark.interceptedCall                        avgt   15    707,985 ±  50,964   ns/op
  QualifierResolutionBenchmark.interceptedCall:gc.alloc.rate.norm     avgt   15   3360,006 ±  57,825    B/op
  QualifierResolutionBenchmark.programmaticLookup                     avgt   15   2075,685 ±  52,292   ns/op
  QualifierResolutionBenchmark.programmaticLookup:gc.alloc.rate.norm  avgt   15   7960,019 ± 100,157    B/op
  QualifierResolutionBenchmark.qualifiedEvent                         avgt   15   4686,259 ±  92,252   ns/op
  QualifierResolutionBenchmark.qualifiedEvent:gc.alloc.rate.norm      avgt   15   8381,377 ±  20,865    B/op
  ```
- **Comparison with the previous run** (BENCH-20260914-01, the reflective matching):

  | Benchmark | before | after | time | allocation |
  |---|---|---|---|---|
  | `dependentCreation` | 41561 ns, 65245 B | 17796 ns, 38504 B | **2.3× faster** | **−41 %** |
  | `programmaticLookup` | 6590 ns, 11691 B | 2076 ns, 7960 B | **3.2× faster** | **−32 %** |
  | `qualifiedEvent` | 4673 ns, 6941 B | 4686 ns, 8381 B | flat (+0.3 %) | +21 % |
  | `interceptedCall` | 651 ns, 3251 B | 708 ns, 3360 B | +8.8 % | +3.4 % |

- **Notes**:
  - The machine was not idle: its load average was about 5 when the run started, so a few percent either way is noise. The two large gains are far outside it; the two small changes are not distinguishable from it on time.
  - `dependentCreation` and `programmaticLookup` are where matching dominated: resolution used to rebuild every candidate bean's qualifiers as proxies and compare them member by member with `Method.invoke`, on every lookup. A bean's key is now computed once, and the injection point's comes from the index.
  - `qualifiedEvent` is `orders.select(literal).fire(event)`: a new `Event` per iteration, so its qualifiers are converted on every measurement — one reflective read of the literal, one key, one set — where the old path passed the annotations straight through and reflected per observer instead. An `Event` injected once and fired many times converts once. Flat in time, 1.4 KB more per operation; PR 4's generated readers remove that read.
  - `interceptedCall` measures interceptor resolution, which neither PR touches — PR 5 moves it to keys. The 57 ns are within this run's noise; the 110 B/op are not explained and belong to that stage.
  - Same benchmark as the baseline, unchanged, so the two runs compare like with like — including the enum member on a field it leaves out, although PR 3a fixed that path (BUG-20260914-02).

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
