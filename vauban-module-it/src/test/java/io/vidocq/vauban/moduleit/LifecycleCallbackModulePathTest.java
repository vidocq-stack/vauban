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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The processor's intercepted subclass leaves lifecycle callbacks alone, so an {@code @AroundInvoke}
 * interceptor never sees them (VAU-INT-006, vauban#114). Module path, no opens.
 */
@DisplayName("Lifecycle callbacks are not business methods, module path (VAU-INT-006)")
class LifecycleCallbackModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.CALLS.clear();
        AuditInterceptor.METHODS.clear();
        LifecycleAuditedService.CALLBACKS.clear();
    }

    @Test
    @DisplayName("@AroundInvoke sees work() only; init() and dispose() still run")
    void aroundInvokeSkipsLifecycleCallbacks() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(LifecycleAuditedService.class)
                .build()) {
            assertEquals("done", container.select(LifecycleAuditedService.class).work());
        }
        assertEquals(List.of("work/0"), AuditInterceptor.CALLS);
        assertEquals(List.of("init", "dispose"), LifecycleAuditedService.CALLBACKS);
    }
}
