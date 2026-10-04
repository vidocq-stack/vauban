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
 * rendered client proxy cannot declare that override, so it leaves the method alone — the
 * outcome of a client proxy that forwards no default at all — rather than breaking the build, and
 * the processor says so with a warning. That this module compiles is the first half of the test
 * (BUG-20261004-02).
 *
 * <p>The default names the class of the instance it runs on ({@link ReceiverLabeled}), so the test
 * tells the documented outcome — the body runs on the proxy instance — from a forward to the
 * contextual instance, which would answer with the bean's class.</p>
 */
@DisplayName("Inherited default with an unnameable member type — processor proxy, module path")
class InaccessibleMemberTypeModulePathTest {

    @Test
    @DisplayName("the proxy is rendered, forwards the bean's methods, and runs the default on itself")
    void defaultLeftAlone() throws Exception {
        try (var container = VaubanContainer.builder().addBeanClass(ScopedHiddenDefaultService.class).build()) {
            var service = container.select(ScopedHiddenDefaultService.class);
            assertEquals(Class.forName(ScopedHiddenDefaultService.class.getName() + "_ClientProxy"), service.getClass());
            assertEquals("own", service.own());
            assertEquals("ScopedHiddenDefaultService hidden", HiddenDefaultBase.callLabel(new ScopedHiddenDefaultService()),
                    "on a plain instance, the default runs on the bean");
            assertEquals("ScopedHiddenDefaultService_ClientProxy hidden", HiddenDefaultBase.callLabel(service),
                    "not forwarded: on the proxy, the default runs on the proxy instance, not on the contextual instance");
        }
    }
}
