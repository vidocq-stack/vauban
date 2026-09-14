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


import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
