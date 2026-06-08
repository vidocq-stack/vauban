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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ComponentProviders}: vauban-core consults the module-supplied
 * {@link VaubanComponentProvider}s before falling back to reflective instantiation.
 */
@DisplayName("ComponentProviders")
class ComponentProvidersTest {

    static class A {}
    static class B {}

    @Test
    @DisplayName("returns an instance from the provider that owns the class, null otherwise")
    void returnsInstanceFromOwningProvider() {
        VaubanComponentProvider p = name -> name.equals(A.class.getName()) ? new A() : null;
        var providers = new ComponentProviders(List.of(p));

        assertInstanceOf(A.class, providers.create(A.class.getName()));
        assertNull(providers.create(B.class.getName()),
                "unowned class must return null so the caller can fall back to reflection");
    }

    @Test
    @DisplayName("first owning provider wins")
    void firstOwningProviderWins() {
        VaubanComponentProvider p1 = name -> null;
        VaubanComponentProvider p2 = name -> name.equals(A.class.getName()) ? new A() : null;
        var providers = new ComponentProviders(List.of(p1, p2));

        assertInstanceOf(A.class, providers.create(A.class.getName()));
    }

    @Test
    @DisplayName("a throwing provider does not break resolution — the next one is tried")
    void misbehavingProviderIsSkipped() {
        VaubanComponentProvider boom = name -> { throw new RuntimeException("boom"); };
        VaubanComponentProvider ok = name -> new A();
        var providers = new ComponentProviders(List.of(boom, ok));

        assertInstanceOf(A.class, providers.create(A.class.getName()));
    }

    @Test
    @DisplayName("no providers → empty, every lookup is null")
    void emptyWhenNoProviders() {
        var providers = new ComponentProviders(List.of());

        assertTrue(providers.isEmpty());
        assertNull(providers.create(A.class.getName()));
    }

    @Test
    @DisplayName("load() includes explicitly-registered providers (consulted before reflection)")
    void loadIncludesExplicitProviders() {
        VaubanComponentProvider p = name -> name.equals(A.class.getName()) ? new A() : null;
        var providers = ComponentProviders.load(getClass().getClassLoader(), List.of(p));

        assertInstanceOf(A.class, providers.create(A.class.getName()),
                "an explicitly-registered provider must be consulted");
    }
}
