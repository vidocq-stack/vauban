# Container relay

Experimental. The library declares `opens its.package to jakarta.cdi`, with no
container dependency or `provides` declaration.

At startup, the selected container obtains its opener through `packageOpener()`.

```mermaid
sequenceDiagram
    participant C as Container
    participant A as CDI API
    participant L as Library module
    C->>A: openPackage(lookup, source, package)
    Note over A: Check provider identity and library opening
    A->>L: addOpens(package, container)
    A-->>C: Authorized recipients
    opt Application authorized FT for this package
        C->>L: addOpens(package, FT)
    end
```

Vauban also prepares the library-to-container read needed by generated code.
The library jar remains unchanged.

[SE authorization](SE.md) · [Container implementation](core-patch/io/vidocq/vauban/core/container/VaubanSeContainerInitializer.java)
