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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The client proxy of a normal-scoped bean whose own name contains {@code $}, which the processor
 * used to proxy from its index after failing to find it by name, forwards the methods the bean
 * inherits, not only those it declares (BUG-20261004-11). Each method returns the name of the class
 * it runs on: the contextual instance's, never the proxy's. The other case of the bug, a bean with
 * private constructors only, is unproxyable without weaving, which this module does not do: the
 * processor's {@code IndexProxyInheritedMethodsTest} covers it.
 */
@DisplayName("Inherited methods through the client proxy of a bean whose name contains '$' — module path")
class IndexProxyInheritedMethodModulePathTest {

    /** What each method returns when it runs on the contextual instance, which is not intercepted. */
    private static final String BEAN = Dollar$SelfNamingService.class.getName();

    private static VaubanContainer boot(Class<?> beanClass) {
        return VaubanContainer.builder()
                .addBeanClass(beanClass)
                .build();
    }

    @Test
    @DisplayName("a bean whose name contains '$': its own method reaches the contextual instance")
    void dollarBeanDeclaredMethod() {
        try (var container = boot(Dollar$SelfNamingService.class)) {
            var proxy = container.select(Dollar$SelfNamingService.class);
            assertEquals(BEAN, proxy.own());
        }
    }

    @Test
    @DisplayName("a bean whose name contains '$': an inherited superclass method reaches the contextual instance")
    void dollarBeanSuperclassMethod() {
        try (var container = boot(Dollar$SelfNamingService.class)) {
            var proxy = container.select(Dollar$SelfNamingService.class);
            assertEquals(BEAN, proxy.inheritedSelf(),
                    "the inherited method ran on the proxy instance");
        }
    }

    @Test
    @DisplayName("a bean whose name contains '$': an interface default method reaches the contextual instance")
    void dollarBeanDefaultMethod() {
        try (var container = boot(Dollar$SelfNamingService.class)) {
            var proxy = container.select(Dollar$SelfNamingService.class);
            assertEquals(BEAN, proxy.defaultSelf(),
                    "the default method ran on the proxy instance");
        }
    }
}
