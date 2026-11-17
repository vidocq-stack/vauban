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
package io.vidocq.vauban.core.codegen;

import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The parameterized classes and interfaces above a class or a parameterized type, as Vauban's annotation processor
 * reads them from the compilation, for the bean types of a bean or a producer. The container reads them by reflection
 * at start ({@code TypeHierarchyResolver}); the index keeps a class's supertypes by name only, so without them the
 * processor's bean types were raw, and a raw bean type matches every parameterization (BUG-20261117-14). While the
 * processor runs, its reading comes first: a class reflection could load there may be another copy than the one the
 * compilation compiles. Unbound at run time. The package is exported to the processor only.
 */
public final class CompiledSupertypes {

    private static final ScopedValue<Function<TypeInfo, Optional<List<TypeInfo>>>> PARAMETERIZED =
            ScopedValue.newInstance();

    private CompiledSupertypes() {
    }

    /**
     * Runs {@code operation} with {@code resolver} giving the parameterized classes and interfaces above a class or a
     * parameterized type, its type variables resolved along the way, or nothing for a type it does not know.
     */
    public static <R, X extends Throwable> R where(Function<TypeInfo, Optional<List<TypeInfo>>> resolver,
            ScopedValue.CallableOp<? extends R, X> operation) throws X {
        return ScopedValue.where(PARAMETERIZED, Objects.requireNonNull(resolver)).call(operation);
    }

    /**
     * The parameterized classes and interfaces above {@code type}, a class or a parameterized type, as the processor
     * reads them; empty when nothing is bound, at start say, or when the processor does not know the type.
     */
    public static Optional<List<TypeInfo>> of(TypeInfo type) {
        return PARAMETERIZED.isBound() ? PARAMETERIZED.get().apply(type) : Optional.empty();
    }
}
