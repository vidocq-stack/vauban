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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.api.VaubanComponentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * vauban#101: the provider a module's test compilation emits, {@code _VaubanComponentsTests}, is found through the
 * lookup the main {@code _VaubanComponents} of its package grants, and consulted next to it.
 */
@DisplayName("vauban#101: the test round's provider is picked up next to the main one")
class TestCompanionProviderTest {

    private static List<String> names(ComponentProviders providers) {
        return providers.providers().stream().map(p -> p.getClass().getName()).toList();
    }

    @Test
    @DisplayName("a _VaubanComponentsTests next to the main provider is added after it")
    void theTestCompanionIsAdded() {
        var providers = ComponentProviders.load(getClass().getClassLoader(),
                List.of(new io.vidocq.vauban.core.container.companion._VaubanComponents()));

        List<String> names = names(providers);
        int main = names.indexOf("io.vidocq.vauban.core.container.companion._VaubanComponents");
        int tests = names.indexOf("io.vidocq.vauban.core.container.companion._VaubanComponentsTests");
        assertEquals(true, main >= 0 && tests == main + 1, names.toString());
        Object created = providers.providers().get(tests).create("test-fixture");
        assertEquals("created by the test round", created);
    }

    @Test
    @DisplayName("a main provider with no test round gets no companion")
    void noCompanionWithoutATestRound() {
        var providers = ComponentProviders.load(getClass().getClassLoader(),
                List.of(new io.vidocq.vauban.core.container.solo._VaubanComponents()));

        assertEquals(0, names(providers).stream().filter(n -> n.endsWith("_VaubanComponentsTests")).count(),
                names(providers).toString());
    }

    @Test
    @DisplayName("the companion is added once, whoever loads the main provider twice")
    void theCompanionIsAddedOnce() {
        VaubanComponentProvider main = new io.vidocq.vauban.core.container.companion._VaubanComponents();
        var providers = ComponentProviders.load(getClass().getClassLoader(), List.of(main, main));

        assertEquals(1, names(providers).stream().filter(n -> n.endsWith("_VaubanComponentsTests")).count(),
                names(providers).toString());
    }
}
