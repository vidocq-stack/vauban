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

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class VaubanPrimitiveType implements PrimitiveType, AnnotatedTypeUse {

    private final PrimitiveKind primitiveKind;
    /** The annotations on this use of the type (BUG-20261117-60); they take no part in its equality. */
    private final List<AnnotationInfo> annotations;

    public VaubanPrimitiveType(PrimitiveKind primitiveKind) {
        this(primitiveKind, List.of());
    }

    public VaubanPrimitiveType(PrimitiveKind primitiveKind, List<AnnotationInfo> annotations) {
        this.primitiveKind = Objects.requireNonNull(primitiveKind);
        this.annotations = List.copyOf(annotations);
    }

    public VaubanPrimitiveType(TypeInfo.PrimitiveType indexType) {
        this(indexType, List.of());
    }

    public VaubanPrimitiveType(TypeInfo.PrimitiveType indexType, List<AnnotationInfo> annotations) {
        this(mapKind(indexType.kind()), annotations);
    }

    @Override
    public List<AnnotationInfo> annotations() {
        return annotations;
    }

    @Override
    public String name() {
        // Whatever the default locale: a Turkish one lowers INT to "ınt", with a dotless i (BUG-20261117-20)
        return primitiveKind.name().toLowerCase(Locale.ROOT);
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
