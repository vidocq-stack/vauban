# CDI #1015: controlled package opening experiments

This directory implements the experiments discussed in
[jakartaee/cdi#1015](https://github.com/jakartaee/cdi/issues/1015):

1. Invoke real MicroProfile Fault Tolerance private and package-private fallback
   methods, comparing Vauban's lookup donation with an explicitly authorized
   opening relay.
2. Delegate reflective access to a separate extension module.
3. Define a subclass in a bean's package and examine the reverse read edge needed
   when that generated code references container types.

Everything here is experimental. Production sources and published API artifacts
are unchanged. The runner builds local copies of the genuine CDI 4.1 and Vauban
core jars with additional experiment classes.

See [recorded results](RESULTS.md) for the reference run and its limitations.

## Run

Requirements: Java 25, Maven 3.9, Python 3, and the Vidocq `0.4.0-SNAPSHOT`
dependencies used by this Vauban checkout, available in your Maven repository.
These snapshots are development inputs, not artifacts guaranteed to remain
available from Maven Central.

Use the Heisenberg source revision that contains the original donation experiment:
`74a98a7515ab84106d958793dc99a9af353250af`.
Its source repository is [Vidocq/heisenberg](https://codefloe.com/Vidocq/heisenberg).
The runner can rebuild that checkout and replace the resolved Heisenberg jars.
Keep that checkout clean so its recorded revision identifies the sources used.

From the Vauban repository root:

```sh
python3 experiments/cdi-1015/run.py \
  --java-home /path/to/jdk-25 \
  --maven /path/to/mvn \
  --heisenberg-source /path/to/heisenberg-checkout
```

Add `--offline` when the Maven repository is already populated. Use `--help` for
single-scenario runs and the Maven repository option. The runner rebuilds this
checkout's Vauban artifacts, compiles the fixtures, and starts a fresh JVM for
each scenario. Generated artifacts, dependency hashes, commands and logs are
written under `target/` in this directory.

The runtime commands use an ordinary module path. They use no runtime
`--add-opens`, `--add-reads`, `--patch-module`, agent or Vauban-owned application
module layer. Dynamic agent attachment is disabled. The proxy fixture uses
`--add-modules` solely to resolve its otherwise unreferenced bean module.

`javac --patch-module` is used **during compilation only**, to compile the
experiment helpers as members of the actual CDI and Vauban modules. Their
classes are then added to copies of the real jars. The original jars and module
descriptors are retained unchanged; nothing is installed over the official CDI
artifact.

## Access flow under test

```text
bean library                         application bootstrap
  opens bean.package to jakarta.cdi    selects exact recipient Module objects
                |                                  |
                v                                  v
jakarta.cdi: ExperimentalPackageOpener
  selects SeContainerInitializer
  checks provider lookup and immediate caller module
  relays bean.package to that provider
                |
                v
Vauban: ExperimentalBootstrap
  uses the exact selected initializer to start the container
  relays bean.package only to application-authorized recipients
                |
                +--> io.vidocq.heisenberg.core (fallback resolver)
                +--> experiment.extension (optional reflection probe)
```

The FT recipient is **`io.vidocq.heisenberg.core`**. Opening only to
`io.vidocq.heisenberg.cdi.vauban`, which contains the interceptor, is insufficient.
The application chooses recipients before container initialization because
Heisenberg can validate fallback access during bootstrap.

Authorization is modeled as an explicit set of actual `Module` objects passed
to the adapter. This is an experiment policy, not an existing CDI configuration
property. No access is granted merely because a component is an interceptor.
Service discovery selects a provider; it does not authorize extension access.

## Controls

| Scenario | Expected observation |
| --- | --- |
| `donation` | Both fallback methods work with a donated lookup and no package opening. |
| `authorized` | Both methods work with the API-to-provider-to-FT relay; no donated lookup exists. |
| `unauthorized` | The adapter refuses the grant; actual FT access fails. |
| `wrong-recipient` | Granting only the interceptor module leaves the fallback resolver denied. |
| `missing-opening` | The API refuses a library package that was not opened to it. |
| `extension` | A separately authorized module can use `Method.invoke` on a private method. |
| `unauthorized-extension` | FT works while the separate reflection probe remains denied. |
| `shared-module` | Two containers using the same `Module` objects share the first opening, even without a grant in the second configuration; closing them does not revoke it. |
| `spoofed-provider` | An application lookup cannot impersonate the selected provider. |
| `proxy-with-reads` | An in-package helper adds the bean-to-provider read; a generated package-private override calls provider code. |
| `proxy-without-reads` | Defining the class succeeds, but invoking provider code fails on the missing read. |
| `proxy-interface-before-reads` | An unread provider interface prevents class definition before a static initializer can repair the read. |

The bean sources are identical across the donation, relay and closed variants.
Only their module descriptors differ. In the relay variants, `RelayComponents`
delegates generated construction, injection and proxy support but deliberately
does **not** delegate `grantModuleLookup`. Registering the generated donor
alongside that wrapper would invalidate the comparison.

Successful relay scenarios assert that no lookup donation is available. Negative
scenarios check the relevant access exception, not just an arbitrary bootstrap
failure. Each independent case uses a fresh JVM because opening a package cannot
be undone.

## What these experiments establish — and their limits

- The FT cases use actual Vauban bootstrap, actual Heisenberg interception and
  actual private/package-private fallback resolution. The container initializer
  authenticated by the API is the one used to run those cases.
- The reflection probe exercises the Java access mechanism. It does not test
  the lifecycle of CDI Full portable extensions; Vauban is a CDI Lite container.
- The proxy cases are focused bytecode experiments using the real API/provider
  modules. They do not run Vauban's production proxy emitter or a complete CDI
  interceptor chain. See [proxy fixture details](proxy-fixtures/README.md).
- The FT bean module still requires Vauban API and core because its generated
  factories and interception subclass reference Vauban types. The experiment
  establishes an implementation-independent **opening target** (`jakarta.cdi`);
  it does not establish a fully implementation-independent generated library.
- A cross-module lookup loses `MODULE` access but retains `PACKAGE` access.
  Defining a helper class in the bean package allows that helper to obtain a
  full-privilege lookup in the bean module. Package declarations therefore
  cannot be treated as an effective containment boundary for a recipient
  permitted to define arbitrary classes. The proxy fixture verifies this by
  invoking a private method in a second package that remains unopened and
  unexported.
- An opening and a read edge are separate. Heisenberg already adds its own
  read edge to the bean module. Generated code calling container types may
  additionally need a read in the opposite direction, and the referenced
  container package must be exported.
- Grants affect `Module` objects, not container instances. They persist after
  container shutdown. This adapter is not a sandbox against arbitrary code
  allowed to bootstrap another container or execute inside the provider module.
- Provider selection uses the CDI SE mechanism and its one-provider rule. This
  does not settle provider authentication in an application server, selection
  among several CDI implementations, or isolation across independently created
  module layers.

## Questions remaining for the specification

The experiments can inform the choice of an agreed opening target, provider
authentication and explicit delegation policy. They do not by themselves define
the final API, a deployment format for extension grants, container-instance
isolation, or access to a third-party module that never consented to an opening.

They also do not establish that every proxy strategy needs class definition in
the bean module. The proxy fixture deliberately exercises the package-private
subclass case raised in the discussion.

Relevant discussion:

- [Proposed experiments](https://github.com/jakartaee/cdi/issues/1015#issuecomment-6180633946)
- [Bootstrap, FT, reflection and read-edge questions](https://github.com/jakartaee/cdi/issues/1015#issuecomment-6193128179)
- [Explicit application authorization proposal](https://github.com/jakartaee/cdi/issues/1015#issuecomment-6268158721)
