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

import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Qualifier resolution, extracted from {@link BeanDiscovery} (which stays the facade and
 * delegates): the {@code computeQualifiers*} family with their CDI-spec variations
 * (bean vs injection-point vs observer defaulting), repeatable-qualifier unwrapping,
 * {@code @Named} defaulting, {@code @Inherited} annotation lookup, and the
 * is-a-qualifier detection (index-first / reflection-fallback).
 */
final class QualifierResolver {

    private final BeanDiscovery host;

    QualifierResolver(BeanDiscovery host) {
        this.host = host;
    }

    Set<QualifierInstance> computeQualifiers(List<AnnotationInfo> annotations) {
        var qualifiers = new LinkedHashSet<QualifierInstance>();
        boolean hasExplicitQualifier = false;

        for (var ann : annotations) {
            if (isQualifierAnnotation(ann.name())) {
                qualifiers.add(QualifierInstance.from(ann));
                if (!ann.name().equals(QualifierInstance.NAMED_NAME)
                        && !ann.name().equals(QualifierInstance.ANY_NAME)) {
                    hasExplicitQualifier = true;
                }
            } else {
                // Unwrap repeatable qualifier container annotations
                var unwrapped = unwrapRepeatableQualifiers(ann);
                if (!unwrapped.isEmpty()) {
                    qualifiers.addAll(unwrapped);
                    hasExplicitQualifier = true;
                }
            }
        }

        if (!hasExplicitQualifier) {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
        qualifiers.add(QualifierInstance.ANY);

        return qualifiers;
    }

    Set<QualifierInstance> computeInjectionPointQualifiers(List<AnnotationInfo> annotations) {
        var qualifiers = new LinkedHashSet<QualifierInstance>();
        boolean hasExplicitQualifier = false;

        for (var ann : annotations) {
            if (isQualifierAnnotation(ann.name())) {
                qualifiers.add(QualifierInstance.from(ann));
                if (!ann.name().equals(QualifierInstance.NAMED_NAME)) {
                    hasExplicitQualifier = true;
                }
            } else {
                var unwrapped = unwrapRepeatableQualifiers(ann);
                if (!unwrapped.isEmpty()) {
                    qualifiers.addAll(unwrapped);
                    hasExplicitQualifier = true;
                }
            }
        }

        if (!hasExplicitQualifier) {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
        qualifiers.add(QualifierInstance.ANY);

        return qualifiers;
    }

    /**
     * CDI spec: @Named without a value on an injection point defaults to the field name.
     */
    Set<QualifierInstance> resolveNamedDefault(Set<QualifierInstance> qualifiers, String defaultName) {
        var result = new LinkedHashSet<QualifierInstance>();
        for (var q : qualifiers) {
            if (q.annotationName().equals(QualifierInstance.NAMED_NAME) && q.members().isEmpty()) {
                // @Named without value → default to field/parameter name
                result.add(new QualifierInstance(QualifierInstance.NAMED_NAME,
                        java.util.Map.of(BeanDiscovery.MEMBER_VALUE, new io.vidocq.vauban.indexer.model.AnnotationValue.StringVal(defaultName))));
            } else {
                result.add(q);
            }
        }
        return result;
    }

    /**
     * Computes qualifiers for observer methods — only explicit qualifiers,
     * no automatic @Default/@Any (CDI spec: an observer with no qualifiers
     * observes all events of that type regardless of qualifiers).
     */
    Set<QualifierInstance> computeObserverQualifiers(List<AnnotationInfo> annotations) {
        var qualifiers = new LinkedHashSet<QualifierInstance>();
        for (var ann : annotations) {
            if (isQualifierAnnotation(ann.name())) {
                qualifiers.add(QualifierInstance.from(ann));
            } else {
                qualifiers.addAll(unwrapRepeatableQualifiers(ann));
            }
        }
        return qualifiers;
    }

    Set<QualifierInstance> computeQualifiersWithStereotypes(ClassInfo classInfo) {
        var allAnnotations = new ArrayList<>(classInfo.annotations());

        // Add inherited annotations from superclasses
        for (var ann : getInheritedAnnotations(classInfo)) {
            allAnnotations.add(toAnnotationInfo(ann));
        }

        // Add annotations from stereotypes (direct + inherited)
        // CDI spec: @Named from stereotype gives name but is NOT added as qualifier
        var allAnnotationNames = host.getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (host.isStereotype(annName)) {
                var stereotypeClass = host.index.getClassByName(annName);
                if (stereotypeClass.isPresent()) {
                    for (var sa : stereotypeClass.get().annotations()) {
                        if (!sa.name().equals(BeanDiscovery.NAMED)) {
                            allAnnotations.add(sa);
                        }
                    }
                }
            }
        }

        return computeQualifiers(allAnnotations);
    }

    /**
     * Returns annotations inherited from superclasses (those NOT declared directly on classInfo).
     * Uses Java reflection — Class.getAnnotations() handles @Inherited automatically per JLS.
     */
    @SuppressWarnings("java:S1141") // Nested try needed for classloader fallback
    List<java.lang.annotation.Annotation> getInheritedAnnotations(ClassInfo classInfo) {
        try {
            // Use TCCL first (TCK sets this to its custom ClassLoader), fallback to system
            var cl = Thread.currentThread().getContextClassLoader();
            Class<?> cls;
            try {
                cls = Class.forName(classInfo.name().value(), false, cl);
            } catch (ClassNotFoundException e1) {
                cls = Class.forName(classInfo.name().value());
            }
            var declared = cls.getDeclaredAnnotations();
            var all = cls.getAnnotations();
            var declaredNames = new HashSet<Class<?>>();
            for (var d : declared) {
                declaredNames.add(d.annotationType());
            }
            var inherited = new ArrayList<java.lang.annotation.Annotation>();
            for (var a : all) {
                if (!declaredNames.contains(a.annotationType())) {
                    inherited.add(a);
                }
            }
            return inherited;
        } catch (ClassNotFoundException e) {
            return List.of();
        }
    }

    /**
     * Converts a java.lang.annotation.Annotation to an AnnotationInfo for indexer compatibility.
     */
    private AnnotationInfo toAnnotationInfo(java.lang.annotation.Annotation ann) {
        var members = new java.util.LinkedHashMap<String, io.vidocq.vauban.indexer.model.AnnotationValue>();
        for (var method : ann.annotationType().getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getDeclaringClass() == ann.annotationType()) {
                try {
                    var value = method.invoke(ann);
                    var converted = switch (value) {
                        case String s -> new io.vidocq.vauban.indexer.model.AnnotationValue.StringVal(s);
                        case Boolean b -> new io.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal(b);
                        case Integer i -> new io.vidocq.vauban.indexer.model.AnnotationValue.IntVal(i);
                        case Class<?> c -> new io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal(DotName.of(c.getName()));
                        case Enum<?> e -> new io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal(
                                DotName.of(e.getClass().getName()), e.name());
                        case null, default -> null;
                    };
                    if (converted != null) members.put(method.getName(), converted);
                } catch (Exception e) { /* skip */ }
            }
        }
        return new AnnotationInfo(DotName.of(ann.annotationType().getName()), members);
    }

