# SE bootstrap

Proposed API; not available in released CDI. The library opens its packages to
`jakarta.cdi`.

```java
try (var container = SeContainerInitializer.newInstance()
        .allowAccessFor(ft, FallbackService.class.getModule(),
                FallbackService.class.getPackageName())
        .disableDiscovery()
        .addBeanClasses(beanClasses)
        .initialize()) {
    container.select(FallbackService.class).get()
            .callWithPrivateFallback("duke");
}
```

`ft` is the FT implementation module. `beanClasses` includes library beans and
FT infrastructure.

- `newInstance()` selects the provider and binds its opener.
- `allowAccessFor` records permission for the named packages; `allowAccessFor(ft)`
  covers all eligible packages processed by the container.
- `initialize()` seals permissions and performs the [container relay](CONTAINER.md).

This example uses explicit bean registration. Openings persist after shutdown;
they do not automatically grant reads or exports.

[Runnable example](fixtures/external-app/src/main/java/experiment/external/app/SeExample.java)
