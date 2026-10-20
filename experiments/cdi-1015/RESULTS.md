# External-library relay: results and scope

**Six checks passed on 2026-10-20:** four scenarios in separate JVMs and two
jar integrity checks. No failures, errors or skipped checks.

```sh
mvn -f experiments/cdi-1015/pom.xml -U clean verify
```

## Checks

| Check | Result | Observation |
| --- | --- | --- |
| `external-authorized` | PASS | Runtime proxies, private injection and private/package-private FT fallbacks worked without a component provider or donated lookup. |
| `external-unauthorized` | PASS | The adapter refused the FT grant; the actual fallback resolver failed with `IllegalAccessException`. |
| `external-closed` | PASS | Missing library consent was rejected before adding openings or a read to the container. |
| `external-se` | PASS | Standard SE provider selection followed by the experimental adapter performed openings, read preparation and FT delegation at `initialize()`, then ran the same proxy/injection/fallback checks. |
| API/container jar integrity | PASS | Every original jar entry was preserved byte for byte; only experimental class files were added to runtime copies. |
| External-library integrity | PASS | The library was compiled against standard APIs only and contained just its descriptor and two original bean classes. |

Each scenario also checks that the library jar is unchanged.
The fallback results include injected state and an increasing counter, so a
successful invocation must reach the same contextual instance through the proxy.

## What this experiment establishes

The library declares its reflective opening as `opens ... to jakarta.cdi`.
It contains no Vauban dependency, annotation processor output or service provider.
The application selects the FT recipient at bootstrap.

Vauban's generated interception subclass needs to read the container module.
The experimental adapter prepares that read using a helper defined in the opened
library package, before Vauban generates its proxies. This adapts the container
to the unchanged library jar.

The relay must reach `io.vidocq.heisenberg.core`, where fallback resolution happens.
Being a CDI interceptor does not itself confer this access.

The SE example binds the opener to the initializer already selected by the
application. Its configuration leaves the library closed to the container and FT;
access preparation happens when `initialize()` is called.

## Boundaries

- The public bean classes are exported and explicitly registered. Automatic
  discovery and other library layouts are outside this experiment.
- The API relay and container access preparation are experimental additions.
  Runtime proxy generation and FT interception use Vauban and Heisenberg.
- This is neither a CDI Full extension test nor a CDI TCK result.
- Provider selection uses CDI SE. Multiple implementations and separate module
  layers remain outside the test.
- The SE adapter requires explicit class registration with discovery disabled.
  Its opener binding checks a single constructor-based service provider with an
  advertised type matching the supplied initializer; service factory methods
  are outside this example.
- Module openings persist and do not provide container-instance isolation.

## Reproducing a run

Use Java 25 and Maven 3.9. Dependencies come from Maven Central and the public
snapshots repository declared in the POM. Snapshots may change.
This verification used a clean build, empty user settings and an offline repeat
with the dependency cache previously populated from those public repositories.

`target/dependency-sha256.txt` records resolved jar hashes.
`target/failsafe-reports/` contains the results; `target/logs/` contains the child
JVM commands and output. See the [README](README.md) for the access flow and sources.
