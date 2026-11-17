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

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;

import java.util.List;
import java.util.Objects;

public final class VaubanParameterizedType implements ParameterizedType, AnnotatedTypeUse {

    private final VaubanClassType genericClass;
    private final List<Type> typeArguments;
    /** The annotations on this use of the type (BUG-20261117-60); they take no part in its equality. */
    private final List<AnnotationInfo> annotations;

    public VaubanParameterizedType(DotName rawType, List<Type> typeArguments, IndexLookup lookup) {
        this(rawType, typeArguments, lookup, List.of());
    }

    /** The type {@code rawType<typeArguments>}, used with {@code annotations} on it (BUG-20261117-60). */
    public VaubanParameterizedType(DotName rawType, List<Type> typeArguments, IndexLookup lookup,
            List<AnnotationInfo> annotations) {
        this.genericClass = new VaubanClassType(rawType, lookup);
        this.typeArguments = List.copyOf(typeArguments);
        this.annotations = List.copyOf(annotations);
    }

    @Override
    public List<AnnotationInfo> annotations() {
        return annotations;
    }

    @Override
    public ClassType genericClass() {
        return genericClass;
    }

    @Override
    public List<Type> typeArguments() {
        return typeArguments;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(genericClass.toString());
        sb.append('<');
        for (int i = 0; i < typeArguments.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeArguments.get(i));
        }
        sb.append('>');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaubanParameterizedType that)) return false;
        return genericClass.equals(that.genericClass) && typeArguments.equals(that.typeArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(genericClass, typeArguments);
    }
}
