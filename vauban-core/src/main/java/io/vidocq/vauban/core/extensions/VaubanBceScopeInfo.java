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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

/**
 * BCE ScopeInfo adapter for Vauban's internal ScopeInfo.
 */
public final class VaubanBceScopeInfo implements jakarta.enterprise.inject.build.compatible.spi.ScopeInfo {

    private final io.vidocq.vauban.core.bean.model.ScopeInfo scope;
    private final IndexLookup lookup;

    public VaubanBceScopeInfo(io.vidocq.vauban.core.bean.model.ScopeInfo scope, IndexLookup lookup) {
        this.scope = scope;
        this.lookup = lookup;
    }

    /**
     * The scope annotation's declaration. A scope from a spec jar (every built-in one, at build time)
     * is not in the index: the declaration is then the stub a class type gives for a class outside the
     * index, which names it. It used to be {@code null}, so {@code name()} threw in {@code @Registration}.
     */
    @Override
    public ClassInfo annotation() {
        return new io.vidocq.vauban.core.langmodel.types.VaubanClassType(scope.annotationName(), lookup).declaration();
    }

    @Override
    public String name() {
        return scope.annotationName().value();
    }

    @Override
    public boolean isNormal() {
        return scope.isNormal();
    }
}
