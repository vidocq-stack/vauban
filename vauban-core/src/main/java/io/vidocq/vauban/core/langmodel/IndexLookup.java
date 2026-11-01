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

import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Provides index lookup for lang model implementations.
 */
@SuppressWarnings("java:S6206") // Cannot be a record: provides mutable lookup behavior
public final class IndexLookup {

    /**
     * Describes, while Vauban's annotation processor runs extensions, a class the index does not hold: the processor
     * sees every class of the compilation and of its class path, the index only the beans and the classes a trigger
     * annotation brings in (VAU-BCE-008). Unbound at run time.
     */
    private static final ScopedValue<Function<DotName, Optional<ClassInfo>>> UNINDEXED = ScopedValue.newInstance();

    private final VaubanIndex index;

    public IndexLookup(VaubanIndex index) {
        this.index = Objects.requireNonNull(index);
    }

    public VaubanIndex index() {
        return index;
    }

    public Optional<ClassInfo> getClass(DotName name) {
        return index.getClassByName(name);
    }

    /**
     * The class as the index holds it or, when it does not and the caller runs under
     * {@link #whereUnindexedDescribedBy}, as that resolver describes it: for the language model only, which shows a
     * class's members to an extension; never a reason to treat the class as a bean.
     */
    public Optional<ClassInfo> describe(DotName name) {
        var indexed = index.getClassByName(name);
        return indexed.isPresent() || !UNINDEXED.isBound() ? indexed : UNINDEXED.get().apply(name);
    }

    /**
     * Runs {@code operation} with {@code resolver} describing the classes the index does not hold, for
     * {@link #describe}. For Vauban's annotation processor, around the extension phases it runs.
     */
    public static <R, X extends Throwable> R whereUnindexedDescribedBy(
            Function<DotName, Optional<ClassInfo>> resolver, ScopedValue.CallableOp<? extends R, X> operation)
            throws X {
        return ScopedValue.where(UNINDEXED, Objects.requireNonNull(resolver)).call(operation);
    }

    public ClassInfo requireClass(DotName name) {
        return index.getClassByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Class not found in index: " + name));
    }
}
