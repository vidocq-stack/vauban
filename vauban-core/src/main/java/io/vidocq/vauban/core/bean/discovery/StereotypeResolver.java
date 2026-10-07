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
        if (val.startsWith(BeanDiscovery.PREFIX_JAVA_ANNOTATION) ||
            val.startsWith(BeanDiscovery.PREFIX_JAKARTA_INTERCEPTOR) ||
            val.startsWith(BeanDiscovery.PREFIX_JAKARTA_INJECT) ||
            val.startsWith("jakarta.inject.")) {
            return false;
        }
        var annClass = host.index.getClassByName(annotationName);
        if (annClass.isPresent()) {
            return annClass.get().hasAnnotation(BeanDiscovery.STEREOTYPE);
        }
        // Fallback: check via reflection
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var annType = cl != null ? Class.forName(val, false, cl)
                    : Class.forName(val);
            return annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class);
        } catch (Exception e) {
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
        if (stereotypeClass.isPresent()) {
            if (stereotypeClass.get().hasAnnotation(BeanDiscovery.ALTERNATIVE)) return true;
            // Check transitive stereotypes
            for (var ann : stereotypeClass.get().annotations()) {
                if (isStereotype(ann.name()) && isAlternativeStereotype(ann.name(), visited)) {
                    return true;
                }
            }
        } else {
            // Reflection fallback
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var annType = cl != null ? Class.forName(stereotypeName.value(), false, cl)
                        : Class.forName(stereotypeName.value());
                if (annType.isAnnotationPresent(jakarta.enterprise.inject.Alternative.class)) return true;
                for (var metaAnn : annType.getAnnotations()) {
                    if (metaAnn.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)
                            && isAlternativeStereotype(DotName.of(metaAnn.annotationType().getName()), visited)) {
                        return true;
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }
        return false;
    }

    int extractPriorityWithStereotypes(ClassInfo classInfo) {
        int priority = host.extractPriority(classInfo.annotations());
        if (priority > 0) return priority;
        // Check via reflection (more reliable for annotation member values)
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            if (clazz.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                return clazz.getAnnotation(jakarta.annotation.Priority.class).value();
            }
        } catch (Exception e) { /* skip */ }
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
            int p = host.extractPriority(stereotypeClass.get().annotations());
            if (p > 0) return p;
            // Check transitive stereotypes
            for (var ann : stereotypeClass.get().annotations()) {
                if (isStereotype(ann.name())) {
                    int tp = extractPriorityFromStereotypeRecursive(ann.name(), visited);
                    if (tp > 0) return tp;
                }
            }
        }
        // Reflection fallback
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var annType = cl != null ? Class.forName(stereotypeName.value(), false, cl)
                    : Class.forName(stereotypeName.value());
            if (annType.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                return annType.getAnnotation(jakarta.annotation.Priority.class).value();
            }
            // Transitive via reflection
            for (var metaAnn : annType.getAnnotations()) {
                if (metaAnn.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                    int tp = extractPriorityFromStereotypeRecursive(
                            DotName.of(metaAnn.annotationType().getName()), visited);
                    if (tp > 0) return tp;
                }
            }
        } catch (Exception e) { /* skip */ }
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
        if (stereotypeClass.isPresent()) {
            for (var ann : stereotypeClass.get().annotations()) {
                if (ann.name().equals(BeanDiscovery.NAMED)) return true;
                if (isStereotype(ann.name()) && hasNamedInStereotypeRecursive(ann.name(), visited)) return true;
            }
        } else {
            try {
                var annType = Class.forName(stereotypeName.value());
                if (annType.isAnnotationPresent(jakarta.inject.Named.class)) return true;
                for (var meta : annType.getAnnotations()) {
                    if (meta.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)
                            && hasNamedInStereotypeRecursive(DotName.of(meta.annotationType().getName()), visited)) {
                        return true;
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }
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

        // Check custom stereotype annotations (from @Discovery phase)
        var customAnns = host.customStereotypeAnnotations.get(stereotypeName);
        if (customAnns != null) {
            for (var annClass : customAnns) {
                var scope = host.mapScope(DotName.of(annClass.getName()));
                if (scope != null) return scope;
            }
        }

        var stereotypeClass = host.index.getClassByName(stereotypeName);
        if (stereotypeClass.isPresent()) {
            // Check direct scope annotations
            for (var ann : stereotypeClass.get().annotations()) {
                var scope = host.mapScope(ann.name());
                if (scope != null) return scope;
            }
            // Recurse into transitive stereotypes
            for (var ann : stereotypeClass.get().annotations()) {
                if (isStereotype(ann.name())) {
                    var scope = findScopeInStereotypeRecursive(ann.name(), visited);
                    if (scope != null) return scope;
                }
            }
        } else {
            try {
                var annType = Class.forName(stereotypeName.value());
                for (var metaAnn : annType.getAnnotations()) {
                    var scope = host.mapScope(DotName.of(metaAnn.annotationType().getName()));
                    if (scope != null) return scope;
                    if (metaAnn.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                        var s = findScopeInStereotypeRecursive(DotName.of(metaAnn.annotationType().getName()), visited);
                        if (s != null) return s;
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }
        return null;
    }
}
