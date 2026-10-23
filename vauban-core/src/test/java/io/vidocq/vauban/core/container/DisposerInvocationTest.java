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
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disposer method receives, besides the disposed instance, whatever its other parameters ask for: an
 * {@link Event}, an {@link Instance} (of a class or of a parameterized type), the {@link BeanManager} or a bean; and
 * an exception it throws is suppressed, as CDI requires.
 */
@DisplayName("DisposerInvoker: the arguments a disposer receives, and a disposer that throws")
class DisposerInvocationTest {

    static final Map<String, Object> RECEIVED = new ConcurrentHashMap<>();
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    public static class Widget {
    }

    public static class Gizmo {
    }

    public static class Gadget {
    }

    @Dependent
    public static class Helper {
        public String help() {
            return "helped";
        }
    }

    @Dependent
    public static class EventSink {
        public void onDisposal(@Observes String message) {
            EVENTS.add(message);
        }
    }

    @Dependent
    public static class WidgetFactory {

        @Produces
        public Widget widget() {
            return new Widget();
        }

        public void dispose(@Disposes Widget widget, Event<String> events, Instance<Helper> helpers,
                            Instance<List<String>> lists,
                            BeanManager beanManager, Helper helper) {
            RECEIVED.put("widget", widget);
            RECEIVED.put("helpers", helpers);
            RECEIVED.put("lists", lists);
            RECEIVED.put("beanManager", beanManager);
            RECEIVED.put("helper", helper);
            events.fire("disposed");
        }
    }

    @Dependent
    public static class GizmoFactory {

        @Produces
        public Gizmo gizmo() {
            return new Gizmo();
        }

        public void dispose(@Disposes Gizmo gizmo) {
            RECEIVED.put("gizmo", gizmo);
            throw new IllegalStateException("cannot dispose of the gizmo");
        }
    }

    /** Not enabled: its producer is no bean, so its disposer is wired to nothing. */
    @Alternative
    @Dependent
    public static class DisabledGadgetFactory {

        @Produces
        public Gadget gadget() {
            return new Gadget();
        }

        public void dispose(@Disposes Gadget gadget) {
            RECEIVED.put("gadget", gadget);
        }
    }

    /** Not a bean: the container has no instance to call its disposer method on. */
    public static class Bystander {
        public void dispose(Widget widget) {
            RECEIVED.put("bystander", widget);
        }
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
        EVENTS.clear();
    }

    private static VaubanContainer boot() {
        return VaubanContainer.builder()
                .addBeanClass(Helper.class)
                .addBeanClass(EventSink.class)
                .addBeanClass(WidgetFactory.class)
                .addBeanClass(GizmoFactory.class)
                .addBeanClass(DisabledGadgetFactory.class)
                .build();
    }

    @Test
    @DisplayName("a disposer gets an Event, Instances of a class and of a parameterized type, the BeanManager and a bean")
    void disposerArguments() {
        try (var container = boot()) {
            var widgets = container.getBeanManager().createInstance().select(Widget.class);
            var widget = widgets.get();

            widgets.destroy(widget);

            assertEquals(widget, RECEIVED.get("widget"));
            assertEquals(List.of("disposed"), List.copyOf(EVENTS), "the Event parameter must reach the observer");
            var helpers = assertInstanceOf(Instance.class, RECEIVED.get("helpers"));
            assertEquals("helped", ((Helper) helpers.get()).help());
            assertInstanceOf(Instance.class, RECEIVED.get("lists"));
            assertInstanceOf(BeanManager.class, RECEIVED.get("beanManager"));
            assertEquals("helped", assertInstanceOf(Helper.class, RECEIVED.get("helper")).help());
        }
    }

    @Test
    @DisplayName("an exception thrown by a disposer is suppressed")
    void throwingDisposerIsSuppressed() {
        try (var container = boot()) {
            var gizmos = container.getBeanManager().createInstance().select(Gizmo.class);
            var gizmo = gizmos.get();

            gizmos.destroy(gizmo);

            assertEquals(gizmo, RECEIVED.get("gizmo"), "the disposer must have been called");
        }
    }

    @Test
    @DisplayName("the producer of a disabled alternative is no bean, and the container still boots")
    void disabledProducerHasNoDisposer() {
        try (var container = boot()) {
            assertNotNull(container.select(Widget.class));
            assertTrue(container.getBeanManager().getBeans(Gadget.class).isEmpty());
        }
    }

    private static io.vidocq.vauban.core.bean.model.DisposerDescriptor disposerOf(Class<?> declaring, String method) {
        return new io.vidocq.vauban.core.bean.model.DisposerDescriptor(
                io.vidocq.vauban.indexer.model.DotName.of(declaring.getName()), method,
                new io.vidocq.vauban.indexer.model.TypeInfo.ClassType(
                        io.vidocq.vauban.indexer.model.DotName.of(Widget.class.getName())),
                java.util.Set.of(), 0, List.of());
    }

    @Test
    @DisplayName("a disposer method that cannot be found, or declared by a class that is no bean, is not called")
    void disposersCalledDirectly() {
        try (var container = boot()) {
            var invoker = new DisposerInvoker(container, container.getVaubanLookup());
            var widget = new Widget();

            invoker.callDisposer(widget, disposerOf(WidgetFactory.class, "absent"));
            invoker.callDisposer(widget, disposerOf(Bystander.class, "dispose"));

            assertEquals(java.util.Map.of(), java.util.Map.copyOf(RECEIVED), "neither disposer can be called, and nothing escapes");
        }
    }
}
