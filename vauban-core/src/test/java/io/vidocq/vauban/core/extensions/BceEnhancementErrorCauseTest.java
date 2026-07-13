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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-BCE-003 — the typed exception thrown by a BCE phase survives in the
 * {@link DeploymentException} cause chain.
 *
 * <p>Several TCKs deploy invalid archives and assert the deployment fails with
 * a spec-defined exception (e.g. MP Fault Tolerance's
 * {@code FaultToleranceDefinitionException}, Arquillian
 * {@code @ShouldThrowException} walks the cause chain). Flattening BCE errors
 * to strings loses that type and makes those scenarios untestable.</p>
 */
@DisplayName("VAU-BCE-003 — BCE errors keep their typed cause in DeploymentException")
class BceEnhancementErrorCauseTest {

    /** Spec-defined exception a BCE would throw on invalid configuration. */
    public static class InvalidDefinitionException extends RuntimeException {
        public InvalidDefinitionException(String message) {
            super(message);
        }
    }

    @ApplicationScoped
    public static class SomeBean {
    }

    public static class RejectingBce implements BuildCompatibleExtension {
        @Enhancement(types = SomeBean.class)
        public void reject(ClassConfig classConfig) {
            throw new InvalidDefinitionException("invalid configuration on " + classConfig.info().name());
        }
    }

    @Test
    @DisplayName("@Enhancement exception is reachable through the DeploymentException causes")
    void enhancementExceptionIsPreservedAsCause() {
        DeploymentException failure = assertThrows(DeploymentException.class, () -> {
            try (VaubanContainer container = VaubanContainer.builder()
                    .addBeanClass(RejectingBce.class)
                    .addBeanClass(SomeBean.class)
                    .build()) {
                // deployment must fail before the container is usable
            }
        });

        Throwable cause = failure;
        InvalidDefinitionException typed = null;
        while (cause != null) {
            if (cause instanceof InvalidDefinitionException found) {
                typed = found;
                break;
            }
            cause = cause.getCause();
        }
        assertNotNull(typed, "the BCE's typed exception must survive in the cause chain");
        assertTrue(typed.getMessage().contains("invalid configuration"),
                "original message must be preserved");
    }
}
