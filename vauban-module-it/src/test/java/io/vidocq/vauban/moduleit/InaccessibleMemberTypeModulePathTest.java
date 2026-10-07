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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A normal-scoped bean inheriting a default method whose member signature names a type this
 * package cannot (`label(HiddenArgument)`, `HiddenArgument` package-private in `foreign`): the
 * client proxy cannot declare that override in source, so the processor emits it as bytecode, which
 * forwards the default by its erased descriptor, as the run-time proxy does (BUG-20261004-02,
 * BUG-20261004-09 {@code n3c}, {@code n11a}).
 *
 * <p>The default names the class of the instance it runs on ({@link ReceiverLabeled}), so the test
 * tells a forward to the contextual instance, which answers with the bean's class, from the body
 * running on the proxy instance.</p>
 */
@DisplayName("Inherited default with an unnameable member type — processor proxy, module path")
class InaccessibleMemberTypeModulePathTest {

    @Test
    @DisplayName("the proxy forwards the bean's methods and the default to the contextual instance (n3c)")
    void defaultForwarded() throws Exception {
        try (var container = VaubanContainer.builder().addBeanClass(ScopedHiddenDefaultService.class).build()) {
            var service = container.select(ScopedHiddenDefaultService.class);
            assertEquals(Class.forName(ScopedHiddenDefaultService.class.getName() + "_ClientProxy"), service.getClass());
            assertEquals("own", service.own());
            assertEquals("ScopedHiddenDefaultService hidden", HiddenDefaultBase.callLabel(new ScopedHiddenDefaultService()),
                    "on a plain instance, the default runs on the bean");
            assertEquals("ScopedHiddenDefaultService hidden", HiddenDefaultBase.callLabel(service),
                    "forwarded: the default runs on the contextual instance, not on the proxy");
        }
    }

    @Test
    @DisplayName("a private nested type of the bean's own package: the default is forwarded as well (BUG-20261004-09, n11a)")
    void privateNestedDefaultForwarded() throws Exception {
        // label(Secret), Secret being private in PrivateNestedHolder of this very package: a source
        // proxy, another top-level class, cannot name it either; the bytecode proxy needs not.
        try (var container = VaubanContainer.builder().addBeanClass(ScopedSecretLabeledService.class).build()) {
            var service = container.select(ScopedSecretLabeledService.class);
            assertEquals(Class.forName(ScopedSecretLabeledService.class.getName() + "_ClientProxy"), service.getClass());
            assertEquals("own", service.own());
            assertEquals("ScopedSecretLabeledService secret",
                    PrivateNestedHolder.callLabel(new ScopedSecretLabeledService()),
                    "on a plain instance, the default runs on the bean");
            assertEquals("ScopedSecretLabeledService secret", PrivateNestedHolder.callLabel(service),
                    "forwarded: the default runs on the contextual instance, not on the proxy");
        }
    }
}
