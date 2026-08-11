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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WeavingPlan — text hand-off between container detection and the agent")
class WeavingPlanTest {

    @Test
    @DisplayName("render/parse round-trips beans (with chain) and proxies")
    void roundTrip(@TempDir Path dir) throws IOException {
        var plan = new WeavingPlan(
                Map.of("com.example.Stats", ProxyLinkWeaver.SuperChain.NO_ARG,
                        "com.example.Child", ProxyLinkWeaver.SuperChain.MARKER),
                Set.of("com.example.Stats_ClientProxy"));

        var file = dir.resolve("plan.txt");
        plan.writeTo(file);
        var back = WeavingPlan.load(file);

        assertEquals(plan.beans(), back.beans());
        assertEquals(plan.proxies(), back.proxies());
        assertFalse(back.isEmpty());
    }

    @Test
    @DisplayName("blank lines are ignored, malformed directives are rejected")
    void malformed() {
        assertTrue(WeavingPlan.parse("\n  \n").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> WeavingPlan.parse("BEAN com.example.A"));
        assertThrows(IllegalArgumentException.class, () -> WeavingPlan.parse("PROXY a b"));
        assertThrows(IllegalArgumentException.class, () -> WeavingPlan.parse("WEAVE com.example.A"));
        assertThrows(IllegalArgumentException.class, () -> WeavingPlan.parse("BEAN com.example.A SIDEWAYS"));
    }
}
