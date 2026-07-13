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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VAU-BCE-002 — identical synthetic bean registrations are deduplicated.
 *
 * <p>The same synthesis logic can legitimately run more than once in a single
 * boot: a build-compatible extension may be visible both through the classpath
 * scan and as an explicit bean class, and the Vidocq extension wrappers
 * re-export brick BCEs as subclasses (both service files end up discovered).
 * Registering the same synthetic bean twice makes every injection point of
 * that bean {@code AmbiguousResolution} — deduplicate on the full identity
 * (bean class, types, qualifiers, scope, creator, params) instead.</p>
 */
@DisplayName("VAU-BCE-002 — duplicate synthetic bean registrations collapse to one bean")
class SyntheticBeanDeduplicationTest {

    /** Marker type the synthetic bean is registered under. */
    public interface SyntheticApi {
    }

    public static class ConstantCreator implements SyntheticBeanCreator<SyntheticApi> {
        @Override
        public SyntheticApi create(Instance<Object> lookup, Parameters params) {
            return new SyntheticApi() {
            };
        }
    }

    /** Simulates a brick BCE synthesizing one bean per discovered interface. */
    public static class BrickBce implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components) {
            components.addBean(SyntheticApi.class)
                    .type(SyntheticApi.class)
                    .scope(Dependent.class)
                    .createWith(ConstantCreator.class);
        }
    }

    /** Simulates a Vidocq extension wrapper re-exporting the brick BCE. */
    public static class WrapperBce extends BrickBce {
    }

    @Test
    @DisplayName("the same BCE synthesis discovered twice registers a single bean")
    void duplicateSynthesisRegistersASingleBean() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(BrickBce.class)
                .addBeanClass(WrapperBce.class)
                .build()) {

            var beans = container.getBeanManager().getBeans(SyntheticApi.class);
            assertEquals(1, beans.size(),
                    "identical synthetic bean registrations must be deduplicated");
        }
    }
}
