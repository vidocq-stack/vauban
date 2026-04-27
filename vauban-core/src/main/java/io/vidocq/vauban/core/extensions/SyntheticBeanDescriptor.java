package io.vidocq.vauban.core.extensions;

import java.util.List;
import java.util.Map;

/**
 * Serializable descriptor for a synthetic bean created by a {@code @Synthesis} BCE phase.
 * Used to persist synthetic bean metadata at compile time and reload it at runtime,
 * avoiding re-execution of the BCE.
 */
public record SyntheticBeanDescriptor(
        String beanClassName,
        String creatorClassName,
        String disposerClassName,
        String scopeAnnotation,
        List<String> types,
        List<String> qualifiers,
        Map<String, String> params,
        boolean alternative,
        int priority,
        String name
) {
    public SyntheticBeanDescriptor {
        types = types != null ? List.copyOf(types) : List.of();
        qualifiers = qualifiers != null ? List.copyOf(qualifiers) : List.of();
        params = params != null ? Map.copyOf(params) : Map.of();
    }
}