    private List<QualifierInstance> unwrapRepeatableQualifiers(AnnotationInfo ann) {
        var result = new java.util.ArrayList<QualifierInstance>();
        // Check if this annotation's value() contains repeatable qualifier annotations
        var valueMember = ann.member(BeanDiscovery.MEMBER_VALUE);
        if (!(valueMember instanceof io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal arrayVal)) {
            return result;
        }
        for (var item : arrayVal.values()) {
            if (item instanceof io.vidocq.vauban.indexer.model.AnnotationValue.AnnotationVal av
                    && isQualifierAnnotation(av.annotation().name())) {
                result.add(QualifierInstance.from(av.annotation()));
            }
        }
        return result;
    }

    boolean isQualifierAnnotation(DotName name) {
        // Built-in qualifiers
        if (name.equals(QualifierInstance.DEFAULT_NAME)
                || name.equals(QualifierInstance.ANY_NAME)
                || name.equals(QualifierInstance.NAMED_NAME)) {
            return true;
        }

        // Custom qualifiers registered via @Discovery / MetaAnnotations
        if (host.customQualifiers.contains(name)) {
            return true;
        }

        // Check the index for the annotation class having @Qualifier
        var annClass = host.index.getClassByName(name);
        if (annClass.isPresent()) {
            return annClass.get().hasAnnotation(DotName.of("jakarta.inject.Qualifier"));
        }
        // Fallback: check via reflection with TCCL
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var annType = cl != null ? Class.forName(name.value(), false, cl) : Class.forName(name.value());
            return annType.isAnnotationPresent(jakarta.inject.Qualifier.class);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
