package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.Dependent;

/**
 * Test dependency injected into {@link ProvidedConstructorBean}'s constructor. The container
 * resolves it; the in-module provider only receives the already-resolved instance.
 */
@Dependent
public class ProvidedDependency {
    public String value() {
        return "dep";
    }
}
