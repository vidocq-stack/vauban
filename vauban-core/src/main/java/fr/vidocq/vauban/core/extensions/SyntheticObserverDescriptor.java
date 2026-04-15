package fr.vidocq.vauban.core.extensions;

import java.util.List;
import java.util.Map;

/**
 * Serializable descriptor for a synthetic observer created by a {@code @Synthesis} BCE phase.
 */
public record SyntheticObserverDescriptor(
        String eventTypeName,
        String observerClassName,
        List<String> qualifiers,
        Map<String, String> params,
        int priority,
        boolean async
) {
    public SyntheticObserverDescriptor {
        qualifiers = qualifiers != null ? List.copyOf(qualifiers) : List.of();
        params = params != null ? Map.copyOf(params) : Map.of();
    }
}
