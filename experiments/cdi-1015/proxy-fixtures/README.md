# Generated subclass and read-edge probes

These cases run with the main experiment's `mvn clean verify` command.

`experiment.proxy.beans` opens one package to `jakarta.cdi` and does not require
Vauban core. The experimental API relays that opening to the real core module.
The provider uses its package-access lookup to define a helper in the bean
package. That helper can obtain a full-privilege lookup and add a read edge.

| Scenario | Expected result |
| --- | --- |
| `proxy-with-reads` | The helper adds the bean-to-provider read before the subclass calls provider code. |
| `proxy-without-reads` | Class definition succeeds; calling provider code throws `IllegalAccessError`. |
| `proxy-interface-before-reads` | An unread provider interface prevents class definition before its initializer can add the read. |

Each case also checks access to a private method in a second package that remains
unopened and unexported. The original cross-module lookup is denied; the helper's
full-privilege lookup succeeds.

The subclass overrides a package-private method and calls a public hook in the
provider module. This is a small bytecode experiment, not Vauban's production
proxy emitter or a full CDI interceptor chain.
