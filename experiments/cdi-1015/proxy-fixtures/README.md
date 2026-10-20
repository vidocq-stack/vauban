# Generated subclasses and module reads

This is a focused Java 25 class-file experiment for CDI #1015. It uses the real
CDI API and Vauban provider modules, with experiment-only helper classes added
to copies of their jars at build time. It does not exercise a full Vauban
interceptor chain and does not change its production code generators.

The bean library has a package-private constructor and virtual method. Its
module only opens that package to `jakarta.cdi`; it does not export the package
or require the Vauban module.

The probe runs inside the real Vauban module so the CDI API's experimental
relay can authenticate the selected provider module. This probe concerns class
definition and read edges, and does not start a CDI container.

## Sequence

1. Assert that the bean package is open to CDI, closed to Vauban, and that the
   bean module does not read Vauban.
2. Have the API relay the package opening to Vauban.
3. Add the provider-to-bean read required by `privateLookupIn`, from provider
   code. This is distinct from the bean-to-provider read used below.
4. Obtain a cross-module lookup with `PACKAGE` access and without `MODULE`.
5. Define a helper in the bean package and call its `MethodHandles.lookup()`.
   Assert that this new lookup has full privilege in the bean module.
6. Check that the original cross-module lookup cannot obtain a private lookup
   on a class in the closed sibling package `experiment.proxy.closed`. Check
   that the helper's full lookup can obtain it and invoke a private method,
   without opening that sibling package to CDI or the provider.
7. In the positive case, call the generated helper to add the bean-to-provider
   read using `Module.addReads`.
8. Define a subclass in the bean package. Its constructor and override access
   package-private bean members, and its override calls an exported provider
   method to produce `intercepted:bean`.

The opening alone does not add either read edge. The generated helper executes
as a member of the bean module, which permits it to add that module's read.

## Modes

Each mode must run in a fresh JVM because module openings and read edges persist,
and failed symbolic references may remain failed after a later graph change.

| Mode | Expected result |
| --- | --- |
| `with-reads` | Helper adds the read; generated override returns `intercepted:bean`. |
| `without-reads` | Class definition and package access succeed; invocation fails with `IllegalAccessError` for the missing bean-to-provider read. |
| `interface-before-reads` | A generated subclass implements a provider-owned marker interface. Definition fails before its static initializer can add the read. |

The interface case demonstrates a limit of adding reads from a proxy's own
static initializer: its superclass and superinterfaces must already be
accessible when the JVM defines the class. A separate helper can perform the
operation earlier.

All three modes also demonstrate that one declared opening combined with
regular class definition can yield lookup access to private members throughout
the bean module. This is a statement about reachable technical capability; it
does not propose that CDI should use such access beyond the library's declared
packages.

## Build and execution contract

Compile all Java sources under `beans`, including `module-info.java`,
`PackageBean.java`, and the unopened sibling package's `Secret.java`, as the
standalone module `experiment.proxy.beans`, with the experimental CDI jar
observable on the module path. Package the compiled directory into a jar.

Compile `core-patch/io/vidocq/vauban/core/container/ExperimentalProxyProbe.java`
with the existing experiment build's compile-time
`--patch-module io.vidocq.vauban.core=proxy-fixtures/core-patch` mechanism, against
the experimental CDI jar and the normal core dependencies. Add its generated
classes, including the nested `ProviderMarker`, to the experiment's copied core
jar.

Run each mode with:

```sh
java --module-path "$EXPERIMENT_MODULE_PATH" \
  --add-modules experiment.proxy.beans \
  --module io.vidocq.vauban.core/io.vidocq.vauban.core.container.ExperimentalProxyProbe \
  with-reads
```

Replace `with-reads` with the other two mode names for their runs. The module
path includes the bean jar, the experimental CDI and core jars, and core's
normal dependency jars. `--add-modules` resolves the otherwise unreferenced
bean fixture; it grants no reads or reflective access. No runtime
`--add-reads`, `--add-opens`, `--patch-module`, agent, or custom module layer is
used.

Every successful scenario prints one line starting with `PASS proxy-`; expected
negative scenarios exit successfully only after checking the specific denied
read and confirming that the bean module still cannot read the provider.
