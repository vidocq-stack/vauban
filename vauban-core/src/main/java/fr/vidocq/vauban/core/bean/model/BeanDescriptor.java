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
        Set<DotName> interceptorBindings,   // interceptor bindings on this bean class
        Set<DotName> constructorBindings,  // interceptor bindings on the bean constructor
        List<java.lang.annotation.Annotation> interceptorBindingAnnotations // full annotations for member-value comparison
) {

    public enum BeanKind {
        MANAGED, PRODUCER_METHOD, PRODUCER_FIELD
    }

    public BeanDescriptor {
        types = Set.copyOf(types);
        qualifiers = Set.copyOf(qualifiers);
        injectionPoints = List.copyOf(injectionPoints);
        interceptorBindings = interceptorBindings != null ? Set.copyOf(interceptorBindings) : Set.of();
        constructorBindings = constructorBindings != null ? Set.copyOf(constructorBindings) : Set.of();
        interceptorBindingAnnotations = interceptorBindingAnnotations != null ? List.copyOf(interceptorBindingAnnotations) : List.of();
    }

    /**
     * Backward-compatible constructor without interceptorBindings.
     */
    public BeanDescriptor(BeanId id, DotName beanClass, BeanKind kind,
            Set<TypeInfo> types, Set<QualifierInstance> qualifiers, ScopeInfo scope,
            boolean isAlternative, int priority, List<InjectionPointInfo> injectionPoints,
            String name) {
        this(id, beanClass, kind, types, qualifiers, scope, isAlternative, priority,
                injectionPoints, name, Set.of(), Set.of(), List.of());
    }

    /**
     * Backward-compatible constructor with interceptorBindings but without constructorBindings.
     */
    public BeanDescriptor(BeanId id, DotName beanClass, BeanKind kind,
            Set<TypeInfo> types, Set<QualifierInstance> qualifiers, ScopeInfo scope,
            boolean isAlternative, int priority, List<InjectionPointInfo> injectionPoints,
            String name, Set<DotName> interceptorBindings) {
        this(id, beanClass, kind, types, qualifiers, scope, isAlternative, priority,
                injectionPoints, name, interceptorBindings, Set.of(), List.of());
    }
    
    /**
     * Backward-compatible constructor with interceptorBindings and constructorBindings.
     */
    public BeanDescriptor(BeanId id, DotName beanClass, BeanKind kind,
            Set<TypeInfo> types, Set<QualifierInstance> qualifiers, ScopeInfo scope,
            boolean isAlternative, int priority, List<InjectionPointInfo> injectionPoints,
            String name, Set<DotName> interceptorBindings, Set<DotName> constructorBindings) {
        this(id, beanClass, kind, types, qualifiers, scope, isAlternative, priority,
                injectionPoints, name, interceptorBindings, constructorBindings, List.of());
    }
}
