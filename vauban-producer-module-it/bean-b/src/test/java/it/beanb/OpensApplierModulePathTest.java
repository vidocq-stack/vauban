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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage 3b (issue #42): an ineligible produced type ({@code it.liba.Gadget} has a package-private
 * method) cannot get a build-time proxy, so it falls back to runtime generation, which needs
 * {@code opens it.liba to io.vidocq.vauban.core}. The container can open it at boot
 * ({@code OpensApplier} + {@code Instrumentation.redefineModule}), driven by the APT-written
 * {@code META-INF/vauban/required-opens.list} — resolving on the MODULE PATH with no hand-written
 * {@code --add-opens} and no jar rewrite.
 *
 * <p>That lever is <strong>opt-in</strong>: left off, the container refuses to open another module
 * on its own and says so. These two tests pin both halves, and run in order because opening a
 * module is irreversible within a JVM — once the second test has opened {@code it.liba}, the first
 * could no longer observe the refusal.
 */
@DisplayName("Stage 3b — opt-in boot-time opens on the module path")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpensApplierModulePathTest {

    /**
     * The opt-in switch, spelled exactly as a user would on the command line. Referenced as a
     * literal rather than through the constant because {@code io.vidocq.vauban.core.opens} is not
     * an exported package — and a test is not a reason to widen a module's API.
     */
    private static final String AUTO_OPEN = "vauban.opens.auto";

    @Test
    @Order(1)
    @DisplayName("off by default: the container does not open another module on its own")
    void doesNothingUnlessAskedTo() {
        assertFalse(Boolean.getBoolean(AUTO_OPEN),
                "the boot-time open must be opt-in: a silent dependency on dynamic agent "
                        + "attachment would only surface on a locked-down JVM");
        assertThrows(Throwable.class, () -> {
            try (VaubanContainer container =
                         VaubanContainer.builder().addBeanClass(FooProducer.class).build()) {
                container.select(Gadget.class).run();
            }
        }, "without the opt-in, the ineligible produced type must fail loudly rather than resolve");
    }

    @Test
    @Order(2)
    @DisplayName("opted in: the container opens it.liba at boot so the runtime Gadget proxy resolves")
    void ineligibleProducedTypeResolvesViaBootTimeOpens() {
        System.setProperty(AUTO_OPEN, "true");
        try (VaubanContainer container =
                     VaubanContainer.builder().addBeanClass(FooProducer.class).build()) {
            Gadget gadget = container.select(Gadget.class);
            assertEquals("gadget", gadget.run(),
                    "with the opt-in set, Stage 3b must open it.liba to the container at boot so the "
                            + "runtime proxy for the ineligible Gadget resolves without --add-opens");
        } finally {
            System.clearProperty(AUTO_OPEN);
        }
    }
}
