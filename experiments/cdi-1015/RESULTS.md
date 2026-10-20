# Reference run: 2026-10-20

**17 Maven integration tests passed:** 15 scenarios, including access
refusals, plus two jar integrity checks. Each scenario ran in a separate JVM.
The shared-module scenario deliberately started two containers in one JVM.

## Inputs

- Java: Eclipse Temurin OpenJDK 25, build `25+36-LTS`.
- Maven: `3.9.16`.
- Vauban core/processor: published snapshot `0.4.0-20261019.193947-85`.
- Heisenberg core/CDI: published snapshot `0.4.0-20261012.121738-14`.
- Ravel core: published snapshot `0.4.0-20261010.163329-12`.
- CDI API: `jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0`.
- External-library compilation: only standard API jars, including
  `org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1`.
- Original CDI jar SHA-256:
  `c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699`.

Dependency resolution was checked using an initially empty Maven repository
and an empty user settings file. All inputs came from public Maven Central and
the public snapshots repository; no sibling source builds or locally installed
Vidocq artifacts were used.

```sh
mvn -f experiments/cdi-1015/pom.xml -U clean verify
```

The integrity checks verify that every original CDI/core jar entry, including
module descriptors and manifests, is preserved byte for byte. Only experiment
class files are added to the runtime copies. The external library has only its
module descriptor and two original bean classes, no generated provider, and no
implementation dependencies. Its jar hash is unchanged after each scenario.

Maven writes standard reports to `target/failsafe-reports/`. Child JVM commands
and output are in `target/logs/`; dependency and library jar hashes are recorded in
`target/dependency-sha256.txt`. Snapshots can change, so later runs may resolve
newer builds.

## Observations

| Scenario | Result | Observed behavior |
| --- | --- | --- |
| `external-authorized` | PASS | A standard-API-only library used runtime-generated Vauban proxies, private injection and both FT fallbacks, with no provider or donated lookup. |
| `external-unauthorized` | PASS | The adapter refused the FT grant; the actual resolver failed with the expected `IllegalAccessException`. |
| `external-closed` | PASS | Missing library consent was rejected before adding openings or a read to the container. |
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

The external-library case first failed with `IllegalAccessError`: its generated
`FallbackService$$Intercepted` could not access Vauban's `InterceptorManager`
because the library module did not read Vauban core. Adding the runtime read
inside the experimental container adapter made the same library jar work.
No implementation dependency or service declaration was added to the library.

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
- The external jar is compiled against standard APIs only. Its public bean classes
  are exported and explicitly registered at bootstrap; automatic discovery and
  other library layouts remain untested.
- The original donation comparison still uses implementation-specific generated
  providers. The external-library cases use Vauban's actual runtime generation.
- The extension case is a reflection probe, not a CDI Full extension lifecycle
  test.
- The three `proxy-*` cases are minimal bytecode probes. The `external-*` cases
  exercise production Vauban emitters with experimental access preparation.
  Neither is a CDI TCK result.
- Provider authentication is an experimental CDI SE design, using the selected
  initializer's module. Multiple CDI implementations and separate module layers
  remain outside this test.
- No existing CDI API or spec text is proposed as final by this branch.

See the [README](README.md) for commands, source layout and remaining design
questions.
