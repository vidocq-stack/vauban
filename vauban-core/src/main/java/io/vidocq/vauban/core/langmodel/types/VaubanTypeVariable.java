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
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.util.List;
import java.util.Objects;

/**
 * A type variable of the language model.
 *
 * @param annotations the annotations on this use of the type variable, or on the type parameter it declares
 *                    (BUG-20261117-60); they take no part in its equality
 */
public record VaubanTypeVariable(String name, List<Type> bounds, List<AnnotationInfo> annotations)
        implements TypeVariable, AnnotatedTypeUse {

    public VaubanTypeVariable {
        Objects.requireNonNull(name);
        bounds = List.copyOf(bounds);
        annotations = List.copyOf(annotations);
    }

    /** A type variable without annotations. */
    public VaubanTypeVariable(String name, List<Type> bounds) {
        this(name, bounds, List.of());
    }

    @Override
    public String toString() {
        if (bounds.isEmpty()) {
            return name;
        }
        var sb = new StringBuilder(name);
        sb.append(" extends ");
        for (int i = 0; i < bounds.size(); i++) {
            if (i > 0) sb.append(" & ");
            sb.append(bounds.get(i));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return this == o
                || (o instanceof VaubanTypeVariable(var otherName, var otherBounds, _)
                        && name.equals(otherName) && bounds.equals(otherBounds));
    }

    // The hash the class had before it became a record: same values, same iteration orders.
    @Override
    public int hashCode() {
        return Objects.hash(name, bounds);
    }
}
