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
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A type variable met again inside its own bounds, {@code T} in {@code <T extends Comparable<T>>}: the same type
 * variable as the one whose bounds are being mapped, its bounds mapped when asked, so that the model stays finite
 * (BUG-20261117-33).
 * <p>
 * It equals the type variable it stands for. Two references are equal when they name the same variable with the same
 * declared bounds, compared as the index records them, which ends where comparing mapped bounds would not; and it shows
 * as its name alone.
 */
final class VaubanTypeVariableReference implements TypeVariable, AnnotatedTypeUse {

    private final String name;
    private final List<TypeInfo> declared;
    private final Supplier<List<Type>> bounds;
    private final List<AnnotationInfo> annotations;

    VaubanTypeVariableReference(String name, List<TypeInfo> declared, Supplier<List<Type>> bounds,
            List<AnnotationInfo> annotations) {
        this.name = Objects.requireNonNull(name);
        this.declared = List.copyOf(declared);
        this.bounds = Objects.requireNonNull(bounds);
        this.annotations = List.copyOf(annotations);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Type> bounds() {
        return bounds.get();
    }

    @Override
    public List<AnnotationInfo> annotations() {
        return annotations;
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Equal to a reference to the same variable with the same declared bounds, and to a {@link VaubanTypeVariable} of
     * the same name with the same bounds, compared through the {@link TypeVariable} they both are.
     */
    @Override
    public boolean equals(Object o) {
        return this == o
                || (o instanceof VaubanTypeVariableReference other && name.equals(other.name)
                        && declared.equals(other.declared))
                || (o instanceof VaubanTypeVariable variable && name.equals(variable.name())
                        && bounds().equals(variable.bounds()));
    }

    // As VaubanTypeVariable's: a type variable hashes by its name, which equal ones share.
    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
