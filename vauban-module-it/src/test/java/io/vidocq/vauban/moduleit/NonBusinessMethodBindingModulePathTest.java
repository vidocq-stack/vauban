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
 * On the module path, a bean whose only binding sits on a method that is not one of its business
 * methods is not intercepted: the processor does not pre-generate a subclass for it, and the
 * container, counting that binding, used to wrap it anyway and fail to define the subclass itself.
 */
@DisplayName("A binding on a method that is not a business method, module path")
class NonBusinessMethodBindingModulePathTest {

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
    @DisplayName("a package-private method of a superclass in another package")
    void foreignPackagePrivateMethod() {
        try (var container = boot(ForeignBoundService.class)) {
            var service = container.select(ForeignBoundService.class);
            assertEquals(ForeignBoundService.class, service.getClass(), "not intercepted");
            assertEquals("own", service.own());
            assertEquals(List.of(), AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a private method")
    void privateMethod() {
        try (var container = boot(PrivatelyBoundService.class)) {
            var service = container.select(PrivatelyBoundService.class);
            assertEquals(PrivatelyBoundService.class, service.getClass(), "not intercepted");
            assertEquals("hidden", service.visible());
            assertEquals(List.of(), AuditInterceptor.METHODS);
        }
    }
}
