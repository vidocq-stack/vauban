/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

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
        MANAGED, PRODUCER_METHOD, PRODUCER_FIELD, SYNTHETIC
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

    /** Create a minimal descriptor for a class (used by BCE Enhancement/Registration fallback). */
    public static BeanDescriptor minimal(DotName className) {
        return new BeanDescriptor(
                new BeanId(className.value()), className, BeanKind.MANAGED,
                Set.of(new TypeInfo.ClassType(className)),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                new ScopeInfo(DotName.of("jakarta.enterprise.context.Dependent"), false),
                false, 0, List.of(), null);
    }
}
