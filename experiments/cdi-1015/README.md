# CDI #1015: package opening experiments

Experiments for [jakartaee/cdi#1015](https://github.com/jakartaee/cdi/issues/1015):
private Fault Tolerance fallbacks, reflective extension access and generated
subclasses on the Java module path.

## Run

Use **JDK 25** and **Maven 3.9**. From the Vauban repository root:

```sh
mvn -f experiments/cdi-1015/pom.xml -U clean verify
```

Maven downloads Vauban, Heisenberg and Ravel `0.4.0-SNAPSHOT` from the
[public snapshots repository](https://central.sonatype.com/repository/maven-snapshots/).
The POM declares that repository. Maven also downloads CDI 4.1 and build plugins
from Maven Central. A Java installation and Maven are the only required tools.

The build prepares the fixtures, then runs each of the **12 scenarios in a fresh
JVM**, plus a jar integrity check. Reports are in `target/failsafe-reports/`;
individual scenario logs are in `target/logs/`. See [recorded results](RESULTS.md).

To run just one scenario, add `-Dscenario=authorized` (or another name from the
results table).

Snapshots can change. The dependency versions are properties in the POM, and
`target/dependency-sha256.txt` records hashes of the jars used for that run.

## What is being compared

The same bean has a private fallback method and a package-private fallback method.
Its module descriptor selects one of three configurations:

- **Donation:** the generated provider supplies a lookup; no package is opened.
- **Relay:** the library opens its bean package to `jakarta.cdi`. The API relays
  that opening to Vauban, which delegates to recipients explicitly authorized
  by application bootstrap. Lookup donation is disabled for this variant.
- **Closed:** neither donation nor opening is available.

The tests cover successful access, missing authorization, a wrong recipient,
a missing library opening, a separate reflection grant, provider identity,
shared openings between containers, and read edges for generated subclasses.

For Heisenberg, the grant must reach `io.vidocq.heisenberg.core`, where fallback
resolution happens. Opening only to the module containing the interceptor is
insufficient.

## Source layout

| Path | Purpose |
| --- | --- |
| `api-patch/` | Experimental relay added to a copy of the real CDI API jar. |
| `core-patch/` | Bootstrap adapter added to a copy of the real Vauban core jar. |
| `fixtures/beans/` | One bean and three module descriptors. |
| `fixtures/app/` | FT and reflection scenario assertions. |
| `fixtures/extension/` | Reflection performed from a separate module. |
| `proxy-fixtures/` | Package-private subclass and read-edge probes. |
| `src/test/` | JUnit launcher and jar integrity checks. |

The POM resolves dependencies and uses Maven's Ant plugin to compile these
small module variants. Ant is downloaded as a plugin dependency. JUnit only
launches the prepared scenarios and checks their results.

The extra API/provider classes are compiled into jar copies **at build time**.
Every original jar entry, including `module-info.class`, is checked for exact
preservation. Runtime uses an ordinary module path, with dynamic attachment
disabled and no access-granting launcher options.

## Limits

- The FT case runs actual Vauban/Heisenberg interception. Its generated classes
  still depend on Vauban; this tests a portable opening target, not portable
  generated binaries.
- The reflection and [proxy cases](proxy-fixtures/README.md) are focused probes.
  They do not establish CDI Full lifecycle or TCK coverage.
- Openings affect shared `Module` objects and survive container shutdown.
  They cannot isolate containers sharing those same objects.
- A generated helper can obtain a full-privilege lookup and reach other,
  unopened packages of its own module. The proxy fixture verifies this.
- The API helper uses CDI SE provider selection. Other bootstrap environments
  and multiple CDI implementations need further design work.
