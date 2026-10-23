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
            if (isInterceptorClass(classInfo)) {
                addInterceptor(classInfo, interceptors);
            }
        }

        // Sort by priority
        interceptors.sort(Comparator.comparingInt(InterceptorDescriptor::priority));
        return interceptors;
    }

    private static boolean isInterceptorClass(ClassInfo classInfo) {
        if (classInfo.hasAnnotation(BeanDiscovery.INTERCEPTOR)) {
            return true;
        }
        // Fallback to reflection if index is incomplete
        try {
            return DeploymentClasses.load(classInfo.name().value())
                    .isAnnotationPresent(jakarta.interceptor.Interceptor.class);
        } catch (Exception _) {
            return false;
        }
    }

    private void addInterceptor(ClassInfo classInfo, List<InterceptorDescriptor> interceptors) {
        // Find bindings: annotations on the class whose annotation type is @InterceptorBinding
        var bindings = new LinkedHashSet<DotName>();
        collectBindings(classInfo, bindings);
        if (bindings.isEmpty()) {
            return;
        }

        // Find @AroundInvoke and @AroundConstruct methods (from index + reflection fallback)
        var aroundMethods = new AroundMethods();
        aroundMethods.readIndex(classInfo);
        if (!aroundMethods.complete()) {
            aroundMethods.readHierarchy(classInfo);
        }

        // Collect actual binding annotations for member comparison
        var bindingAnnotations = bindingAnnotations(classInfo, bindings);

        var priority = host.extractPriority(classInfo.annotations());
        boolean hasPriority = BeanDiscovery.hasAnnotation(classInfo.annotations(), DotName.of("jakarta.annotation.Priority"));
        // Reflection fallback for @Priority detection
        if (!hasPriority) {
            var reflected = reflectedPriority(classInfo);
            hasPriority = reflected != null;
            if (hasPriority && priority == 0) {
                priority = reflected.value();
            }
        }
        interceptors.add(new InterceptorDescriptor(classInfo.name(), bindings, aroundMethods.aroundInvoke,
                aroundMethods.aroundConstruct, priority, hasPriority, bindingAnnotations));
    }

    /**
     * The annotations of the interceptor class whose types bind. {@code bindings} already says which
     * types bind, extension-registered ones included. Asking the annotation type for a physical
     * {@code @InterceptorBinding} instead dropped every binding an extension declared, leaving
     * nothing to compare members with, so the interceptor bound whatever the values were
     * (BUG-20260914-08).
     */
    private static List<java.lang.annotation.Annotation> bindingAnnotations(ClassInfo classInfo,
            Set<DotName> bindings) {
        var bindingAnnotations = new ArrayList<java.lang.annotation.Annotation>();
        try {
            for (var ann : DeploymentClasses.load(classInfo.name().value()).getAnnotations()) {
                if (bindings.contains(DotName.of(ann.annotationType().getName()))) {
                    bindingAnnotations.add(ann);
                }
            }
        } catch (ClassNotFoundException _) { /* skip */ }
        return bindingAnnotations;
    }

    /** The {@code @Priority} the interceptor class carries when the index does not say, or {@code null}. */
    private static jakarta.annotation.Priority reflectedPriority(ClassInfo classInfo) {
        try {
            return DeploymentClasses.load(classInfo.name().value()).getAnnotation(jakarta.annotation.Priority.class);
        } catch (Exception _) {
            return null;
        }
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
        if (BeanDiscovery.isPlatformAnnotation(val)) {
            // These are never interceptor bindings themselves for application beans
            return false;
        }
        var annClass = host.index.getClassByName(annotationName);
        if (annClass.isPresent() && isIndexedInterceptorBinding(annClass.get())) {
            return true;
        }
        // Fallback: check via reflection with TCCL
        return isReflectedInterceptorBinding(val);
    }

    /** Direct {@code @InterceptorBinding}, or a meta-annotation that is one (CDI transitive bindings). */
    private boolean isIndexedInterceptorBinding(ClassInfo annClass) {
        if (annClass.hasAnnotation(BeanDiscovery.INTERCEPTOR_BINDING)) return true;
        for (var metaAnn : annClass.annotations()) {
            if (metaAnn.name().equals(BeanDiscovery.INTERCEPTOR_BINDING)) return true;
            // Check if a meta-annotation is itself an interceptor binding (transitive)
            var metaClass = host.index.getClassByName(metaAnn.name());
            if (metaClass.isPresent() && metaClass.get().hasAnnotation(BeanDiscovery.INTERCEPTOR_BINDING)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReflectedInterceptorBinding(String annotationName) {
        try {
            var annType = DeploymentClasses.load(annotationName);
            if (annType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) return true;
            // Check transitive bindings via reflection
            for (var metaAnn : annType.getAnnotations()) {
                if (metaAnn.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }

    private void collectBindings(ClassInfo classInfo, Set<DotName> bindings) {
        var visited = new java.util.HashSet<DotName>();
        var current = classInfo;
        do {
            collectBindingsRecursively(current, bindings, visited);
            current = indexedSuperclassOf(current);
        } while (current != null);
        collectReflectedBindings(classInfo, bindings);
    }

    /** The superclass of {@code classInfo} as the index knows it, or {@code null} at {@code Object} or past the index. */
    private ClassInfo indexedSuperclassOf(ClassInfo classInfo) {
        var superName = classInfo.superName();
        if (superName == null || superName.value().equals(BeanDiscovery.JAVA_LANG_OBJECT)) {
            return null;
        }
        return host.index.getClassByName(superName).orElse(null);
    }

    /**
     * Fallback for classes not fully indexed (e.g. inner classes in some environments): the bindings
     * the class and its superclasses carry, read by reflection. Built-in Jakarta and Java annotations
     * are explicitly left out of the final set.
     */
    private void collectReflectedBindings(ClassInfo classInfo, Set<DotName> bindings) {
        try {
            var curr = DeploymentClasses.load(classInfo.name().value());
            while (curr != null && curr != Object.class) {
                for (var ann : curr.getAnnotations()) {
                    String val = ann.annotationType().getName();
                    if (isInterceptorBinding(DotName.of(val)) && !hasBuiltInPrefix(val)) {
                        bindings.add(DotName.of(val));
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Exception _) { /* skip */ }
    }

    private static boolean hasBuiltInPrefix(String annotationName) {
        return annotationName.startsWith(BeanDiscovery.PREFIX_JAVA_ANNOTATION)
                || annotationName.startsWith(BeanDiscovery.PREFIX_JAKARTA_INTERCEPTOR)
                || annotationName.startsWith(BeanDiscovery.PREFIX_JAKARTA_INJECT);
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

    /**
     * The {@code @AroundInvoke} and {@code @AroundConstruct} methods of an interceptor, read from
     * the index first and from the class hierarchy for what the index does not say.
     */
    private static final class AroundMethods {

        private String aroundInvoke;
        private String aroundConstruct;

        boolean complete() {
            return aroundInvoke != null && aroundConstruct != null;
        }

        /** From the index: the last annotated method wins. */
        void readIndex(ClassInfo classInfo) {
            for (var method : classInfo.methods()) {
                if (BeanDiscovery.hasAnnotation(method.annotations(), BeanDiscovery.AROUND_INVOKE)) {
                    aroundInvoke = method.name();
                }
                if (BeanDiscovery.hasAnnotation(method.annotations(), BeanDiscovery.AROUND_CONSTRUCT)) {
                    aroundConstruct = method.name();
                }
            }
        }

        /** Reflection fallback: the class, then its superclasses until both methods are known. */
        void readHierarchy(ClassInfo classInfo) {
            try {
                var clazz = DeploymentClasses.load(classInfo.name().value());
                readDeclared(clazz);
                var superClass = clazz.getSuperclass();
                while (!complete() && superClass != null && superClass != Object.class) {
                    readDeclared(superClass);
                    superClass = superClass.getSuperclass();
                }
            } catch (ClassNotFoundException _) { /* skip */ }
        }

        /** Keeps what is already known; otherwise the first annotated method declared by {@code type}. */
        private void readDeclared(Class<?> type) {
            for (var m : type.getDeclaredMethods()) {
                if (aroundInvoke == null && m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                    aroundInvoke = m.getName();
                }
                if (aroundConstruct == null && m.isAnnotationPresent(jakarta.interceptor.AroundConstruct.class)) {
                    aroundConstruct = m.getName();
                }
            }
        }
    }
}
