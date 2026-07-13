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
package io.vidocq.vauban.core.bean.validation;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI 4.1 §10.4.3 — the non-event parameters of an observer method are
 * injection points and must be validated at deployment time. An observer
 * parameter whose type has no matching bean must fail the deployment (the MP
 * Config TCK's {@code MissingValueOnObserverMethodInjectionTest} relies on
 * this), not silently break at event-dispatch time — startup events suppress
 * dispatch exceptions.
 */
@DisplayName("Observer method non-event parameters are validated at deployment")
class ObserverParamValidationTest {

    /** Not registered as a bean in the failing container. */
    public static class UnsatisfiedDep {
    }

    @Dependent
    public static class SatisfiedDep {
        boolean here = true;
    }

    @ApplicationScoped
    public static class BrokenObserver {
        void onStart(@Observes @Initialized(ApplicationScoped.class) Object event, UnsatisfiedDep dep) {
            // never reached — deployment must fail
        }
    }

    @ApplicationScoped
    public static class HealthyObserver {
        static SatisfiedDep seen;

        void onStart(@Observes @Initialized(ApplicationScoped.class) Object event, SatisfiedDep dep) {
            seen = dep;
        }
    }

    @Test
    @DisplayName("an observer parameter with no matching bean fails the deployment")
    void unsatisfiedObserverParamFailsDeployment() {
        DeploymentException failure = assertThrows(DeploymentException.class, () -> {
            try (VaubanContainer container = VaubanContainer.builder()
                    .addBeanClass(BrokenObserver.class)
                    .build()) {
                // deployment must not succeed
            }
        });
        assertTrue(String.valueOf(failure.getMessage()).contains("onStart"),
                "the failure must point at the observer parameter, got: " + failure.getMessage());
    }

    @Test
    @DisplayName("a satisfiable observer parameter still deploys and injects")
    void satisfiedObserverParamDeploysAndInjects() {
        HealthyObserver.seen = null;
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(HealthyObserver.class)
                .addBeanClass(SatisfiedDep.class)
                .build()) {
            assertNotNull(HealthyObserver.seen,
                    "the @Initialized observer must have received its non-event parameter");
            assertTrue(HealthyObserver.seen.here);
        }
    }
}
