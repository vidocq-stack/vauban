package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.util.List;
import java.util.Set;

/**
 * Complete description of a discovered CDI bean.
 */
public record BeanDescriptor(
        BeanId id,
        DotName beanClass,
        BeanKind kind,
        Set<TypeInfo> types,              // bean types (class + all supertypes + interfaces + Object)
        Set<QualifierInstance> qualifiers,
        ScopeInfo scope,
        boolean isAlternative,
        int priority,                      // @Priority value, 0 if not set
        List<InjectionPointInfo> injectionPoints,
        String name,                       // @Named value, null if not named
        Set<DotName> interceptorBindings   // interceptor bindings on this bean
) {

    public enum BeanKind {
        MANAGED, PRODUCER_METHOD, PRODUCER_FIELD
    }

    public BeanDescriptor {
        types = Set.copyOf(types);
        qualifiers = Set.copyOf(qualifiers);
        injectionPoints = List.copyOf(injectionPoints);
        interceptorBindings = interceptorBindings != null ? Set.copyOf(interceptorBindings) : Set.of();
    }

    /**
     * Backward-compatible constructor without interceptorBindings.
     */
    public BeanDescriptor(BeanId id, DotName beanClass, BeanKind kind,
            Set<TypeInfo> types, Set<QualifierInstance> qualifiers, ScopeInfo scope,
            boolean isAlternative, int priority, List<InjectionPointInfo> injectionPoints,
            String name) {
        this(id, beanClass, kind, types, qualifiers, scope, isAlternative, priority,
                injectionPoints, name, Set.of());
    }
}
