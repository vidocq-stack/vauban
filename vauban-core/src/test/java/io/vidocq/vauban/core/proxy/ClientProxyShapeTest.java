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
package io.vidocq.vauban.core.proxy;

import io.vidocq.vauban.core.interceptor.TypeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The client-proxy naming decisions ({@code _ClientProxy} suffix, {@code $$delegate},
 * {@code $$mh_<name>_<n>} field sequence) live on the shared {@link ClientProxyShape} IR —
 * the single authority for the three front-ends (runtime {@code Class}, indexer
 * {@code ClassInfo}, APT {@code TypeElement}).
 */
@DisplayName("ClientProxyShape — shared naming authority for the proxy generators")
class ClientProxyShapeTest {

    private static ClientProxyShape.ProxyMethodShape method(String name, boolean needsMh) {
        return new ClientProxyShape.ProxyMethodShape(
                name, TypeRef.ofVoid(), List.of(), List.of(), needsMh);
    }

    private static ClientProxyShape shape(ClientProxyShape.ProxyMethodShape... methods) {
        return new ClientProxyShape("com.example.MyService", List.of(), Arrays.asList(methods));
    }

    @Test
    @DisplayName("proxyClassName appends the _ClientProxy suffix to the bean binary name")
    void proxyClassName() {
        assertEquals("com.example.MyService_ClientProxy", shape().proxyClassName());
    }

    @Test
    @DisplayName("methodHandleFieldNames: $$mh_<name>_<n>, the counter only advances on MH methods")
    void methodHandleFieldNames() {
        var s = shape(
                method("plain", false),
                method("prot", true),
                method("other", false),
                method("pkg", true));
        var names = s.methodHandleFieldNames();
        assertNull(names.get(0));
        assertEquals("$$mh_prot_0", names.get(1));
        assertNull(names.get(2));
        assertEquals("$$mh_pkg_1", names.get(3));
    }

    @Test
    @DisplayName("no MH methods — every field name is null and hasMethodHandles() is false")
    void noMethodHandles() {
        var s = shape(method("a", false), method("b", false));
        assertEquals(Arrays.asList(null, null), s.methodHandleFieldNames());
        assertEquals(false, s.hasMethodHandles());
    }
}
