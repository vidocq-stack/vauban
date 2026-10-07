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
import io.vidocq.vauban.moduleit.foreign.HiddenDefaultBase;
import io.vidocq.vauban.moduleit.foreign.HiddenTaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beans inheriting members whose signatures name a type their package cannot name — a
 * package-private type of another package, a private nested type of their own — through the
 * classes the processor generates, on the module path (BUG-20261004-09). Java source cannot declare
 * such an override, so the processor emits that subclass or proxy as bytecode, which names the type
 * in a method descriptor only. Before the fix this module did not compile.
 */
@DisplayName("Inherited members with unnameable types — processor subclass and proxy, module path")
class UnnameableMemberTypeModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.CALLS.clear();
        AuditInterceptor.METHODS.clear();
    }

    private static VaubanContainer boot(Class<?> beanClass) {
        return VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(beanClass)
                .build();
    }

    @Test
    @DisplayName("n3a: a default whose member names a package-private type of another package is intercepted")
    void hiddenDefaultIntercepted() {
        try (var container = boot(AuditedHiddenDefaultService.class)) {
            var bean = container.select(AuditedHiddenDefaultService.class);
            assertEquals(AuditedHiddenDefaultService.class.getName() + "$$Intercepted", bean.getClass().getName());
            assertEquals("AuditedHiddenDefaultService$$Intercepted hidden", HiddenDefaultBase.callLabel(bean));
            assertEquals("own", bean.own());
            assertEquals(List.of("label/1", "own/0"), AuditInterceptor.CALLS);
        }
    }

    @Test
    @DisplayName("n3b: a class method with a package-private parameter type of another package is intercepted")
    void hiddenParameterIntercepted() {
        try (var container = boot(AuditedHiddenTakerService.class)) {
            var bean = container.select(AuditedHiddenTakerService.class);
            assertEquals(AuditedHiddenTakerService.class.getName() + "$$Intercepted", bean.getClass().getName());
            assertEquals("AuditedHiddenTakerService$$Intercepted took hidden", HiddenTaker.callTake(bean));
            assertEquals(List.of("take/1"), AuditInterceptor.CALLS);
            assertEquals(HiddenTaker.class.getName() + "#take", methodName(0));
            // give() returns the hidden type: no subclass outside its package can type the value an
            // interceptor chain hands back, so it is not intercepted, and runs as on the bean.
            assertEquals("hidden", HiddenTaker.callGive(bean));
            assertEquals(List.of("take/1"), AuditInterceptor.CALLS);
        }
    }

    @Test
    @DisplayName("n3d: the client proxy forwards class methods naming a package-private type of another package")
    void hiddenParameterForwarded() throws Exception {
        try (var container = boot(ScopedHiddenTakerService.class)) {
            var service = container.select(ScopedHiddenTakerService.class);
            assertEquals(Class.forName(ScopedHiddenTakerService.class.getName() + "_ClientProxy"), service.getClass());
            assertEquals("ScopedHiddenTakerService took hidden", HiddenTaker.callTake(service),
                    "forwarded: the method runs on the contextual instance, not on the proxy");
            assertEquals("hidden", HiddenTaker.callGive(service));
            assertEquals("own", service.own());
        }
    }

    @Test
    @DisplayName("a producer's client proxy forwards class methods naming a package-private type of another package")
    void producedHiddenParameterForwarded() {
        try (var container = boot(HiddenTakerProducer.class)) {
            var taker = container.select(HiddenTaker.class);
            assertTrue(taker.getClass().getName().endsWith("_ClientProxy"), taker.getClass().getName());
            assertEquals("HiddenTaker took hidden", HiddenTaker.callTake(taker),
                    "forwarded: the method runs on the produced instance, not on the proxy");
            assertEquals("hidden", HiddenTaker.callGive(taker));
        }
    }

    @Test
    @DisplayName("n11b: the client proxy forwards a class method naming a private nested type of the package")
    void secretParameterForwarded() throws Exception {
        try (var container = boot(ScopedSecretTakerService.class)) {
            var service = container.select(ScopedSecretTakerService.class);
            assertEquals(Class.forName(ScopedSecretTakerService.class.getName() + "_ClientProxy"), service.getClass());
            assertEquals("ScopedSecretTakerService took secret", PrivateNestedHolder.callTake(service),
                    "forwarded: the method runs on the contextual instance, not on the proxy");
            assertEquals("own", service.own());
        }
    }

    @Test
    @DisplayName("n11c: a default whose member names a private nested type of the package is intercepted")
    void secretDefaultIntercepted() {
        try (var container = boot(AuditedSecretLabeledService.class)) {
            var bean = container.select(AuditedSecretLabeledService.class);
            assertEquals(AuditedSecretLabeledService.class.getName() + "$$Intercepted", bean.getClass().getName());
            assertEquals("AuditedSecretLabeledService$$Intercepted secret", PrivateNestedHolder.callLabel(bean));
            assertEquals(List.of("label/1"), AuditInterceptor.CALLS);
        }
    }

    @Test
    @DisplayName("a class method whose parameter is a private nested type of the package is intercepted")
    void secretParameterIntercepted() {
        try (var container = boot(AuditedSecretTakerService.class)) {
            var bean = container.select(AuditedSecretTakerService.class);
            assertEquals(AuditedSecretTakerService.class.getName() + "$$Intercepted", bean.getClass().getName());
            assertEquals("AuditedSecretTakerService$$Intercepted took secret", PrivateNestedHolder.callTake(bean));
            assertEquals(List.of("take/1"), AuditInterceptor.CALLS);
        }
    }

    /** {@code Declaring#name} of the {@code i}-th method the interceptor saw. */
    private static String methodName(int i) {
        var m = AuditInterceptor.METHODS.get(i);
        return m.getDeclaringClass().getName() + "#" + m.getName();
    }
}
