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
 * A default method that a superclass's private method shadows, through the sources the processor
 * renders, on the module path (BUG-20261004-08): {@code super.hidden(...)} — and any call typed by
 * the bean class from outside its superclass's nest — resolves to the private method and fails
 * with {@code IllegalAccessError}. The {@code $$Intercepted} subclass reaches the default
 * explicitly, and the client proxy forwards through the interface.
 */
@DisplayName("Shadowed default method — processor subclass and proxy, module path")
class ShadowedDefaultModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.METHODS.clear();
    }

    private static VaubanContainer boot(Class<?> beanClass) {
        return VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(beanClass)
                .build();
    }

    @Test
    @DisplayName("through the build-time subclass: intercepted, and the default body runs")
    void throughTheSubclass() throws Exception {
        try (var container = boot(ShadowedDefaultService.class)) {
            var service = container.select(ShadowedDefaultService.class);
            assertEquals(Class.forName(ShadowedDefaultService.class.getName() + "$$Intercepted"), service.getClass());
            AuditedHider hider = service;
            assertEquals("default x", hider.hidden("x"));
            assertEquals(List.of(AuditedHider.class.getDeclaredMethod("hidden", String.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("through the build-time client proxy: forwarded, intercepted, and the default body runs")
    void throughTheClientProxy() throws Exception {
        try (var container = boot(ScopedShadowedService.class)) {
            var service = container.select(ScopedShadowedService.class);
            assertEquals(Class.forName(ScopedShadowedService.class.getName() + "_ClientProxy"), service.getClass());
            AuditedHider hider = service;
            assertEquals("default y", hider.hidden("y"));
            assertEquals(List.of(AuditedHider.class.getDeclaredMethod("hidden", String.class)),
                    AuditInterceptor.METHODS);
        }
    }
}
