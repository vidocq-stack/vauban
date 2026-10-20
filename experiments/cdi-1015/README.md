# CDI #1015: SE access authorization

An experiment for [jakartaee/cdi#1015](https://github.com/jakartaee/cdi/issues/1015):
can CDI SE relay an independent library's `opens ... to jakarta.cdi` to the
selected container, then to FT with explicit application authorization?

The experiment adds a proposed `allowAccessFor` method to a copy of the CDI SE
API and integrates it into a copy of Vauban's SE initializer. Released CDI has
no such method.

Short guides: [container relay](CONTAINER.md) · [SE bootstrap](SE.md).

## Run

Use **JDK 25** and **Maven 3.9**. From the Vauban repository root:

```sh
mvn -f experiments/cdi-1015/pom.xml -U clean verify
```

The POM downloads Vauban, Heisenberg and Ravel `0.4.0-SNAPSHOT` from the
[public snapshots repository](https://central.sonatype.com/repository/maven-snapshots/).
Standard APIs and build plugins come from Maven Central. No other tools or
sibling source builds are needed.

There are four scenarios, each launched in a fresh JVM, and two jar integrity
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

    exports experiment.external.other;
    opens experiment.external.other to jakarta.cdi;
}
```

Maven compiles this library against standard API jars only, without annotation
processing. It contains three beans and its module descriptor: no Vauban dependency,
generated classes or `provides` declaration. Its jar remains unchanged at runtime.

## Proposed SE API

[`SeExample.java`](fixtures/external-app/src/main/java/experiment/external/app/SeExample.java)
uses the standard SE bootstrap directly, with a scoped FT authorization:

```java
try (var container = SeContainerInitializer.newInstance()
        .allowAccessFor(ft, FallbackService.class.getModule(),
                FallbackService.class.getPackageName())
        .disableDiscovery()
        .addBeanClasses(beanClasses)
        .initialize()) {
    var service = container.select(FallbackService.class).get();
    System.out.println(service.callWithPrivateFallback("duke"));
}
```

`ft` is the `io.vidocq.heisenberg.core` module, where fallback resolution happens.
`beanClasses` contains the library beans and FT implementation classes, listed
in the example. The application has no Vauban dependency.

Two overloads express the application's policy:

```java
allowAccessFor(Module recipient)
allowAccessFor(Module recipient, Module source, String... packages)
```

The first covers all eligible bean packages processed by this initializer. The
second covers exact package names in one source module; it does not include
subpackages. Grants accumulate and cannot bypass the library's opening to CDI.
Configuration makes no openings; the policy is sealed when initialization starts.

`allowAccessFor` names the authorization, while this prototype implements it
with package openings. These support reflection, lookups and class definition.
They do not automatically grant reads or exports for arbitrary compiled code.
Vauban separately prepares the read needed by its generated interception code.
Other access kinds would need their own defined semantics.

## The access flow

1. The library opens its bean packages to `jakarta.cdi`.
2. `newInstance()` selects the SE provider and binds an opener to that initializer.
3. The application configures any additional recipients with `allowAccessFor`.
4. During `initialize()`, the provider obtains the opener. The CDI API relays
   each eligible registered package to that provider.
5. Vauban prepares the library-to-container read needed by its generated code,
   then relays the package to recipients authorized for it.
6. Vauban generates its normal-scope proxies and interception subclasses.

The API checks the requesting module and its lookup against the selected
provider. Vauban prepares its read by defining a small helper in the opened
library package. Neither step changes the library jar.

All four scenarios use this SE flow:

| Scenario | Expected behavior |
| --- | --- |
| `external-authorized` | Broad FT grant: fallbacks in both packages work. |
| `external-unauthorized` | CDI works, but FT cannot access the private fallback. |
| `external-closed` | No opening to CDI: actual container access fails. |
| `external-scoped` | FT works in the authorized package and fails in the second. |

Fallback results include injected state and a counter in the contextual instance.
The scoped scenario also confirms that the container can access both packages.
It first deploys the authorized beans, then adds the second package in another
deployment. Heisenberg rejects its inaccessible fallback during initialization.
Run it with `-Dscenario=external-scoped`.

## Source layout

| Path | Purpose |
| --- | --- |
| `fixtures/external-beans/` | Independent library and its closed descriptor variant. |
| `fixtures/external-app/` | SE example and scenario assertions. |
| `api-patch/` | Proposed CDI SE API and its bound opener. |
| `core-patch/` | Adapted Vauban SE initializer and generated-code access preparation. |
| `src/test/` | JUnit launcher and jar integrity checks. |

Maven's Ant plugin compiles the module variants. At build time it replaces only
`SeContainerInitializer.class` and `VaubanSeContainerInitializer.class` in runtime
jar copies, and adds the experimental helpers. Integrity checks require every
other original entry, including module descriptors, to remain byte-identical.
The library compiles against the original standard APIs.

Runtime uses the ordinary module path, without access-granting launcher options,
agents or a container-owned module layer.

## Limits

This tests actual Vauban/Heisenberg integration with public, exported beans
registered at bootstrap. It does not cover automatic discovery, CDI Full
extensions, multiple container implementations or every library layout.
Only explicitly registered classes receive the experimental access preparation;
package and archive discovery need integration before this could be generalized.
Provider authentication currently uses the selected initializer's module; more
complex provider layouts need consideration.

Module openings are persistent permissions on `Module` objects, not permissions
scoped to a container instance. Package scoping controls the relay; it is not a
security boundary against a recipient able to define classes in the source module.
Snapshot artifacts can change; each run records
the resolved jar hashes in `target/dependency-sha256.txt`.
