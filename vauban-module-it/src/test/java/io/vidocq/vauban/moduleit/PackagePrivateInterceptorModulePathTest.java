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
import io.vidocq.vauban.moduleit.internal.GuardInterceptor;
import io.vidocq.vauban.moduleit.internal.GuardedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Package-private interceptor methods in a package the module neither exports nor opens (VAU-INT-011): the container
 * calls {@code @AroundConstruct}, the interceptor's {@code @PostConstruct} and {@code @AroundInvoke} through the
 * generated provider of that package, with no reflection and no {@code opens}. Module path.
 */
@DisplayName("Package-private interceptor methods without opens, module path (VAU-INT-011)")
class PackagePrivateInterceptorModulePathTest {

    @BeforeEach
    void reset() {
        GuardInterceptor.CALLS.clear();
    }

    @Test
    @DisplayName("@AroundConstruct, @PostConstruct and @AroundInvoke run with no exports and no opens")
    void interceptorMethodsRunWithoutOpens() {
        Module module = GuardInterceptor.class.getModule();
        String pkg = GuardInterceptor.class.getPackageName();
        assertFalse(module.isExported(pkg), "the package must not be exported for this test to mean anything");
        assertFalse(module.isOpen(pkg), "the package must not be opened for this test to mean anything");
        try (var container = VaubanContainer.builder()
                .addBeanClass(GuardInterceptor.class)
                .addBeanClass(GuardedService.class)
                .build()) {
            assertEquals("guarded", container.select(GuardedService.class).work());
        }
        assertEquals(List.of("construct", "postConstruct", "invoke work"), GuardInterceptor.CALLS);
    }

    @Test
    @DisplayName("a checked exception of the intercepted method reaches the caller unchanged")
    void checkedExceptionPropagatesUnchanged() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(GuardInterceptor.class)
                .addBeanClass(GuardedService.class)
                .build()) {
            var service = container.select(GuardedService.class);
            var thrown = assertThrows(IOException.class, service::fail);
            assertEquals("refused", thrown.getMessage());
        }
    }
}
