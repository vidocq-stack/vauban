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
 * A bean that binds the type variable of a generic superclass or of a generic interface with
 * default methods, through the sources the processor renders: the client proxy and the
 * {@code $$Intercepted} subclass override each inherited method as the bean sees it
 * ({@code echo(String)}), which is the only override Java source accepts, and the interceptor still
 * sees the erased declaration ({@code echo(Object)}), the method the class file holds
 * (BUG-20261004-06). That this module compiles at all is the first half of the test.
 */
@DisplayName("Generic supertypes — processor proxy and subclass sources, module path")
class GenericInheritanceModulePathTest {

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
    @DisplayName("a generic superclass method, through the build-time client proxy")
    void genericSuperclassMethodThroughTheClientProxy() throws Exception {
        try (var container = boot(GenericScopedService.class)) {
            var service = container.select(GenericScopedService.class);
            assertEquals(Class.forName(GenericScopedService.class.getName() + "_ClientProxy"), service.getClass(),
                    "the reference must be the client proxy the processor rendered");
            assertEquals("echo y", service.echo("y"));
            assertEquals(List.of(GenericBase.class.getDeclaredMethod("echo", Object.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a generic superclass method returning the type variable")
    void genericReturnType() throws Exception {
        try (var container = boot(GenericScopedService.class)) {
            String same = container.select(GenericScopedService.class).identity("z");
            assertEquals("z", same);
            assertEquals(List.of(GenericBase.class.getDeclaredMethod("identity", Object.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a generic interface default method, through the build-time client proxy")
    void genericDefaultMethodThroughTheClientProxy() throws Exception {
        try (var container = boot(GenericScopedService.class)) {
            assertEquals("label x", container.select(GenericScopedService.class).label("x"));
            assertEquals(List.of(Labeled.class.getDeclaredMethod("label", Object.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a bean bound only through a generic superclass method")
    void beanBoundThroughAGenericSuperclassMethod() throws Exception {
        try (var container = boot(GenericBoundService.class)) {
            var service = container.select(GenericBoundService.class);
            assertEquals(Class.forName(GenericBoundService.class.getName() + "$$Intercepted"), service.getClass(),
                    "the bean must be the subclass the processor rendered");
            assertEquals("stored v", service.store("v"));
            assertEquals(List.of(GenericBase.class.getDeclaredMethod("store", Object.class)),
                    AuditInterceptor.METHODS);
        }
    }
}
