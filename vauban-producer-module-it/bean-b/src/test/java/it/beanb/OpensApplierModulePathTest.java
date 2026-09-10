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
import it.liba.Gadget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stage 3b (issue #42): an ineligible produced type (it.liba.Gadget has a package-private method)
 * cannot get a build-time proxy, so it falls to runtime generation which needs
 * {@code opens it.liba to io.vidocq.vauban.core}. The container opens it at boot via the agent
 * ({@code OpensApplier} + {@code Instrumentation.redefineModule}), driven by the APT-written
 * {@code META-INF/vauban/required-opens.list} — so this resolves on the MODULE PATH with no
 * hand-written {@code --add-opens} and no jar rewrite.
 */
@DisplayName("Stage 3b — ineligible producer resolves on the module path with zero --add-opens")
class OpensApplierModulePathTest {

    @Test
    @DisplayName("the container opens it.liba at boot so the runtime Gadget proxy resolves")
    void ineligibleProducedTypeResolvesViaBootTimeOpens() {
        try (VaubanContainer container =
                     VaubanContainer.builder().addBeanClass(FooProducer.class).build()) {
            Gadget gadget = container.select(Gadget.class);
            assertEquals("gadget", gadget.run(),
                    "Stage 3b must open it.liba to the container at boot so the runtime proxy for the "
                            + "ineligible Gadget resolves without a hand-written --add-opens");
        }
    }
}
