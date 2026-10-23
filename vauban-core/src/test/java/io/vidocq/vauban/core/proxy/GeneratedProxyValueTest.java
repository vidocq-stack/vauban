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

import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator.GeneratedProxy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** A {@link GeneratedProxy} compares by name and bytes content. */
class GeneratedProxyValueTest {

    private static final String NAME = "com.acme.Bean_ClientProxy";

    @Test
    void equalNamesAndBytesMakeEqualProxies() {
        var proxy = new GeneratedProxy(NAME, new byte[] {4, 2});

        assertEquals(new GeneratedProxy(NAME, new byte[] {4, 2}), proxy);
        assertEquals(new GeneratedProxy(NAME, new byte[] {4, 2}).hashCode(), proxy.hashCode());
        assertNotEquals(new GeneratedProxy(NAME, new byte[] {4}), proxy);
        assertNotEquals(new GeneratedProxy("com.acme.Other_ClientProxy", new byte[] {4, 2}), proxy);
        Object aString = NAME;
        assertNotEquals(proxy, aString);
    }
}
