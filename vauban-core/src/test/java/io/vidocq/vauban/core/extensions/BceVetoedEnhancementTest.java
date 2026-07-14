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

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-BCE-005 — {@code @Vetoed} added through a build compatible extension's
 * {@code @Enhancement} phase must exclude the class from bean discovery, exactly as a
 * source-level {@code @Vetoed} would (CDI 4.1 — enhancement-modified annotations are the
 * ones bean discovery sees). Frameworks rely on this to replace a discovered managed bean
 * with a synthetic one (e.g. Mansart's dataStore-routed repositories, MANSART-005).
 */
@DisplayName("VAU-BCE-005 — @Vetoed added at @Enhancement excludes the bean")
class BceVetoedEnhancementTest {

    @Singleton
    public static class VetoedTarget {
        public String who() {
            return "managed";
        }
    }

    @Singleton
    public static class UntouchedBean {
        public String who() {
            return "still-here";
        }
    }

    public static class VetoBce implements BuildCompatibleExtension {
        @Enhancement(types = VetoedTarget.class)
        public void veto(ClassConfig clazz) {
            clazz.addAnnotation(Vetoed.class);
        }
    }

    @Test
    @DisplayName("the vetoed class is no longer a bean; unrelated beans are untouched")
    void enhancementAddedVetoExcludesTheBean() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(VetoedTarget.class)
                .addBeanClass(UntouchedBean.class)
                .addBeanClass(VetoBce.class)
                .build()) {

            assertTrue(container.getBeanManager().getBeans(VetoedTarget.class).isEmpty(),
                    "a class that gains @Vetoed at @Enhancement must not be discovered as a bean");
            assertEquals(1, container.getBeanManager().getBeans(UntouchedBean.class).size(),
                    "beans not targeted by the veto must remain discoverable");
        }
    }
}
