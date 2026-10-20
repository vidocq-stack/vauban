# CDI #1015: an independent bean library

An experiment for [jakartaee/cdi#1015](https://github.com/jakartaee/cdi/issues/1015):
can an external bean library support CDI injection and private Fault Tolerance
fallbacks by opening its package to `jakarta.cdi`, without depending on a container?

## Run

Use **JDK 25** and **Maven 3.9**. From the Vauban repository root:

```sh
mvn -f experiments/cdi-1015/pom.xml -U clean verify
```

The POM downloads Vauban, Heisenberg and Ravel `0.4.0-SNAPSHOT` from the
[public snapshots repository](https://central.sonatype.com/repository/maven-snapshots/).
Standard APIs and build plugins come from Maven Central. No other tools or
sibling source builds are needed.

There are three scenarios, each launched in a fresh JVM, and two jar integrity
checks. Add `-Dscenario=external-authorized` to select one scenario. Reports are in
`target/failsafe-reports/`; commands and output are in `target/logs/`.
See [results and scope](RESULTS.md).

## The library

```java
module experiment.external.beans {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires microprofile.fault.tolerance.api;

    exports experiment.external.beans;
    opens experiment.external.beans to jakarta.cdi;
}
```

Maven compiles this library against standard API jars only, without annotation
processing. It contains two beans and its module descriptor: no Vauban dependency,
generated classes or `provides` declaration. Its jar remains unchanged at runtime.

## The access flow

1. The library opens its bean package to the CDI API.
2. The experimental CDI API relays that opening to the selected container.
3. The container defines a small helper inside the opened package to add the
   library-to-container read needed by runtime-generated interception code.
4. The application explicitly authorizes the container to relay access to
   `io.vidocq.heisenberg.core`, the module that resolves FT fallbacks.
5. Vauban creates the normal-scope proxy and interception subclass at runtime.

The application registers the public bean classes explicitly. The service has
private field injection and private/package-private fallback methods; their
results depend on injected state and a counter in the contextual instance.
The two negative scenarios remove either the application's FT grant or the
library's opening to CDI.

## Source layout

| Path | Purpose |
| --- | --- |
| `fixtures/external-beans/` | Independent library and its closed descriptor variant. |
| `fixtures/external-app/` | Application bootstrap and scenario assertions. |
| `api-patch/` | Experimental CDI access relay. |
| `core-patch/` | Container bootstrap and generated-code access preparation. |
| `src/test/` | JUnit launcher and jar integrity checks. |

Maven's Ant plugin compiles the module variants. The extra API and container
classes are added to jar copies at build time; every original entry, including
the module descriptors, is preserved. Runtime uses the ordinary module path,
without access-granting launcher options, agents or a container-owned module layer.

## Limits

This tests actual Vauban/Heisenberg integration with public, exported beans
registered at bootstrap. It does not cover automatic discovery, CDI Full
extensions, multiple container implementations or every library layout.
The relay and container adapters are experimental; released CDI has no such API.

Module openings are persistent permissions on `Module` objects, not permissions
scoped to a container instance. Snapshot artifacts can change; each run records
the resolved jar hashes in `target/dependency-sha256.txt`.
