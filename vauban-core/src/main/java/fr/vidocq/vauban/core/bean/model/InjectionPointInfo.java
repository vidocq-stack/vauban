package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.util.Set;

/**
 * Describes a single injection point (field, constructor parameter, or method parameter).
 */
public record InjectionPointInfo(
        TypeInfo requiredType,
        Set<QualifierInstance> qualifiers,
        InjectionKind kind,
        String description // for error messages: "field MyService.repo" or "parameter 0 of MyService()"
) {

    public enum InjectionKind {
        FIELD, CONSTRUCTOR_PARAMETER, METHOD_PARAMETER
    }

    public InjectionPointInfo {
        qualifiers = Set.copyOf(qualifiers);
    }
}
