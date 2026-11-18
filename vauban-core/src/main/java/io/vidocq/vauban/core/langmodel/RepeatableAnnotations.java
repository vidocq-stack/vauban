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
package io.vidocq.vauban.core.langmodel;

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.lang.annotation.Repeatable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What {@code AnnotationTarget.repeatableAnnotation} gives, from the annotations a target shows: those of the
 * repeatable type, and those the {@code value} of its container holds, as {@code getAnnotationsByType} gives them
 * (BUG-20261117-56). The compiler wraps an annotation written more than once in its container, so the target shows the
 * container alone.
 */
public final class RepeatableAnnotations {

    private RepeatableAnnotations() {
    }

    /**
     * The annotations of {@code type} among {@code present}, and those each container of {@code type} among them
     * holds, in the order the target shows them.
     */
    public static Collection<AnnotationInfo> of(Collection<AnnotationInfo> present, Class<?> type) {
        var name = type.getName();
        var result = new ArrayList<AnnotationInfo>();
        for (var annotation : present) {
            if (annotation.name().equals(name)) {
                result.add(annotation);
            } else {
                result.addAll(contained(annotation, name));
            }
        }
        return List.copyOf(result);
    }

    /** The annotations of {@code type} that {@code annotation} holds, when it is the container of {@code type}. */
    private static List<AnnotationInfo> contained(AnnotationInfo annotation, String type) {
        var value = annotation.value();
        if (value == null || !value.isArray()) {
            return List.of();
        }
        var held = value.asArray().stream().filter(AnnotationMember::isNestedAnnotation)
                .map(AnnotationMember::asNestedAnnotation).filter(nested -> nested.name().equals(type)).toList();
        return held.isEmpty() || !isContainerOf(annotation, held.getFirst()) ? List.of() : held;
    }

    /**
     * Whether {@code repeatable}'s type names {@code container}'s in its {@code @Repeatable}: another annotation whose
     * {@code value} is an array of the type is no container, nor is any for a type that is not repeatable. A type
     * nothing describes is taken at its word.
     */
    private static boolean isContainerOf(AnnotationInfo container, AnnotationInfo repeatable) {
        try {
            var repeatableOf = repeatable.declaration().annotation(Repeatable.class);
            return repeatableOf != null
                    && repeatableOf.value().asType().asClass().declaration().name().equals(container.name());
        } catch (IllegalArgumentException _) {
            // AnnotationInfo.declaration() throws for an annotation type nothing describes (BUG-20261117-31).
            return true;
        }
    }
}
