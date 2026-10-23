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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * VAU-OBS-004: deployment validation checks the parameters of the observer or disposer method itself, not of the
 * first method of that name the class declares. The order of {@code getDeclaredMethods()} is unspecified, so each
 * bean below declares its overloads in another order: with a first-match lookup, at least one of them is validated
 * through the overload that observes or disposes nothing, whose {@code int} parameter no bean can satisfy.
 */
@DisplayName("VAU-OBS-004: the observer or disposer overload is the one validated")
class ObserverOverloadValidationTest {

    @Dependent
    public static class ObserverFirst {
        public void on(@Observes String text) {
            // observes strings
        }

        public void on(int ignored) {
            // shares the name, observes nothing
        }
    }

    @Dependent
    public static class ObserverLast {
        public void on(int ignored) {
            // shares the name, observes nothing
        }

        public void on(long ignored) {
            // shares the name, observes nothing
        }

        public void on(@Observes String text) {
            // observes strings
        }
    }

    @Dependent
    public static class ObserverMiddle {
        public void handle(long ignored) {
            // shares the name, observes nothing
        }

        public void handle(@Observes String text) {
            // observes strings
        }

        public void handle(int ignored) {
            // shares the name, observes nothing
        }
    }

    public static class Resource {
    }

    @Dependent
    public static class DisposerOverloads {
        @Produces
        public Resource produce() {
            return new Resource();
        }

        public void close(int ignored) {
            // shares the name, disposes nothing
        }

        public void close(@Disposes Resource resource) {
            // disposes the resource
        }

        public void close(long ignored) {
            // shares the name, disposes nothing
        }
    }

    @Test
    @DisplayName("a bean whose observer method has overloads deploys")
    void observerOverloads() {
        assertDoesNotThrow(() -> VaubanContainer.builder()
                .addBeanClass(ObserverFirst.class)
                .addBeanClass(ObserverLast.class)
                .addBeanClass(ObserverMiddle.class)
                .build()
                .close());
    }

    @Test
    @DisplayName("a bean whose disposer method has overloads deploys")
    void disposerOverloads() {
        assertDoesNotThrow(() -> VaubanContainer.builder()
                .addBeanClass(DisposerOverloads.class)
                .build()
                .close());
    }
}
