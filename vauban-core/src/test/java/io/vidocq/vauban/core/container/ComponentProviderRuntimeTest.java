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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the container instantiates a bean through a
 * {@link io.vidocq.vauban.api.VaubanComponentProvider} (loaded via ServiceLoader) instead
 * of reflection — i.e. without needing {@code opens … to io.vidocq.vauban.core}.
 *
 * <p>{@link CountingComponentProvider} is registered as a service and records the call when
 * it instantiates {@link ProvidedBean}.
 */
@DisplayName("VaubanComponentProvider - runtime instantiation")
class ComponentProviderRuntimeTest {

    @Test
    @DisplayName("a bean owned by a provider is created by the provider, not by reflection")
    void instantiatesViaProvider() {
        CountingComponentProvider.CREATED.clear();

        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addComponentProvider(new CountingComponentProvider())
                .addBeanClass(ProvidedBean.class)
                .build()) {

            ProvidedBean bean = container.select(ProvidedBean.class);

            assertNotNull(bean, "container should produce a ProvidedBean instance");
            assertEquals("hello", bean.hello());
            assertTrue(CountingComponentProvider.CREATED.contains(ProvidedBean.class.getName()),
                    "the bean must be instantiated through the VaubanComponentProvider "
                            + "(generated, in-module) rather than reflective newInstance");
        }
    }

    @Test
    @DisplayName("an @Inject-constructor bean is created by the provider with container-resolved args")
    void instantiatesConstructorBeanViaProvider() {
        CountingComponentProvider.CREATED.clear();

        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addComponentProvider(new CountingComponentProvider())
                .addBeanClass(ProvidedConstructorBean.class)
                .addBeanClass(ProvidedDependency.class)
                .build()) {

            ProvidedConstructorBean bean = container.select(ProvidedConstructorBean.class);

            assertNotNull(bean, "container should produce a ProvidedConstructorBean instance");
            assertEquals("bean+dep", bean.describe());
            assertTrue(CountingComponentProvider.CREATED.contains(ProvidedConstructorBean.class.getName()),
                    "the @Inject-constructor bean must be instantiated through the provider "
                            + "(in-module new X(args)) rather than reflective newInstance");
        }
    }
}
