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
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Step;
import jakarta.enterprise.lang.model.AnnotationInfo;

import java.util.List;
import java.util.Objects;

/**
 * Where a type is used, for its annotations (BUG-20261117-60): the type annotations of the index found inside the
 * type, each with its path from the type, and the type parameters in scope, whose declarations annotate the bounds of
 * the type variables the type uses ({@code T extends @A Number} shows {@code @A Number} wherever {@code T} is
 * used). {@link TypeMapper} walks a type and its use together.
 */
public final class TypeUse {

    /** A use without annotations: an extension's own type, or a bean type. */
    public static final TypeUse NONE = new TypeUse(List.of(), TypeParameterScope.EMPTY);

    private final List<TypeAnnotationInfo> annotations;
    private final TypeParameterScope scope;

    TypeUse(List<TypeAnnotationInfo> annotations, TypeParameterScope scope) {
        this.annotations = annotations;
        this.scope = Objects.requireNonNull(scope);
    }

    /** The annotations on the type itself, as the language model shows them. */
    List<AnnotationInfo> annotations(IndexLookup lookup) {
        return annotations.stream().filter(annotation -> annotation.path().isEmpty())
                .<AnnotationInfo>map(annotation -> new VaubanAnnotationInfo(annotation.annotation(), lookup))
                .toList();
    }

    /** The use of the type argument at {@code index}. */
    TypeUse typeArgument(int index) {
        return after(Step.typeArgument(index));
    }

    /** The use of the component of an array type. */
    TypeUse component() {
        return after(Step.ARRAY);
    }

    /** The use of the bound of a wildcard. */
    TypeUse wildcardBound() {
        return after(Step.WILDCARD);
    }

    /** The bound at {@code position} of the type variable {@code name}, as the type parameter in scope declares it. */
    TypeUse bound(String name, int position) {
        return scope.bound(name, position);
    }

    private TypeUse after(Step step) {
        if (annotations.isEmpty()) {
            return this;
        }
        return new TypeUse(annotations.stream().map(annotation -> annotation.after(step)).filter(Objects::nonNull)
                .toList(), scope);
    }
}
