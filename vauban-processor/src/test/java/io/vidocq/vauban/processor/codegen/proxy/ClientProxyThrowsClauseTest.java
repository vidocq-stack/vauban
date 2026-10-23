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
package io.vidocq.vauban.processor.codegen.proxy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.interceptor.TypeRef;
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A forwarded method keeps every checked exception its bean method declares. */
class ClientProxyThrowsClauseTest {

    @Test
    void everyDeclaredExceptionIsKeptInOrder() {
        var method = new ClientProxyShape.ProxyMethodShape("load", TypeRef.ofVoid(), List.of(),
                List.of(TypeRef.ofReference("java.io.IOException"),
                        TypeRef.ofReference("java.util.concurrent.TimeoutException")), false);
        var shape = new ClientProxyShape("app.Store", List.of(), List.of(method));

        var source = ClientProxySourceRenderer.render(shape).source();

        assertTrue(source.contains(" throws java.io.IOException, java.util.concurrent.TimeoutException"), source);
    }
}
