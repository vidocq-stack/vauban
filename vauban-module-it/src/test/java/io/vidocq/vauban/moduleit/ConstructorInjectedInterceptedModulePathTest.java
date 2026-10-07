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
import io.vidocq.vauban.moduleit.foreign.HiddenTaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Intercepted beans built through an injected constructor, on the module path, where reading an
 * annotation back is forbidden: the container resolves the constructor's arguments from what the
 * processor recorded (BUG-20261007-03), then instantiates the subclass through the provider.
 */
@DisplayName("Intercepted beans with an injected constructor — module path")
class ConstructorInjectedInterceptedModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.CALLS.clear();
    }

    private static VaubanContainer boot(Class<?> beanClass) {
        return VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(Collaborator.class)
                .addBeanClass(beanClass)
                .build();
    }

    @Test
    @DisplayName("a source subclass: built through the injected constructor, intercepted")
    void sourceSubclass() {
        try (var container = boot(ConstructorInjectedAuditedService.class)) {
            var bean = container.select(ConstructorInjectedAuditedService.class);
            assertEquals(ConstructorInjectedAuditedService.class.getName() + "$$Intercepted", bean.getClass().getName());
            assertEquals("hello", bean.greet());
            assertEquals(List.of("greet/0"), AuditInterceptor.CALLS);
        }
    }

    @Test
    @DisplayName("a bytecode subclass: the provider passes the constructor's arguments (BUG-20261004-09)")
    void bytecodeSubclass() {
        try (var container = boot(ConstructorInjectedHiddenTakerService.class)) {
            var bean = container.select(ConstructorInjectedHiddenTakerService.class);
            assertEquals(ConstructorInjectedHiddenTakerService.class.getName() + "$$Intercepted", bean.getClass().getName());
            assertEquals("hello", bean.greet());
            assertEquals("ConstructorInjectedHiddenTakerService$$Intercepted took hidden", HiddenTaker.callTake(bean));
            assertEquals(List.of("greet/0", "take/1"), AuditInterceptor.CALLS);
        }
    }
}
