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


import io.vidocq.vauban.core.bean.model.InjectionPointInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.invoke.MethodType;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class QualifierHelper {

    private QualifierHelper() {}

    /**
     * The qualifiers the descriptor records for parameter {@code index} of {@code member}, as the
     * annotation instances the injection point exposes — the module's own literal when it generated
     * one (vauban#70), never a reflective read of the parameter. {@code null} when nothing describes
     * that parameter, which is the caller's signal to fall back.
     */
    static Annotation[] parameterQualifiers(List<InjectionPointInfo> points,
            java.lang.reflect.Executable member, int index,
            io.vidocq.vauban.core.annotation.AnnotationTypes types) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        var declaringSimpleName = simpleName(member.getDeclaringClass());
        var methodName = member instanceof java.lang.reflect.Constructor<?> ? null : member.getName();
        var wanted = InjectionPointInfo.parameterDescription(declaringSimpleName,
                methodName, index, MethodType.methodType(void.class, member.getParameterTypes()).descriptorString());
        var legacy = InjectionPointInfo.parameterDescription(declaringSimpleName,
                methodName, index);
        InjectionPointInfo exact = null;
        InjectionPointInfo found = null;
        for (var point : points) {
            if (point.kind() == InjectionPointInfo.InjectionKind.FIELD) continue;
            if (point.description().equals(wanted)) exact = point;
            else if (point.description().equals(legacy)) {
                // Old descriptors use the name/position-only format. A unique legacy match is safe;
                // overloaded members remain unresolved rather than receiving another member's qualifier.
                if (found != null) return null;
                found = point;
            }
        }
        if (exact != null) found = exact;
        if (found == null) {
            return null;
        }
        var qualifiers = QualifierUtils.toAnnotations(found.declaredQualifiers(), null, null, types);
        return qualifiers.isEmpty()
                ? new Annotation[] {jakarta.enterprise.inject.Default.Literal.INSTANCE}
                : qualifiers.toArray(new Annotation[0]);
    }

    /**
     * The qualifiers the descriptor records for {@code field}, as annotation instances.
     * {@code null} when nothing describes it — an interceptor's field, injected without a descriptor.
     */
    static Annotation[] fieldQualifiers(List<InjectionPointInfo> points, Field field,
            io.vidocq.vauban.core.annotation.AnnotationTypes types) {
        var point = fieldPoint(points, field);
        if (point == null) {
            return null;
        }
        var qualifiers = QualifierUtils.toAnnotations(point.declaredQualifiers(), null, null, types);
        return qualifiers.isEmpty()
                ? new Annotation[] {jakarta.enterprise.inject.Default.Literal.INSTANCE}
                : qualifiers.toArray(new Annotation[0]);
    }

    /** The injection point describing {@code field}, matched on the whole description. */
    static InjectionPointInfo fieldPoint(List<InjectionPointInfo> points, Field field) {
        if (points == null) {
            return null;
        }
        // The declaring class is part of it: a subclass field may shadow a superclass field's name.
        var wanted = InjectionPointInfo.fieldDescription(
                simpleName(field.getDeclaringClass()), field.getName());
        for (var point : points) {
            if (point.kind() == InjectionPointInfo.InjectionKind.FIELD
                    && point.description().equals(wanted)) {
                return point;
            }
        }
        return null;
    }

    /**
     * An {@code Event} field's qualifiers: what the injection point declares, completed as CDI
     * requires — {@code @Default} when nothing else is written, and always {@code @Any}. The
     * qualifiers come from the descriptor, so unlike {@link #collectEventQualifiers} nothing has to
     * ask an annotation type whether it is one.
     */
    static Annotation[] eventQualifiers(Annotation[] described) {
        var quals = new LinkedHashSet<Annotation>(List.of(described));
        if (quals.stream().allMatch(q -> q.annotationType() == jakarta.enterprise.inject.Default.class)) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        quals.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        return quals.toArray(new Annotation[0]);
    }

    /**
     * The simple name as the index spells it — {@code Outer$Inner} for a nested class, where
     * {@link Class#getSimpleName()} says only {@code Inner}. The descriptions the two sides compare
     * are built from {@code DotName#simpleName()}, so this has to agree with it.
     */
    private static String simpleName(Class<?> type) {
        var name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    static Annotation[] extractParamQualifiers(Parameter param) {
        io.vidocq.vauban.core.annotation.AnnotationReflection.checkParameter(param);
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

    static Annotation[] extractFieldQualifiers(Field field) {
        io.vidocq.vauban.core.annotation.AnnotationReflection.checkField(field);
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
