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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.loading.DeploymentClasses;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.List;
import java.util.Set;

/**
 * Stereotype resolution, extracted from {@link BeanDiscovery} (which stays the facade and
 * delegates): is-a-stereotype detection plus the four transitive lookups a stereotype can
 * contribute to a bean — {@code @Alternative}, {@code @Priority}, {@code @Named} defaulting
 * and the scope. Every walk follows the same index-first / reflection-fallback duality with
 * a {@code visited} guard against stereotype cycles.
 */
final class StereotypeResolver {

    private final BeanDiscovery host;

    StereotypeResolver(BeanDiscovery host) {
        this.host = host;
    }


    boolean isStereotype(DotName annotationName) {
        if (host.customStereotypes.contains(annotationName)) {
            return true;
        }
        String val = annotationName.value();
        if (BeanDiscovery.isPlatformAnnotation(val)) {
            return false;
        }
        var annClass = host.index.getClassByName(annotationName);
        if (annClass.isPresent()) {
            return annClass.get().hasAnnotation(BeanDiscovery.STEREOTYPE);
        }
        // Fallback: check via reflection
        try {
            return DeploymentClasses.load(val).isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class);
        } catch (Exception _) {
            return false;
        }
    }

    boolean isAlternativeWithStereotypes(ClassInfo classInfo) {
        if (classInfo.hasAnnotation(BeanDiscovery.ALTERNATIVE)) return true;
        // Check inherited @Alternative
        for (var ann : host.getInheritedAnnotations(classInfo)) {
            if (DotName.of(ann.annotationType().getName()).equals(BeanDiscovery.ALTERNATIVE)) return true;
        }
        // Check stereotypes for @Alternative (direct + transitive)
        var allAnnotationNames = host.getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName) && isAlternativeStereotype(annName, new java.util.HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAlternativeStereotype(DotName stereotypeName, Set<DotName> visited) {
        if (!visited.add(stereotypeName)) return false;
        var stereotypeClass = host.index.getClassByName(stereotypeName);
        return stereotypeClass.isPresent()
                ? isIndexedAlternativeStereotype(stereotypeClass.get(), visited)
                : isReflectedAlternativeStereotype(stereotypeName, visited);
    }

    private boolean isIndexedAlternativeStereotype(ClassInfo stereotypeClass, Set<DotName> visited) {
        if (stereotypeClass.hasAnnotation(BeanDiscovery.ALTERNATIVE)) return true;
        // Check transitive stereotypes
        for (var ann : stereotypeClass.annotations()) {
            if (isStereotype(ann.name()) && isAlternativeStereotype(ann.name(), visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean isReflectedAlternativeStereotype(DotName stereotypeName, Set<DotName> visited) {
        try {
            var annType = DeploymentClasses.load(stereotypeName.value());
            if (annType.isAnnotationPresent(jakarta.enterprise.inject.Alternative.class)) return true;
            for (var metaAnn : annType.getAnnotations()) {
                if (isReflectedStereotype(metaAnn)
                        && isAlternativeStereotype(DotName.of(metaAnn.annotationType().getName()), visited)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException _) { /* skip */ }
        return false;
    }

    private static boolean isReflectedStereotype(java.lang.annotation.Annotation annotation) {
        return annotation.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class);
    }

    int extractPriorityWithStereotypes(ClassInfo classInfo) {
        int priority = host.extractPriority(classInfo.annotations());
        if (priority > 0) return priority;
        // Check via reflection (more reliable for annotation member values)
        try {
            var clazz = DeploymentClasses.load(classInfo.name().value());
            if (clazz.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                return clazz.getAnnotation(jakarta.annotation.Priority.class).value();
            }
        } catch (Exception _) { /* skip */ }
        // Search through stereotypes (including transitive)
        var allAnnotationNames = host.getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName)) {
                int stereotypePriority = extractPriorityFromStereotypeRecursive(annName, new java.util.HashSet<>());
                if (stereotypePriority > 0) return stereotypePriority;
            }
        }
        return 0;
    }

    private int extractPriorityFromStereotypeRecursive(DotName stereotypeName, Set<DotName> visited) {
        if (!visited.add(stereotypeName)) return 0;
        // Check index
        var stereotypeClass = host.index.getClassByName(stereotypeName);
        if (stereotypeClass.isPresent()) {
            int p = indexedStereotypePriority(stereotypeClass.get(), visited);
            if (p > 0) return p;
        }
        // Reflection fallback
        return reflectedStereotypePriority(stereotypeName, visited);
    }

    /** The first positive {@code @Priority} the indexed stereotype or its own stereotypes declare, or 0. */
    private int indexedStereotypePriority(ClassInfo stereotypeClass, Set<DotName> visited) {
        int p = host.extractPriority(stereotypeClass.annotations());
        if (p > 0) return p;
        // Check transitive stereotypes
        for (var ann : stereotypeClass.annotations()) {
            if (isStereotype(ann.name())) {
                int tp = extractPriorityFromStereotypeRecursive(ann.name(), visited);
                if (tp > 0) return tp;
            }
        }
        return 0;
    }

    private int reflectedStereotypePriority(DotName stereotypeName, Set<DotName> visited) {
        try {
            var annType = DeploymentClasses.load(stereotypeName.value());
            if (annType.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                return annType.getAnnotation(jakarta.annotation.Priority.class).value();
            }
            // Transitive via reflection
            for (var metaAnn : annType.getAnnotations()) {
                if (isReflectedStereotype(metaAnn)) {
                    int tp = extractPriorityFromStereotypeRecursive(
                            DotName.of(metaAnn.annotationType().getName()), visited);
                    if (tp > 0) return tp;
                }
            }
        } catch (Exception _) { /* skip */ }
        return 0;
    }

    String extractNameWithStereotypes(ClassInfo classInfo) {
        // Check bean itself first (direct + inherited annotations)
        var name = host.extractName(classInfo.annotations(), BeanDiscovery.decapitalize(classInfo.simpleName()));
        if (name != null) return name;
        // Check inherited @Named
        for (var ann : host.getInheritedAnnotations(classInfo)) {
            if (ann.annotationType() == jakarta.inject.Named.class) {
                var named = (jakarta.inject.Named) ann;
                return named.value().isEmpty() ? BeanDiscovery.decapitalize(classInfo.simpleName()) : named.value();
            }
        }

        // Check stereotypes (direct + transitive)
        var allAnnotationNames = host.getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName)
                    && hasNamedInStereotypeRecursive(annName, new java.util.HashSet<>())) {
                return BeanDiscovery.decapitalize(classInfo.simpleName());
            }
        }

        return null;
    }

    private boolean hasNamedInStereotypeRecursive(DotName stereotypeName, Set<DotName> visited) {
        if (!visited.add(stereotypeName)) return false;
        var stereotypeClass = host.index.getClassByName(stereotypeName);
        return stereotypeClass.isPresent()
                ? hasNamedInIndexedStereotype(stereotypeClass.get(), visited)
                : hasNamedInReflectedStereotype(stereotypeName, visited);
    }

    private boolean hasNamedInIndexedStereotype(ClassInfo stereotypeClass, Set<DotName> visited) {
        for (var ann : stereotypeClass.annotations()) {
            if (ann.name().equals(BeanDiscovery.NAMED)) return true;
            if (isStereotype(ann.name()) && hasNamedInStereotypeRecursive(ann.name(), visited)) return true;
        }
        return false;
    }

    private boolean hasNamedInReflectedStereotype(DotName stereotypeName, Set<DotName> visited) {
        try {
            var annType = DeploymentClasses.load(stereotypeName.value());
            if (annType.isAnnotationPresent(jakarta.inject.Named.class)) return true;
            for (var meta : annType.getAnnotations()) {
                if (isReflectedStereotype(meta)
                        && hasNamedInStereotypeRecursive(DotName.of(meta.annotationType().getName()), visited)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException _) { /* skip */ }
        return false;
    }

    ScopeInfo computeScopeWithStereotypes(List<AnnotationInfo> annotations) {
        // 1. Explicit scope
        for (var ann : annotations) {
            var scope = host.mapScope(ann.name());
            if (scope != null) return scope;
        }
        // 2. Scope from stereotype (transitively)
        for (var ann : annotations) {
            if (isStereotype(ann.name())) {
                var scope = findScopeInStereotypeRecursive(ann.name(), new java.util.HashSet<>());
                if (scope != null) return scope;
            }
        }
        return ScopeInfo.DEPENDENT;
    }

    ScopeInfo findScopeInStereotypeRecursive(DotName stereotypeName, Set<DotName> visited) {
        if (!visited.add(stereotypeName)) return null;

        var customScope = customStereotypeScope(stereotypeName);
        if (customScope != null) return customScope;

        var stereotypeClass = host.index.getClassByName(stereotypeName);
        return stereotypeClass.isPresent()
                ? indexedStereotypeScope(stereotypeClass.get(), visited)
                : reflectedStereotypeScope(stereotypeName, visited);
    }

    /** The scope among the annotations a {@code @Discovery} phase gave the stereotype, or {@code null}. */
    private ScopeInfo customStereotypeScope(DotName stereotypeName) {
        var customAnns = host.customStereotypeAnnotations.get(stereotypeName);
        if (customAnns != null) {
            for (var annClass : customAnns) {
                var scope = host.mapScope(DotName.of(annClass.getName()));
                if (scope != null) return scope;
            }
        }
        return null;
    }

    private ScopeInfo indexedStereotypeScope(ClassInfo stereotypeClass, Set<DotName> visited) {
        // Check direct scope annotations
        for (var ann : stereotypeClass.annotations()) {
            var scope = host.mapScope(ann.name());
            if (scope != null) return scope;
        }
        // Recurse into transitive stereotypes
        for (var ann : stereotypeClass.annotations()) {
            if (isStereotype(ann.name())) {
                var scope = findScopeInStereotypeRecursive(ann.name(), visited);
                if (scope != null) return scope;
            }
        }
        return null;
    }

    private ScopeInfo reflectedStereotypeScope(DotName stereotypeName, Set<DotName> visited) {
        try {
            var annType = DeploymentClasses.load(stereotypeName.value());
            for (var metaAnn : annType.getAnnotations()) {
                var scope = host.mapScope(DotName.of(metaAnn.annotationType().getName()));
                if (scope != null) return scope;
                if (isReflectedStereotype(metaAnn)) {
                    var s = findScopeInStereotypeRecursive(DotName.of(metaAnn.annotationType().getName()), visited);
                    if (s != null) return s;
                }
            }
        } catch (ClassNotFoundException _) { /* skip */ }
        return null;
    }
}
