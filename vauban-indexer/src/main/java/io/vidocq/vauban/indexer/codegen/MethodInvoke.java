package io.vidocq.vauban.indexer.codegen;

import java.util.List;

/**
 * Describes a method (producer, observer, disposer, lifecycle callback, initializer) that the
 * generated {@code _VaubanComponents} provider can invoke in-module without reflection.
 *
 * <p>The method identity key ({@link #methodId()}) is {@code methodName(paramErasure0,…)},
 * using binary class names (dots for top-level, {@code $} for nested, {@code []} per array
 * dimension), exactly matching the format produced by
 * {@code io.vidocq.vauban.core.container.VaubanLookup#methodId(Method)}.
 *
 * @param declaringClassFqn fully-qualified name of the class declaring the method
 * @param methodName        simple name of the method
 * @param paramErasures     erased parameter type names in declared order (empty for no-arg)
 * @param isStatic          {@code true} for a static method (no target cast needed)
 * @param isVoid            {@code true} when the return type is {@code void}
 * @param returnErasure     erased return type FQN, or {@code null} when {@code isVoid} is true
 */
public record MethodInvoke(
        String declaringClassFqn,
        String methodName,
        List<String> paramErasures,
        boolean isStatic,
        boolean isVoid,
        String returnErasure) {

    public MethodInvoke {
        paramErasures = List.copyOf(paramErasures);
    }

    /** The runtime-compatible method identity key: {@code name(erasure0,erasure1,…)}. */
    public String methodId() {
        return methodName + "(" + String.join(",", paramErasures) + ")";
    }
}
