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

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * VAU-PERF-006 fallback: these classes are compiled without the Vauban processor, so no generated provider
 * owns them (as under Weld/Open Liberty, or when the processor did not run). Their private {@code @Inject}
 * fields are still injected, by the container's reflective path, unchanged.
 */
@DisplayName("VAU-PERF-006 - private @Inject fields without a generated provider")
class PrivateFieldFallbackTest {

    @Dependent
    public static class Dependency {}

    @Dependent
    public static class Owner {
        @Inject private Dependency dependency;
        @Inject private int port;

        Dependency dependency() { return dependency; }
        int port() { return port; }
    }

    @Dependent
    public static class Port {
        @jakarta.enterprise.inject.Produces int port() { return 8080; }
    }

    @Test
    @DisplayName("private fields are injected by the reflective path")
    void privateFieldsAreInjectedWithoutAProvider() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Dependency.class)
                .addBeanClass(Owner.class)
                .addBeanClass(Port.class)
                .build()) {
            var owner = container.select(Owner.class);
            assertNotNull(owner.dependency());
            assertEquals(8080, owner.port());
        }
    }
}
