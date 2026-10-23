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
package io.vidocq.vauban.core.interceptor;

import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link InterceptorManager#$$callIntercepted}: what the action returns or throws comes out unchanged. */
@DisplayName("InterceptorManager.$$callIntercepted")
class CallInterceptedTest {

    private static final Set<DotName> NO_BINDINGS = Set.of();

    @Test
    @DisplayName("returns the action's result, with the interception bound while it runs")
    void returnsTheResult() throws Exception {
        var result = InterceptorManager.$$callIntercepted(List.of(), NO_BINDINGS, null,
                () -> InterceptorManager.$$isIntercepting() ? "bound" : "unbound");

        assertEquals("bound", result);
    }

    @Test
    @DisplayName("propagates a checked exception unchanged")
    void propagatesACheckedException() {
        var failure = new IOException("checked");

        var thrown = assertThrows(IOException.class, () -> callThrowing(failure));

        assertSame(failure, thrown);
    }

    @Test
    @DisplayName("propagates an Error unchanged, as $$runIntercepted does (VAU-Q5-001)")
    void propagatesAnErrorUnchanged() {
        // A LinkageError: an Error the test framework never raises itself.
        var error = new LinkageError("probe");

        var thrown = assertThrows(LinkageError.class, () -> callThrowing(error));

        assertSame(error, thrown);
    }

    private static void callThrowing(Throwable failure) throws Exception {
        InterceptorManager.$$callIntercepted(List.of(), NO_BINDINGS, null, () -> {
            if (failure instanceof Error error) {
                throw error;
            }
            throw (Exception) failure;
        });
    }
}
