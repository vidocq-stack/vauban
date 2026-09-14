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

import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Interceptor discovery and interceptor-binding resolution, extracted from
 * {@link BeanDiscovery} (which stays the single facade — its {@code discoverInterceptors()}
 * and {@code isInterceptorBinding(DotName)} delegate here). This was the bug-densest zone
 * of the discovery (VAU-INT-004/005 lived around it); keeping it in one focused class makes
 * its index-first / reflection-fallback duality reviewable.
 */
final class InterceptorDiscovery {

    private final BeanDiscovery host;

    InterceptorDiscovery(BeanDiscovery host) {
        this.host = host;
    }

    /**
     * Discovers all interceptors in the index.
     * An interceptor is a class annotated with {@code @jakarta.interceptor.Interceptor}.
     */
    List<InterceptorDescriptor> discoverInterceptors() {
        var interceptors = new ArrayList<InterceptorDescriptor>();

        for (var classInfo : host.index.getKnownClasses()) {
            boolean isInterceptor = classInfo.hasAnnotation(BeanDiscovery.INTERCEPTOR);
            if (!isInterceptor) {
                // Fallback to reflection if index is incomplete
                try {
                    var cl = Thread.currentThread().getContextClassLoader();
                    var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                            : Class.forName(classInfo.name().value());
                    if (clazz.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
                        isInterceptor = true;
                    }
                } catch (Exception e) { /* skip */ }
            }

            if (!isInterceptor) {
                continue;
            }
            addInterceptor(classInfo, interceptors);
        }

        // Sort by priority
        interceptors.sort(Comparator.comparingInt(InterceptorDescriptor::priority));
        return interceptors;
    }

    private void addInterceptor(ClassInfo classInfo, List<InterceptorDescriptor> interceptors) {
        // Find bindings: annotations on the class whose annotation type is @InterceptorBinding
        var bindings = new LinkedHashSet<DotName>();
        collectBindings(classInfo, bindings);
        if (bindings.isEmpty()) {
            return;
        }

        // Find @AroundInvoke and @AroundConstruct methods (from index + reflection fallback)
        String aroundInvoke = null;
        String aroundConstruct = null;
        for (var method : classInfo.methods()) {
            if (BeanDiscovery.hasAnnotation(method.annotations(), BeanDiscovery.AROUND_INVOKE)) {
                aroundInvoke = method.name();
            }
            if (BeanDiscovery.hasAnnotation(method.annotations(), BeanDiscovery.AROUND_CONSTRUCT)) {
                aroundConstruct = method.name();
            }
        }
        // Reflection fallback for @AroundInvoke / @AroundConstruct
        if (aroundInvoke == null || aroundConstruct == null) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                        : Class.forName(classInfo.name().value());
                for (var m : clazz.getDeclaredMethods()) {
                    if (aroundInvoke == null && m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                        aroundInvoke = m.getName();
                    }
                    if (aroundConstruct == null && m.isAnnotationPresent(jakarta.interceptor.AroundConstruct.class)) {
                        aroundConstruct = m.getName();
                    }
                }
                // Also check superclass
                if (aroundInvoke == null || aroundConstruct == null) {
                    var superClass = clazz.getSuperclass();
                    while (superClass != null && superClass != Object.class) {
                        for (var m : superClass.getDeclaredMethods()) {
                            if (aroundInvoke == null && m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                                aroundInvoke = m.getName();
                            }
                            if (aroundConstruct == null && m.isAnnotationPresent(jakarta.interceptor.AroundConstruct.class)) {
                                aroundConstruct = m.getName();
                            }
                        }
                        if (aroundInvoke != null && aroundConstruct != null) break;
                        superClass = superClass.getSuperclass();
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }

        // Collect actual binding annotations for member comparison
        var bindingAnnotations = new ArrayList<java.lang.annotation.Annotation>();
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz2 = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            for (var ann : clazz2.getAnnotations()) {
                // `bindings` above already says which types bind, extension-registered ones included.
                // Asking the annotation type for a physical @InterceptorBinding instead dropped every
                // binding an extension declared, leaving nothing to compare members with, so the
                // interceptor bound whatever the values were (BUG-20260914-08).
                if (bindings.contains(DotName.of(ann.annotationType().getName()))) {
                    bindingAnnotations.add(ann);
                }
            }
        } catch (ClassNotFoundException e) { /* skip */ }

        var priority = host.extractPriority(classInfo.annotations());
        boolean hasPriority = BeanDiscovery.hasAnnotation(classInfo.annotations(), DotName.of("jakarta.annotation.Priority"));
        // Reflection fallback for @Priority detection
        if (!hasPriority) {
            try {
                var cl2 = Thread.currentThread().getContextClassLoader();
                var clazz3 = cl2 != null ? Class.forName(classInfo.name().value(), false, cl2)
                        : Class.forName(classInfo.name().value());
                hasPriority = clazz3.isAnnotationPresent(jakarta.annotation.Priority.class);
                if (hasPriority && priority == 0) {
                    priority = clazz3.getAnnotation(jakarta.annotation.Priority.class).value();
                }
            } catch (Exception e) { /* skip */ }
        }
        interceptors.add(new InterceptorDescriptor(classInfo.name(), bindings, aroundInvoke, aroundConstruct, priority, hasPriority, bindingAnnotations));
    }

