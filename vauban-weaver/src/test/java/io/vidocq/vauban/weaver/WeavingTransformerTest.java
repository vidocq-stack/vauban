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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WeavingTransformer — plan-driven, definition-time only, never breaks loading")
class WeavingTransformerTest {

    private static final String BEAN = "com.example.Fixture";
    private static final String PROXY = "com.example.Fixture_ClientProxy";

    private static WeavingTransformer transformer() {
        return new WeavingTransformer(new WeavingPlan(
                Map.of(BEAN, ProxyLinkWeaver.SuperChain.NO_ARG), Set.of(PROXY)));
    }

    @Test
    @DisplayName("a planned bean gains the (ProxyLink) marker constructor")
    void weavesPlannedBean() {
        var woven = transformer().transform(null, null, "com/example/Fixture", null, null,
                Fixtures.beanWithBusinessCtorOnly(BEAN));
        assertNotNull(woven);
        assertTrue(ProxyLinkWeaver.hasMarkerConstructor(woven));
    }

    @Test
    @DisplayName("an already-woven planned bean is left unchanged (null)")
    void alreadyWovenIsUntouched() {
        var woven = ProxyLinkWeaver.addMarkerConstructor(
                Fixtures.beanWithBusinessCtorOnly(BEAN), ProxyLinkWeaver.SuperChain.NO_ARG);
        assertNull(transformer().transform(null, null, "com/example/Fixture", null, null, woven));
    }

    @Test
    @DisplayName("a planned proxy is retargeted to super((ProxyLink) null)")
    void retargetsPlannedProxy() {
        var retargeted = transformer().transform(null, null, "com/example/Fixture_ClientProxy",
                null, null, Fixtures.proxyChainingBusinessCtor(PROXY, BEAN));
        assertNotNull(retargeted);
        assertTrue(Fixtures.constructorChainsToMarker(retargeted, BEAN));
    }

    @Test
    @DisplayName("classes outside the plan and redefinitions are ignored")
    void ignoresOutOfPlanAndRedefinitions() {
        var t = transformer();
        assertNull(t.transform(null, null, "com/example/Other", null, null,
                Fixtures.beanWithBusinessCtorOnly("com.example.Other")));
        assertNull(t.transform(null, null, null, null, null, new byte[0]));
        assertNull(t.transform((Module) null, null, "com/example/Fixture", String.class, null,
                Fixtures.beanWithBusinessCtorOnly(BEAN)));
    }

    @Test
    @DisplayName("a weaving failure reports and leaves the class unchanged (null)")
    void failureLeavesClassUnchanged() {
        assertNull(transformer().transform(null, null, "com/example/Fixture", null, null,
                new byte[] {1, 2, 3}));
    }
}
