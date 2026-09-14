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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.annotation.AnnotationInstances;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns the qualifiers of a descriptor into the annotation instances the CDI API hands out
 * ({@code Bean#getQualifiers()}, {@code ObserverMethod#getObservedQualifiers()}).
 *
 * <p>A built-in qualifier becomes the literal the CDI API ships; any other becomes an
 * {@link AnnotationInstances} instance, which honours the {@code Annotation} contract. Resolution
 * never reads these instances back: it compares keys.
 */
public final class QualifierUtils {

    private QualifierUtils() {}

    /**
     * Converts a set of {@link QualifierInstance} to runtime {@link Annotation} instances.
     */
    public static Set<Annotation> toAnnotations(Set<QualifierInstance> qualifiers, String beanName) {
        return toAnnotations(qualifiers, beanName, null);
    }

    public static Set<Annotation> toAnnotations(Set<QualifierInstance> qualifiers, String beanName,
            ClassLoader cl) {
        return toAnnotations(qualifiers, beanName, cl, null);
    }

    /**
     * The instances of a bean's or an observer's qualifiers. With {@code types}, a module that
     * generated a literal for its own qualifier hands that literal out instead of a proxy.
     */
    public static Set<Annotation> toAnnotations(Set<QualifierInstance> qualifiers, String beanName,
            ClassLoader cl, io.vidocq.vauban.core.annotation.AnnotationTypes types) {
        var result = new LinkedHashSet<Annotation>();
        for (var qi : qualifiers) {
            var ann = toAnnotation(qi, beanName, cl, types);
            if (ann != null) result.add(ann);
        }
        return result;
    }

    public static Annotation toAnnotation(QualifierInstance qi, String beanName) {
        return toAnnotation(qi, beanName, null);
    }

    /**
     * Converts a single {@link QualifierInstance} to a runtime {@link Annotation}, or {@code null}
     * when none of the container's class loaders can load its annotation type.
     */
    public static Annotation toAnnotation(QualifierInstance qi, String beanName, ClassLoader cl) {
        return toAnnotation(qi, beanName, cl, null);
    }

    /** As above, letting {@code types} hand out a generated literal when the module shipped one. */
    public static Annotation toAnnotation(QualifierInstance qi, String beanName, ClassLoader cl,
            io.vidocq.vauban.core.annotation.AnnotationTypes types) {
        var qualifier = withBeanName(qi, beanName);
        return switch (qualifier.annotationName().value()) {
            case "jakarta.enterprise.inject.Default" -> jakarta.enterprise.inject.Default.Literal.INSTANCE;
            case "jakarta.enterprise.inject.Any" -> jakarta.enterprise.inject.Any.Literal.INSTANCE;
            case "jakarta.inject.Named" ->
                    jakarta.enterprise.inject.literal.NamedLiteral.of(stringMember(qualifier, "value"));
            default -> {
                if (types != null) {
                    yield types.instanceOf(qualifier.annotationName(), qualifier.members());
                }
                var annType = annotationType(qualifier.annotationName().value(), cl);
                yield annType == null ? null : AnnotationInstances.create(annType,
                        new AnnotationInfo(qualifier.annotationName(), qualifier.members()), cl);
            }
        };
    }

    /**
     * CDI 4.1 §3.1.5: {@code @Named} on a bean, with no value, takes the bean's name. The bean's
     * qualifier instance and its matching key must both carry it, so both go through this.
     */
    public static QualifierInstance withBeanName(QualifierInstance qualifier, String beanName) {
        if (beanName == null || !qualifier.annotationName().equals(QualifierInstance.NAMED_NAME)) {
            return qualifier;
        }
        if (!stringMember(qualifier, "value").isEmpty()) {
            return qualifier;
        }
        return new QualifierInstance(QualifierInstance.NAMED_NAME,
                Map.of("value", new AnnotationValue.StringVal(beanName)));
    }

    private static String stringMember(QualifierInstance qualifier, String member) {
        return qualifier.members().get(member) instanceof AnnotationValue.StringVal value ? value.value() : "";
    }

    private static Class<? extends Annotation> annotationType(String name, ClassLoader cl) {
        return AnnotationInstances.typeNamed(name, cl).orElse(null);
    }
}
