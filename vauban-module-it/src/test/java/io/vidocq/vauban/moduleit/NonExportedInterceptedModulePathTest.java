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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.moduleit.internal.InternalAuditedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * BUG-20261010-02: an intercepted bean in a package that the module neither exports nor opens.
 * The {@code $$Intercepted} subclass is created through the package's generated provider; its
 * post-construction wiring must not need the package exported to {@code io.vidocq.vauban.core}
 * either.
 */
@DisplayName("Intercepted bean in a non-exported package — module path")
class NonExportedInterceptedModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.CALLS.clear();
    }

    @Test
    @DisplayName("is created, wired and intercepted with no exports and no opens")
    void interceptedWithoutExports() {
        Module module = InternalAuditedService.class.getModule();
        assertFalse(module.isExported(InternalAuditedService.class.getPackageName()),
                "the package must not be exported for this test to mean anything");

        try (var container = VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(InternalAuditedService.class)
                .build()) {
            var bean = container.select(InternalAuditedService.class);
            assertEquals(InternalAuditedService.class.getName() + "$$Intercepted", bean.getClass().getName());
            assertEquals("hello from internal", bean.greet());
            assertEquals(List.of("greet/0"), AuditInterceptor.CALLS);
        }
    }
}
