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
package io.vidocq.vauban.core.extensions;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CDI 4.1 runs the phases of the build compatible extensions in order, {@code @Discovery}, {@code @Enhancement},
 * {@code @Registration}, {@code @Synthesis}, {@code @Validation}, and the methods of a phase by {@code @Priority},
 * each on the one instance of its extension: an extension's later phases see what its earlier ones recorded. The CDI
 * Lite TCK's {@code PriorityTest} checks it with an extension that records its calls.
 */
@DisplayName("The phases of an extension run in order, on one instance")
class BcePhaseOrderTest {

    @ApplicationScoped
    public static class Service {
    }

    /** Records each call on its own instance, and the order its {@code @Validation} method sees. */
    public static class OrderRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        private final List<String> invocations = new CopyOnWriteArrayList<>();

        @Discovery
        @Priority(10)
        public void first() {
            invocations.add("discovery");
        }

        @Enhancement(types = Object.class, withSubtypes = true)
        @Priority(15)
        public void second(ClassConfig clazz) {
            if (!invocations.contains("enhancement")) {
                invocations.add("enhancement");
            }
        }

        @Registration(types = Object.class)
        @Priority(5)
        public void third(BeanInfo bean) {
            if (!invocations.contains("registration")) {
                invocations.add("registration");
            }
        }

        @Validation
        public void last() {
            invocations.add("validation");
            SEEN.addAll(invocations);
        }
    }

    @Test
    @DisplayName("an extension's @Validation sees its @Discovery, @Enhancement and @Registration calls, in order")
    void theValidationOfAnExtensionSeesItsEarlierPhasesInOrder() {
        OrderRecorder.SEEN.clear();

        try (var _ = SeContainerInitializer.newInstance().addBeanClasses(Service.class, OrderRecorder.class)
                .initialize()) {
            assertEquals(List.of("discovery", "enhancement", "registration", "validation"),
                    List.copyOf(OrderRecorder.SEEN));
        }
    }
}
