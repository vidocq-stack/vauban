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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.indexer.model.AnnotationValue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class QualifierHelper {

    private QualifierHelper() {}

    static Annotation[] extractParamQualifiers(Parameter param) {
        var quals = new ArrayList<Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : param.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                quals.add(ann);
                hasAnyAnnotation = true;
            }
        }
        if (!hasAnyAnnotation) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return quals.toArray(new Annotation[0]);
    }

    static Set<Annotation> collectQualifierSet(Annotation[] annotations) {
        var quals = new LinkedHashSet<Annotation>();
        for (var ann : annotations) {
            if (ann.annotationType() == jakarta.inject.Inject.class) continue;
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class) {
                quals.add(ann);
            }
        }
        if (quals.isEmpty()) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return quals;
    }

    static Annotation[] collectEventQualifiers(Annotation[] annotations) {
        var quals = new ArrayList<Annotation>();
        boolean hasExplicitQualifier = false;
        for (var ann : annotations) {
            if (ann.annotationType() == jakarta.inject.Inject.class) continue;
            if (ann.annotationType() == jakarta.enterprise.inject.Default.class) {
                quals.add(ann);
                continue;
            }
            if (ann.annotationType() == jakarta.enterprise.inject.Any.class) {
                quals.add(ann);
                hasExplicitQualifier = true;
                continue;
            }
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                quals.add(ann);
                hasExplicitQualifier = true;
            }
        }
        if (!hasExplicitQualifier) {
            boolean hasDefault = quals.stream().anyMatch(q -> q.annotationType() == jakarta.enterprise.inject.Default.class);
            if (!hasDefault) {
                quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
            }
        }
        boolean hasAny = quals.stream().anyMatch(q -> q.annotationType() == jakarta.enterprise.inject.Any.class);
        if (!hasAny) {
            quals.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        }
        return quals.toArray(new Annotation[0]);
    }

    static Annotation[] extractFieldQualifiersWithEnhancement(Field field, BeanDescriptor descriptor) {
        if (descriptor != null) {
            for (var ip : descriptor.injectionPoints()) {
                if (ip.kind() != io.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.FIELD) continue;
                if (!ip.description().endsWith("." + field.getName())) continue;
                return qualifierInstancesToAnnotations(ip.qualifiers());
            }
        }
        return extractFieldQualifiers(field);
    }

    static Annotation[] qualifierInstancesToAnnotations(Set<QualifierInstance> qualifierInstances) {
        var annotations = new ArrayList<Annotation>();
        for (var qi : qualifierInstances) {
            var name = qi.annotationName().value();
            if (name.equals("jakarta.enterprise.inject.Default")) {
                annotations.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
            } else if (name.equals("jakarta.enterprise.inject.Any")) {
                annotations.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
            } else if (name.equals("jakarta.inject.Named")) {
                var nameValue = qi.members().get("value");
                var strValue = nameValue instanceof AnnotationValue.StringVal sv
                        ? sv.value() : "";
                annotations.add(jakarta.enterprise.inject.literal.NamedLiteral.of(strValue));
            } else {
                try {
                    @SuppressWarnings("unchecked")
                    var annType = (Class<? extends Annotation>)
                            Thread.currentThread().getContextClassLoader().loadClass(name);
                    annotations.add(createQualifierAnnotation(annType, qi.members()));
                } catch (ClassNotFoundException e) {
                    // Skip unloadable qualifier
                }
            }
        }
        if (annotations.isEmpty()) {
            annotations.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return annotations.toArray(new Annotation[0]);
    }

    static <A extends Annotation> A createQualifierAnnotation(
            Class<A> annType, Map<String, AnnotationValue> members) {
        return io.vidocq.vauban.core.annotation.AnnotationInstances.create(annType,
                new io.vidocq.vauban.indexer.model.AnnotationInfo(
                        io.vidocq.vauban.indexer.model.DotName.of(annType.getName()), members),
                annType.getClassLoader());
    }

    static Annotation[] extractFieldQualifiers(Field field) {
        var qualifiers = new ArrayList<Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : field.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                qualifiers.add(ann);
                hasAnyAnnotation = true;
            }
        }
        if (!hasAnyAnnotation) {
            qualifiers.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return qualifiers.toArray(new Annotation[0]);
    }
}
