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
package io.vidocq.vauban.core.langmodel.types;

import io.vidocq.vauban.core.langmodel.RepeatableAnnotations;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.function.Predicate;

/**
 * A type of the language model and the annotations on it, where the type is used (BUG-20261117-60): every
 * annotation query reads {@link #annotations()}. The annotations do not take part in the equality of the types: the
 * same type, annotated or not, is one type, as a bean type or a type the container resolves is.
 */
interface AnnotatedTypeUse extends Type {

    @Override
    Collection<AnnotationInfo> annotations();

    @Override
    default boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return annotation(annotationType) != null;
    }

    @Override
    default boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    default <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        var name = annotationType.getName();
        return annotations().stream().filter(annotation -> annotation.name().equals(name)).findFirst().orElse(null);
    }

    @Override
    default <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return RepeatableAnnotations.of(annotations(), annotationType);
    }

    @Override
    default Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }
}
