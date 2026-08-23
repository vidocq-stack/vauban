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
package io.vidocq.vauban.weaver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused checks of the moved canonical weaver; the full behavioural matrix (loading the
 * woven bytes, @Inject beans, super chains) lives in the vauban-maven-plugin's
 * ProxyLinkWeavingTest, which exercises this same implementation through its orchestration.
 */
@DisplayName("ProxyLinkWeaver — marker weaving and proxy retargeting primitives")
class ProxyLinkWeaverTest {

    private static final String BEAN = "com.example.Fixture";

    @Test
    @DisplayName("addMarkerConstructor weaves once, then reports the marker and returns null")
    void weaveThenIdempotent() {
        var bytes = Fixtures.beanWithBusinessCtorOnly(BEAN);
        assertFalse(ProxyLinkWeaver.hasMarkerConstructor(bytes));

        var woven = ProxyLinkWeaver.addMarkerConstructor(bytes, ProxyLinkWeaver.SuperChain.NO_ARG);
        assertNotNull(woven);
        assertTrue(ProxyLinkWeaver.hasMarkerConstructor(woven));
        assertNull(ProxyLinkWeaver.addMarkerConstructor(woven, ProxyLinkWeaver.SuperChain.NO_ARG));
    }

    @Test
    @DisplayName("hasNonPrivateNoArgConstructor sees through access flags")
    void noArgDetection() {
        assertFalse(ProxyLinkWeaver.hasNonPrivateNoArgConstructor(
                Fixtures.beanWithBusinessCtorOnly(BEAN)));
        // The generated business-ctor proxy fixture has a public no-arg <init>
        assertTrue(ProxyLinkWeaver.hasNonPrivateNoArgConstructor(
                Fixtures.proxyChainingBusinessCtor(BEAN + "_ClientProxy", BEAN)));
    }

    @Test
    @DisplayName("retargetProxyConstructor rewrites the chain to super((ProxyLink) null)")
    void retarget() {
        var proxy = Fixtures.proxyChainingBusinessCtor(BEAN + "_ClientProxy", BEAN);
        assertFalse(Fixtures.constructorChainsToMarker(proxy, BEAN));

        var retargeted = ProxyLinkWeaver.retargetProxyConstructor(proxy);
        assertTrue(Fixtures.constructorChainsToMarker(retargeted, BEAN));
        // Idempotent: retargeting again yields the same chain
        assertTrue(Fixtures.constructorChainsToMarker(
                ProxyLinkWeaver.retargetProxyConstructor(retargeted), BEAN));
    }
}
