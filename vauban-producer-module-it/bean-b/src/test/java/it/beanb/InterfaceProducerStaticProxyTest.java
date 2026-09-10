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
package it.beanb;

import io.vidocq.vauban.core.container.VaubanContainer;
import it.liba.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #42 (interface producer static proxy): a normal-scoped producer of an interface must be
 * proxied by a build-time generated class {@code implements Tool}, not a runtime
 * {@code java.lang.reflect.Proxy}. On the module path, with zero reflection.
 */
@DisplayName("interface producer resolves via a build-time static proxy, not reflect.Proxy")
class InterfaceProducerStaticProxyTest {

    @Test
    @DisplayName("the produced interface proxy is a generated static class")
    void interfaceProducerIsStaticProxy() {
        try (VaubanContainer container =
                     VaubanContainer.builder().addBeanClass(ToolProducer.class).build()) {
            Tool tool = container.select(Tool.class);
            assertEquals("hammer", tool.use());
            assertEquals("tool:hammer", tool.describe(),
                    "the default method must forward to the contextual instance too");
            assertFalse(java.lang.reflect.Proxy.isProxyClass(tool.getClass()),
                    "must be a build-time static proxy, not a java.lang.reflect.Proxy");
            assertTrue(tool.getClass().getName().endsWith("_ClientProxy"),
                    "expected a generated <Tool>_ClientProxy, got " + tool.getClass().getName());
        }
    }
}
