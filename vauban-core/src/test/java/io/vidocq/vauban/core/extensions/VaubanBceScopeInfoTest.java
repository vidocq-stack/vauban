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

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.IndexBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanBceScopeInfo - a scope outside the index")
class VaubanBceScopeInfoTest {

    @Test
    @DisplayName("a built-in scope, absent from the index at build time, still has a name and a declaration")
    void scopeOutsideTheIndex() {
        var scope = new VaubanBceScopeInfo(ScopeInfo.DEPENDENT, new IndexLookup(new IndexBuilder().build()));
        assertEquals("jakarta.enterprise.context.Dependent", scope.name());
        assertEquals("jakarta.enterprise.context.Dependent", scope.annotation().name());
        assertFalse(scope.isNormal());
    }
}
