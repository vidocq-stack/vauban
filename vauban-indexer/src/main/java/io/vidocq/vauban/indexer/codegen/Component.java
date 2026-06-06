package io.vidocq.vauban.indexer.codegen;

import java.util.List;

/**
 * Describes a managed class the generated {@code _VaubanComponents} provider can instantiate
 * in-module: either via a no-arg constructor ({@code new X()}) or an injected constructor
 * ({@code new X((T0) args[0], …)}) where every parameter is a nameable reference type.
 *
 * @param fqn            fully-qualified class name of the component
 * @param ctorParamTypes erased, nameable types of the selected constructor's parameters, in
 *                       declared order (empty for a no-arg constructor)
 */
public record Component(String fqn, List<String> ctorParamTypes) {

    public Component {
        ctorParamTypes = List.copyOf(ctorParamTypes);
    }

    /** {@code true} when the provider uses a no-arg constructor ({@code new X()}). */
    public boolean noArg() {
        return ctorParamTypes.isEmpty();
    }
}
