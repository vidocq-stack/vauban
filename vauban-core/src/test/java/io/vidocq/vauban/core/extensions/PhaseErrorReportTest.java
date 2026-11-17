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

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-46: an error an extension phase throws without a message, such as the {@code AssertionError} of a
 * failed {@code assert}, fails the start with {@code @Enhancement error: null}; the message names neither the
 * extension, nor its method, nor the error.
 */
@DisplayName("BUG-20261117-46 - an error an extension phase throws without a message is reported by name")
class PhaseErrorReportTest {

    /** A class of the deployment for {@link Asserting}. */
    public static class Target {
    }

    /** Throws what a failed {@code assert} throws. */
    public static class Asserting implements BuildCompatibleExtension {

        @Enhancement(types = Target.class, withSubtypes = false)
        public void verify(ClassInfo clazz) {
            throw new AssertionError();
        }
    }

    @Test
    @Disabled("BUG-20261117-46: an error thrown without a message in an extension phase is reported as \"null\"")
    @DisplayName("the start failure names the extension, its method and the error")
    void theStartFailureNamesTheExtensionItsMethodAndTheError() {
        var initializer = SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(Target.class, Asserting.class);

        var thrown = assertThrows(DeploymentException.class, initializer::initialize);

        var message = thrown.getMessage();
        assertAll(() -> assertTrue(message.contains(Asserting.class.getName()), message),
                () -> assertTrue(message.contains("verify"), message),
                () -> assertTrue(message.contains(AssertionError.class.getName()), message));
    }
}
