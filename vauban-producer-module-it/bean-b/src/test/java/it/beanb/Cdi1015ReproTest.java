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
import it.liba.Foo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs on the MODULE PATH (a main {@code module-info.java} exists, so surefire uses it).
 * {@code it.liba} is a real named module on that path with no {@code opens}.
 *
 * <p>Desired behaviour (Stage 1, #42): the {@code @ApplicationScoped} producer of the fully-public
 * {@code it.liba.Foo} resolves through a BUILD-TIME proxy placed in {@code it.beanb}, with zero
 * {@code opens} and zero runtime {@code defineClass}. Until Stage 1 lands this FAILS at runtime
 * with "Cannot reflectively access ... opens it.liba to io.vidocq.vauban.core".</p>
 */
@DisplayName("cdi#1015 — class-typed cross-module producer resolves with zero opens")
class Cdi1015ReproTest {

    @Test
    @DisplayName("@Produces @ApplicationScoped it.liba.Foo resolves without opens")
    void producedTypeResolvesWithZeroOpens() {
        try (VaubanContainer container =
                     VaubanContainer.builder().addBeanClass(FooProducer.class).build()) {
            Foo foo = container.select(Foo.class);
            assertEquals("hello", foo.greet(),
                    "the @ApplicationScoped producer of a fully-public class from a non-opened "
                            + "module must resolve via a build-time proxy (zero opens, zero runtime defineClass)");
        }
    }
}
