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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CDI 4.1 calls a {@code @Registration} method with an {@code ObserverInfo} parameter once for each observer whose
 * observed event type is assignable to an expected type. The container matched the class that declares the observer
 * instead (BUG-20261117-05).
 */
@DisplayName("@Registration with an ObserverInfo parameter matches the observed event type (BUG-20261117-05)")
class BceRegistrationObserverInfoTest {

    public static class Ping implements Serializable {
    }

    @ApplicationScoped
    public static class Listener implements Serializable {

        void onPing(@Observes Ping ping) {
            // observes an event of an expected type
        }

        void onText(@Observes CharSequence text) {
            // observes an event of no expected type
        }
    }

    /** Records the event types of the observers {@code @Registration} gives it. */
    public static class ObserverRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Serializable.class)
        public void serializableEvents(ObserverInfo observer) {
            SEEN.add(observer.eventType().asClass().declaration().name());
        }
    }

    @Test
    @DisplayName("an ObserverInfo parameter is given the observers of an event of an expected type")
    void anObserverInfoParameterIsGivenTheObserversOfAnEventOfAnExpectedType() {
        ObserverRecorder.SEEN.clear();

        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(Listener.class, ObserverRecorder.class).initialize()) {
            container.getBeanManager().getEvent().select(Ping.class).fire(new Ping());
        }

        assertEquals(List.of(Ping.class.getName()), List.copyOf(ObserverRecorder.SEEN));
    }

    /** Records the method, the event parameter and the bean of the observers of a {@code Ping} it is given. */
    public static class ObserverDescriber implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Ping.class)
        public void pings(ObserverInfo observer) {
            SEEN.add(observer.observerMethod().name() + " " + observer.eventParameter().declaringMethod().name()
                    + " " + observer.bean().declaringClass().name());
        }
    }

    /**
     * CDI 4.1 gives the {@code ObserverInfo} of an observer method its method, its event parameter and the bean that
     * declares it; the container gives {@code null} for each (BUG-20261117-09).
     */
    @Test
    @Disabled("BUG-20261117-09: an ObserverInfo has no observer method, no event parameter and no bean")
    @DisplayName("an ObserverInfo gives the observer method, its event parameter and its bean")
    void anObserverInfoGivesItsMethodItsEventParameterAndItsBean() {
        ObserverDescriber.SEEN.clear();

        try (var _ = SeContainerInitializer.newInstance()
                .addBeanClasses(Listener.class, ObserverDescriber.class).initialize()) {
            assertEquals(List.of("onPing onPing " + Listener.class.getName()), List.copyOf(ObserverDescriber.SEEN));
        }
    }
}