    /**
     * Checks if the given annotation name is an interceptor binding
     * (i.e., it is itself annotated with {@code @InterceptorBinding} in the index).
     */
    boolean isInterceptorBinding(DotName annotationName) {
        if (host.customInterceptorBindings.contains(annotationName)) {
            return true;
        }
        String val = annotationName.value();
        if (val.startsWith(BeanDiscovery.PREFIX_JAVA_ANNOTATION) ||
            val.startsWith(BeanDiscovery.PREFIX_JAKARTA_INTERCEPTOR) ||
            val.startsWith(BeanDiscovery.PREFIX_JAKARTA_INJECT) ||
            val.startsWith("jakarta.inject.")) {
            // These are never interceptor bindings themselves for application beans
            return false;
        }
        var annClass = host.index.getClassByName(annotationName);
        if (annClass.isPresent()) {
            // Check direct @InterceptorBinding
            if (annClass.get().hasAnnotation(BeanDiscovery.INTERCEPTOR_BINDING)) return true;
            // CDI spec: transitive interceptor bindings — check meta-annotations
            for (var metaAnn : annClass.get().annotations()) {
                if (metaAnn.name().equals(BeanDiscovery.INTERCEPTOR_BINDING)) return true;
                // Check if a meta-annotation is itself an interceptor binding (transitive)
                var metaClass = host.index.getClassByName(metaAnn.name());
                if (metaClass.isPresent() && metaClass.get().hasAnnotation(BeanDiscovery.INTERCEPTOR_BINDING)) {
                    return true;
                }
            }
        }
        // Fallback: check via reflection with TCCL
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var annType = cl != null ? Class.forName(val, false, cl)
                    : Class.forName(val);
            if (annType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) return true;
            // Check transitive bindings via reflection
            for (var metaAnn : annType.getAnnotations()) {
                if (metaAnn.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void collectBindings(ClassInfo classInfo, Set<DotName> bindings) {
        var visited = new java.util.HashSet<DotName>();
        var current = classInfo;
        while (current != null) {
            collectBindingsRecursively(current, bindings, visited);
            // Check superclass from index
            var superName = current.superName();
            if (superName != null && !superName.value().equals(BeanDiscovery.JAVA_LANG_OBJECT)) {
                current = host.index.getClassByName(superName).orElse(null);
            } else {
                current = null;
            }
        }

        // Fallback for classes not fully indexed (e.g. inner classes in some environments)
        // Check for bindings via reflection as well, walking the hierarchy
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            var curr = clazz;
            while (curr != null && curr != Object.class) {
                for (var ann : curr.getAnnotations()) {
                    var annType = ann.annotationType();
                    if (isInterceptorBinding(DotName.of(annType.getName()))) {
                        // EXPLICITLY SKIP built-in Jakarta/Java annotations in the final set
                        String val = annType.getName();
                        if (!val.startsWith(BeanDiscovery.PREFIX_JAVA_ANNOTATION) &&
                            !val.startsWith(BeanDiscovery.PREFIX_JAKARTA_INTERCEPTOR) &&
                            !val.startsWith(BeanDiscovery.PREFIX_JAKARTA_INJECT)) {
                            bindings.add(DotName.of(val));
                        }
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Exception e) { /* skip */ }
    }

    private void collectBindingsRecursively(ClassInfo classInfo, Set<DotName> result, Set<DotName> visited) {
        if (!visited.add(classInfo.name())) return;

        for (var ann : classInfo.annotations()) {
            var name = ann.name();

            if (isInterceptorBinding(name)) {
                result.add(name);
                // Transitive bindings
                var annClass = host.index.getClassByName(name);
                if (annClass.isPresent()) {
                    collectBindingsRecursively(annClass.get(), result, visited);
                }
            } else if (host.isStereotype(name)) {
                // Stereotypes can have bindings
                var annClass = host.index.getClassByName(name);
                if (annClass.isPresent()) {
                    collectBindingsRecursively(annClass.get(), result, visited);
                }
            }
        }
    }
}
