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

The build prepares the fixtures, then runs each of the **15 scenarios in a fresh
JVM**, plus two jar integrity checks. Reports are in `target/failsafe-reports/`;
individual scenario logs are in `target/logs/`. See [recorded results](RESULTS.md).

To run just one scenario, add `-Dscenario=external-authorized` (or another name from the
results table).

Snapshots can change. The dependency versions are properties in the POM, and
`target/dependency-sha256.txt` records hashes of the jars used for that run.

## An external library

The library names only standard API modules:

```java
module experiment.external.beans {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires microprofile.fault.tolerance.api;

    exports experiment.external.beans;
    opens experiment.external.beans to jakarta.cdi;
}
```

It has no Vauban dependency, annotation processor or `provides` declaration.
Maven compiles it using only released standard API jars and packages it before
compiling the application. The library jar remains unchanged during execution.

In `external-authorized`, the application registers the bean classes and
authorizes access for the FT resolver. The experimental API relays the opening
to Vauban. The container adapter then adds the read needed by its generated
code, using a small helper defined inside the opened library package.
Vauban creates the normal-scope proxy and interception subclass at runtime.
Private injection and private/package-private FT fallbacks succeed.

`external-unauthorized` checks refusal without the application's FT grant.
`external-closed` checks refusal without the library's opening to CDI.

This requires the experimental API and container adapters in this directory.
Released CDI does not yet provide this relay.

## Generated-provider comparison

The same bean has a private fallback method and a package-private fallback method.
In the separate `fixtures/beans` comparison, its module descriptor selects one
of three configurations:

- **Donation:** the generated provider supplies a lookup; no package is opened.
- **Relay:** the library opens its bean package to `jakarta.cdi`. The API relays
  that opening to Vauban, which delegates to recipients explicitly authorized
  by application bootstrap. Lookup donation is disabled for this variant.
- **Closed:** neither donation nor opening is available.

These variants use Vauban-generated classes and a component provider.
They compare access mechanisms; the external-library fixture above demonstrates
the case without this implementation dependency.

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
| `core-patch/` | Bootstrap and runtime read adapters added to a copy of the real Vauban core jar. |
| `fixtures/external-beans/` | Independent library using only standard APIs. |
| `fixtures/external-app/` | Runtime proxy, injection and FT checks for the external library. |
| `fixtures/beans/` | Generated-provider comparison with three module descriptors. |
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

- The external-library case runs actual Vauban/Heisenberg interception, with
  public bean classes in an exported package, explicitly registered at bootstrap.
  It does not test automatic discovery or every possible library layout.
- Runtime-generated classes depend on Vauban. The container prepares their access;
  the published library jar contains neither those classes nor their dependencies.
- The reflection and [proxy cases](proxy-fixtures/README.md) are focused probes.
  They do not establish CDI Full lifecycle or TCK coverage.
- Openings affect shared `Module` objects and survive container shutdown.
  They cannot isolate containers sharing those same objects.
- A generated helper can obtain a full-privilege lookup and reach other,
  unopened packages of its own module. The proxy fixture verifies this.
- The API helper uses CDI SE provider selection. Other bootstrap environments
  and multiple CDI implementations need further design work.
