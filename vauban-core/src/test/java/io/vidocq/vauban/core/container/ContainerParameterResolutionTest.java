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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.TransientReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How the container resolves the parameters it is asked for outside a bean: an {@link Event} with or without a
 * member, {@link Instance} parameters whose type argument is not a class, and a producer method's parameters when
 * no creational context is given.
 */
@DisplayName("VaubanContainer: parameter resolution and producer factories")
class ContainerParameterResolutionTest {

    @Dependent
    public static class Clock {
        public String now() {
            return "now";
        }
    }

    public static class Report {
        final String text;

        Report(String text) {
            this.text = text;
        }
    }

    @ApplicationScoped
    public static class ReportFactory {
        @Produces
        public Report report(Clock clock, @TransientReference Clock transientClock) {
            return new Report(clock.now() + "/" + transientClock.now());
        }
    }

    public static class Holder {
        @SuppressWarnings("rawtypes")
        public Instance raw;
        public Instance<List<String>> lists;
    }

    private static VaubanContainer boot() {
        return VaubanContainer.builder()
                .addBeanClass(Clock.class)
                .addBeanClass(ReportFactory.class)
                .build();
    }

    @Test
    @DisplayName("an error thrown while an injection point is bound is a container operation failure")
    void errorWhileInjectionPointBound() {
        var error = new AssertionError("broken");

        var thrown = assertThrows(ContainerOperationException.class,
                () -> VaubanContainer.callWithInjectionPoint(VaubanInjectionPoint.EMPTY, () -> {
                    throw error;
                }));
        var checked = assertThrows(IOException.class,
                () -> VaubanContainer.callWithInjectionPoint(VaubanInjectionPoint.EMPTY, () -> {
                    throw new IOException("checked");
                }));

        assertSame(error, thrown.getCause());
        assertEquals("checked", checked.getMessage());
    }

    @Test
    @DisplayName("an Event parameter is resolved with or without the member declaring it")
    void eventParameters() throws NoSuchMethodException {
        try (var container = boot()) {
            var member = ReportFactory.class.getMethod("report", Clock.class, Clock.class);

            assertInstanceOf(Event.class, container.resolveParameter(Event.class, Event.class, null,
                    new Annotation[0]));
            assertInstanceOf(Event.class, container.resolveParameter(Event.class, Event.class, null,
                    new Annotation[0], member));
        }
    }

    @Test
    @DisplayName("a raw Instance, or one of a parameterized type, is resolved as an Instance of Object")
    void instanceParameters() throws NoSuchFieldException {
        try (var container = boot()) {
            var raw = container.resolveParameter(Instance.class, Holder.class.getField("raw").getGenericType());
            var lists = container.resolveParameter(Instance.class, Holder.class.getField("lists").getGenericType());

            assertInstanceOf(Instance.class, raw);
            assertInstanceOf(Instance.class, lists);
        }
    }

    @Test
    @DisplayName("a producer method's parameters are resolved even when the factory gets no creational context")
    void producerWithoutCreationalContext() {
        try (var container = boot()) {
            var beanManager = container.getBeanManager();
            var bean = (ManagedBean<?>) beanManager.resolve(beanManager.getBeans(Report.class));

            var report = assertInstanceOf(Report.class, bean.factory().create());

            assertEquals("now/now", report.text);
            assertEquals("now/now", container.select(Report.class).text);
        }
    }
}
