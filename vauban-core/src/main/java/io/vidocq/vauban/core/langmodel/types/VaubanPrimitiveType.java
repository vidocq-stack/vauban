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

import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.PrimitiveType;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class VaubanPrimitiveType implements PrimitiveType {

    private final PrimitiveKind primitiveKind;

    public VaubanPrimitiveType(PrimitiveKind primitiveKind) {
        this.primitiveKind = Objects.requireNonNull(primitiveKind);
    }

    public VaubanPrimitiveType(TypeInfo.PrimitiveType indexType) {
        this(mapKind(indexType.kind()));
    }

    @Override
    public String name() {
        return primitiveKind.name().toLowerCase();
    }

    @Override
    public PrimitiveKind primitiveKind() {
        return primitiveKind;
    }

    private static PrimitiveKind mapKind(TypeInfo.PrimitiveType.Kind kind) {
        return switch (kind) {
            case BOOLEAN -> PrimitiveKind.BOOLEAN;
            case BYTE -> PrimitiveKind.BYTE;
            case CHAR -> PrimitiveKind.CHAR;
            case SHORT -> PrimitiveKind.SHORT;
            case INT -> PrimitiveKind.INT;
            case LONG -> PrimitiveKind.LONG;
            case FLOAT -> PrimitiveKind.FLOAT;
            case DOUBLE -> PrimitiveKind.DOUBLE;
        };
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
        return name();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaubanPrimitiveType that)) return false;
        return primitiveKind == that.primitiveKind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(primitiveKind);
    }
}
