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
package io.vidocq.vauban.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("GeneratedCoverage")
class GeneratedCoverageTest {

    @Test
    @DisplayName("of() builds the sets a generated provider declares, a repeated key kept once")
    void ofBuildsTheDeclaredSets() {
        var coverage = GeneratedCoverage.of(GeneratedCoverage.Generator.APT,
                new String[] {"app.Foo", "app.Foo"}, new String[] {"app.Foo#repo"},
                new String[] {"app.Foo#init()"}, new String[] {"app.Foo_ClientProxy"});

        assertEquals(GeneratedCoverage.Generator.APT, coverage.generator());
        assertEquals(Set.of("app.Foo"), coverage.instantiated());
        assertEquals(Set.of("app.Foo#repo"), coverage.injectedFields());
        assertEquals(Set.of("app.Foo#init()"), coverage.invokedMethods());
        assertEquals(Set.of("app.Foo_ClientProxy"), coverage.clientProxies());
    }

    @Test
    @DisplayName("a coverage names its generator")
    void generatorIsRequired() {
        assertThrows(NullPointerException.class,
                () -> new GeneratedCoverage(null, Set.of(), Set.of(), Set.of(), Set.of()));
    }

    @Test
    @DisplayName("a provider that predates coverage() declares nothing")
    void providerDefaultIsNull() {
        VaubanComponentProvider provider = className -> null;
        assertNull(provider.coverage());
    }
}
