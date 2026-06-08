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

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.WildcardType;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class VaubanWildcardType implements WildcardType {

    private final Type upperBound;
    private final Type lowerBound;

    public VaubanWildcardType(Type upperBound, Type lowerBound) {
        this.upperBound = upperBound;
        this.lowerBound = lowerBound;
    }

    @Override
    public Type upperBound() {
        return upperBound;
    }

    @Override
    public Type lowerBound() {
        return lowerBound;
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return false;
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return false;
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return null;
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return List.of();
    }

    @Override
    public String toString() {
        if (lowerBound != null) {
            return "? super " + lowerBound;
        }
        if (upperBound != null) {
            return "? extends " + upperBound;
        }
        return "?";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaubanWildcardType that)) return false;
        return Objects.equals(upperBound, that.upperBound) && Objects.equals(lowerBound, that.lowerBound);
    }

    @Override
    public int hashCode() {
        return Objects.hash(upperBound, lowerBound);
    }
}
