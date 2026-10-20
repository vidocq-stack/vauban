# External-library relay: results and scope

**Six checks passed on 2026-10-20:** four scenarios in separate JVMs and two
jar integrity checks. No failures, errors or skipped checks.

```sh
mvn -f experiments/cdi-1015/pom.xml -U clean verify
```

## Checks

| Check | Result | Observation |
| --- | --- | --- |
| `external-authorized` | PASS | Broad FT grant: runtime proxies, private injection and fallbacks in both packages worked. |
| `external-unauthorized` | PASS | Without the FT grant, fallback validation failed at deployment with `IllegalAccessException`. |
| `external-closed` | PASS | With FT infrastructure omitted, actual CDI access failed without changing the library's openings or reads. |
| `external-scoped` | PASS | First deployment ran the authorized package; adding the second package in another deployment failed its FT validation, while both packages were open to the container. |
| API/container jar integrity | PASS | Only the two bootstrap classes replaced; all other original entries preserved byte for byte. |
| External-library integrity | PASS | Standard API dependencies only; descriptor and three original bean classes. |

Each scenario also checks that the library jar is unchanged.
The fallback results include injected state and an increasing counter, so a
successful invocation must reach the same contextual instance through the proxy.
The standalone `SeExample.main` also completed successfully and printed
`private fallback for duke:external:1`.

## What this experiment establishes

The library declares its reflective opening as `opens ... to jakarta.cdi`.
It contains no Vauban dependency, annotation processor output or service provider.
The application selects the FT recipient at bootstrap.

Vauban's generated interception subclass needs to read the container module.
The adapted SE initializer prepares that read using a helper defined in the opened
library package, before Vauban generates its proxies. This adapts the container
to the unchanged library jar.

The relay must reach `io.vidocq.heisenberg.core`, where fallback resolution happens.
Being a CDI interceptor does not itself confer this access.

The proposed `SeContainerInitializer.newInstance()` binds the opener to the
provider it selects. `allowAccessFor` records broad or package-scoped application
authorization. Configuration leaves the library closed to the container and FT;
access preparation happens when `initialize()` is called. Further grants are
rejected after initialization starts.

The API's opener only relays to the provider and returns the authorized additional
recipients. The provider relays to those recipients. This prototype implements
package openings; it does not treat them as arbitrary read or export permissions.

## Boundaries

- The public bean classes are exported and explicitly registered. Automatic
  discovery and other library layouts are outside this experiment.
- The API relay and container access preparation are experimental changes to
  runtime jar copies, including the two SE bootstrap classes.
  Runtime proxy generation and FT interception use Vauban and Heisenberg.
- This is neither a CDI Full extension test nor a CDI TCK result.
- Provider selection uses CDI SE. Multiple implementations and separate module
  layers remain outside the test.
- The scenarios use explicit class registration with discovery disabled.
  Discovered-only classes do not receive the experimental access preparation.
- Opener authentication uses the selected initializer's module. A provider
  split across modules would need a further design decision.
- Module openings persist and do not provide container-instance isolation.
- Package scope controls which openings are relayed, not a sandbox for code
  allowed to define classes inside the source module.

## Reproducing a run

Use Java 25 and Maven 3.9. Dependencies come from Maven Central and the public
snapshots repository declared in the POM. Snapshots may change.
This verification used a clean build, empty user settings and an offline repeat
with the dependency cache previously populated from those public repositories.

`target/dependency-sha256.txt` records resolved jar hashes.
`target/failsafe-reports/` contains the results; `target/logs/` contains the child
JVM commands and output. See the [README](README.md) for the access flow and sources.
