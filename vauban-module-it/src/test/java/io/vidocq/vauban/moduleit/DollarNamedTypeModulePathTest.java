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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generated classes that name a type whose own name contains {@code $} — the intercepted subclass
 * and client proxy of such a bean, the producer proxies of such a class and interface — compile and
 * work on the module path (BUG-20261007-01).
 */
@DisplayName("Generated classes naming a type whose name contains '$' — module path")
class DollarNamedTypeModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.METHODS.clear();
    }

    @Test
    @DisplayName("an intercepted bean whose name contains '$' runs its build-time subclass behind its proxy")
    void interceptedDollarBean() throws Exception {
        try (var container = VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(Dollar$AuditedService.class)
                .build()) {
            var proxy = container.select(Dollar$AuditedService.class);
            assertEquals("io.vidocq.vauban.moduleit.Dollar$AuditedService_ClientProxy", proxy.getClass().getName());
            assertEquals("x@Dollar$AuditedService$$Intercepted", proxy.echo(new Dollar$Note("x")).text(),
                    "the call reaches the processor's subclass through the proxy");
            assertEquals(List.of(Dollar$AuditedService.class.getDeclaredMethod("echo", Dollar$Note.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a producer of a class whose name contains '$' gets its build-time proxy")
    void producerOfDollarClass() {
        try (var container = VaubanContainer.builder().addBeanClass(DollarProducers.class).build()) {
            var proxy = container.select(Dollar$Produced.class);
            assertTrue(proxy.getClass().getName().endsWith("_ClientProxy")
                            && proxy.getClass().getPackageName().equals(DollarProducers.class.getPackageName()),
                    "the producer's build-time proxy: " + proxy.getClass().getName());
            assertEquals("produced", proxy.hello());
        }
    }

    @Test
    @DisplayName("a producer of an interface whose name contains '$' gets its build-time proxy")
    void producerOfDollarInterface() {
        try (var container = VaubanContainer.builder().addBeanClass(DollarProducers.class).build()) {
            var proxy = container.select(Dollar$Port.class);
            assertTrue(proxy.getClass().getName().endsWith("_ClientProxy"),
                    "the producer's build-time proxy: " + proxy.getClass().getName());
            assertEquals("port:y", proxy.note(new Dollar$Note("y")).text());
        }
    }
}
