# Reference run: 2026-10-20

**12 scenarios passed**, including the cases that must reject access.
Each scenario ran in a separate JVM. The shared-module scenario deliberately
started two containers in one JVM.

## Inputs

- Java: Eclipse Temurin OpenJDK 25, build `25+36-LTS`.
- Maven: `3.9.16`.
- Vauban production baseline: `16a84b813762a3aa7b9e63acc916c238f3fc027e`,
  with the experiment sources in this branch.
- Heisenberg source baseline: `74a98a7515ab84106d958793dc99a9af353250af`.
- CDI API: `jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0`.
- Original CDI jar SHA-256:
  `c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699`.
- Vauban and Heisenberg source artifacts were rebuilt for the experiment.
  Other dependencies, including Ravel, came from the local Maven repository.
  The run used Maven offline mode.

Both source builds used `clean package`. The extended CDI jar preserved all
227 original entries byte for byte and added one helper class; the extended
Vauban core jar preserved all 291 original entries and added three helper class
files. Both original module descriptors and manifests were preserved exactly.

The runner records the exact commands, all resolved/runtime dependency hashes,
source status, source hashes, generated jar hashes and per-scenario results in
`target/manifest.json`. This directory is ignored by Git because it contains
machine-specific build output. This report describes observed behavior; it is
not a claim that unpinned development snapshots are reproducible byte for byte.

## Observations

| Scenario | Result | Observed behavior |
| --- | --- | --- |
| `donation` | PASS | Actual FT interception invoked both fallback methods with a donated lookup and no opening to FT. |
| `authorized` | PASS | Actual FT interception invoked both methods through the opening relay; lookup donation was absent. |
| `unauthorized` | PASS | The adapter refused the grant, followed by the expected FT `IllegalAccessException`. |
| `wrong-recipient` | PASS | Opening to the interceptor module did not grant access to the FT core resolver. |
| `missing-opening` | PASS | The CDI API helper refused the closed library package before changing the module graph. |
| `extension` | PASS | The explicitly authorized extension invoked the private method through reflection. |
| `unauthorized-extension` | PASS | FT succeeded; reflection by the separate extension remained denied. |
| `shared-module` | PASS | The second container could use the first container's opening without receiving a new grant. The opening survived both containers closing. |
| `spoofed-provider` | PASS | The application module could not obtain a provider opener with its own lookup. |
| `proxy-with-reads` | PASS | An in-package helper added the read edge; the generated subclass called a package-private bean method and provider code. |
| `proxy-without-reads` | PASS | Class definition succeeded, but calling provider code failed on the missing read edge. |
| `proxy-interface-before-reads` | PASS | An unread provider interface caused failure before the class initializer could add the read edge. |

All three proxy cases also demonstrated that an in-package generated helper can
obtain a full-privilege lookup and access a private method in a different,
unopened package of the same bean module. The original cross-module lookup could
not do that directly.

## Implications for the discussion

The proposed application-authorized relay works for the tested FT case. The
library's opening names only `jakarta.cdi`; the application selects the actual
FT implementation module. The recipient must be the module performing access,
which need not be the module containing the CDI interceptor.

Reflective extensions can use the same opening mechanism with an explicit
recipient grant. No automatic grant follows from being an interceptor.

However, application authorization cannot be described as container-instance
isolation when containers share the same `Module` objects. `Module.addOpens`
is persistent, and defining arbitrary classes in an opened package gives the
recipient a route to wider access inside that module.

The reverse read edge for generated code is a separate requirement. An
in-package helper can add it before defining or invoking the subclass.
Relying solely on the subclass initializer is insufficient when linking a
container-owned superinterface already requires that read.

## Boundaries of this evidence

- The FT experiment is a real Vauban/Heisenberg integration.
- Its generated library binaries still depend on Vauban API/core; opening
  portability does not establish complete library portability.
- The extension case is a reflection probe, not a CDI Full extension lifecycle
  test.
- The proxy cases are minimal bytecode probes, not a production Vauban proxy
  implementation or a CDI TCK result.
- Provider authentication is an experimental CDI SE design, using the selected
  initializer's module. Multiple CDI implementations and separate module layers
  remain outside this test.
- No existing CDI API or spec text is proposed as final by this branch.

See the [README](README.md) for commands, source layout and remaining design
questions.
