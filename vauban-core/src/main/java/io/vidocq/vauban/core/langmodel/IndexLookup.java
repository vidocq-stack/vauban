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

/**
 * Provides index lookup for lang model implementations.
 */
@SuppressWarnings("java:S6206") // Cannot be a record: provides mutable lookup behavior
public final class IndexLookup {

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

    public ClassInfo requireClass(DotName name) {
        return index.getClassByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Class not found in index: " + name));
    }
}
