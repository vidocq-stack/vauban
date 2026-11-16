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

import io.vidocq.vauban.core.container.BuiltInPassivationTest.Counted;
import io.vidocq.vauban.core.container.BuiltInPassivationTest.Holder;
import io.vidocq.vauban.core.container.BuiltInPassivationTest.Loud;
import io.vidocq.vauban.core.container.BuiltInPassivationTest.LoudHolder;
import io.vidocq.vauban.core.container.BuiltInPassivationTest.Ping;
import io.vidocq.vauban.core.container.BuiltInPassivationTest.Recorder;
import io.vidocq.vauban.core.container.BuiltInPassivationTest.Shelf;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Member;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BeanManager#getInjectableReference} and {@link BeanManager#getReference} give the built-in {@code Event} and
 * {@code Instance} an injection gives: with the type and the qualifiers of the injection point; an observer method's
 * {@code Event} parameter keeps its qualifiers too (BUG-20261116-07).
 */
@DisplayName("getInjectableReference keeps the type and qualifiers of a built-in Event or Instance")
class BuiltInInjectableReferenceTest {

    private final VaubanContainer container = VaubanContainer.builder()
            .addBeanClass(Recorder.class)
            .addBeanClass(Shelf.class)
            .addBeanClass(Counted.class)
            .addBeanClass(Holder.class)
            .addBeanClass(LoudHolder.class)
            .addBeanClass(Relay.class)
            .addBeanClass(PlainRelay.class)
            .addBeanClass(Lookup.class)
            .addBeanClass(Announcer.class)
            .addBeanClass(Speaker.class)
            .addBeanClass(Witness.class)
            .addBeanClass(VetoedAnnouncer.class)
            .addBeanClass(Shouter.class)
            .build();

    @AfterEach
    void close() {
        container.close();
    }

    @Test
    @DisplayName("an Instance<Shelf> field gives an Instance of Shelf")
    void anInstanceFieldKeepsItsType() {
        var instance = (Instance<?>) injectableReference(Holder.class, "shelves");

        assertSame(container.select(Shelf.class), instance.get());
    }

    @Test
    @DisplayName("an Instance<Shelf> constructor parameter gives an Instance of Shelf")
    void anInstanceParameterKeepsItsType() {
        var instance = (Instance<?>) injectableReference(Holder.class, "<init>");

        assertSame(container.select(Shelf.class), instance.get());
    }

    @Test
    @DisplayName("a @Loud Event<Ping> field fires @Loud")
    void aQualifiedEventFieldKeepsItsQualifiers() {
        @SuppressWarnings("unchecked")
        var event = (Event<Ping>) injectableReference(LoudHolder.class, "pings");
        event.fire(new Ping("loud"));

        assertEquals(List.of("loud"), container.select(Recorder.class).loudPings());
    }

    @Test
    @DisplayName("getReference of the built-in Instance bean for Instance<Shelf> gives an Instance of Shelf")
    void getReferenceKeepsTheTypeOfAnInstance() {
        var beanManager = container.getBeanManager();
        var type = new TypeLiteral<Instance<Shelf>>() { }.getType();
        var bean = beanManager.resolve(beanManager.getBeans(type));

        var instance = (Instance<?>) beanManager.getReference(bean, type, beanManager.createCreationalContext(bean));

        assertSame(container.select(Shelf.class), instance.get());
    }

    @Test
    @DisplayName("an observer method's @Loud Event<Pong> parameter fires @Loud")
    void anObserverEventParameterKeepsItsQualifiers() {
        container.getBeanManager().getEvent().select(Ping.class).fire(new Ping("relayed"));

        assertEquals(List.of("relayed"), container.select(Relay.class).loudPongs());
    }

    @Test
    @DisplayName("an observer method's Event parameter, with no injection point, cannot be written and says why")
    void anObserverEventParameterCannotBeWritten() {
        container.getBeanManager().getEvent().select(Ping.class).fire(new Ping("observed"));
        var given = container.select(Relay.class).given();

        var thrown = assertThrows(NotSerializableException.class, () -> write(given));

        assertTrue(thrown.getMessage().contains("no injection point"), thrown.getMessage());
        assertFalse(thrown.getMessage().endsWith("null"), thrown.getMessage());
    }

    @Test
    @DisplayName("an observer method's unqualified Event<Pong> parameter fires @Default, and reaches the default observer")
    void anUnqualifiedObserverEventParameterFiresDefault() {
        container.getBeanManager().getEvent().select(Ping.class).fire(new Ping("relayed"));

        assertEquals(List.of("plain relayed"), container.select(Relay.class).defaultPongs(),
                "PlainRelay's Pong, not Relay's @Loud one");
    }

    @Test
    @DisplayName("an observer method's Instance parameter cannot be written, and says it was given to an observer")
    void anObserverInstanceParameterCannotBeWritten() {
        container.getBeanManager().getEvent().select(Ping.class).fire(new Ping("observed"));
        var given = container.select(Relay.class).givenShelves();

        var thrown = assertThrows(NotSerializableException.class, () -> write(given));

        assertTrue(thrown.getMessage().contains("observer method"), thrown.getMessage());
    }

    @Test
    @DisplayName("getReference ignores the injection point being injected, even one of the same type")
    void getReferenceIgnoresTheCurrentInjectionPoint() throws Exception {
        var beanManager = container.getBeanManager();
        var loud = injectionPoint(LoudHolder.class, "pings");
        var bean = beanManager.resolve(beanManager.getBeans(loud.getType()));

        @SuppressWarnings("unchecked")
        var event = (Event<Ping>) VaubanContainer.callWithInjectionPoint(loud,
                () -> beanManager.getReference(bean, loud.getType(), beanManager.createCreationalContext(bean)));
        event.fire(new Ping("plain"));

        assertEquals(List.of(), container.select(Recorder.class).loudPings(), "getReference knows no @Loud");
        assertEquals(List.of("plain"), container.select(Recorder.class).pings());
    }

    @Test
    @DisplayName("what getReference or an Instance<Event<Ping>> gives is a programmatic lookup: it says so when written")
    void aProgrammaticBuiltInSaysItIsOne() {
        var beanManager = container.getBeanManager();
        var type = new TypeLiteral<Instance<Shelf>>() { }.getType();
        var bean = beanManager.resolve(beanManager.getBeans(type));
        var instance = beanManager.getReference(bean, type, beanManager.createCreationalContext(bean));
        var event = container.select(Lookup.class).events.get();

        var instanceThrown = assertThrows(NotSerializableException.class, () -> write(instance));
        var eventThrown = assertThrows(NotSerializableException.class, () -> write(event));

        assertTrue(instanceThrown.getMessage().startsWith("an Instance or a Provider obtained by select(...), by a"
                + " programmatic lookup"), instanceThrown.getMessage());
        assertTrue(eventThrown.getMessage().startsWith("an Event obtained by select(...) or by a programmatic lookup"),
                eventThrown.getMessage());
    }

    @Test
    @DisplayName("an Event from getReference or from an Instance<Event<Ping>> fires with no injection point")
    void aLookedUpEventFiresWithNoInjectionPoint() {
        var beanManager = container.getBeanManager();
        var type = new TypeLiteral<Event<Ping>>() { }.getType();
        var bean = beanManager.resolve(beanManager.getBeans(type));
        @SuppressWarnings("unchecked")
        var referenced = (Event<Ping>) beanManager.getReference(bean, type, beanManager.createCreationalContext(bean));

        referenced.fire(new Ping("referenced"));
        container.select(Lookup.class).events.get().fire(new Ping("looked up"));

        assertEquals(List.of("referenced", "looked up"), container.select(Witness.class).withoutInjectionPoint());
    }

    @Test
    @DisplayName("a @Loud Instance<Event<Ping>> gives an Event that fires @Loud")
    void aQualifiedInstanceOfEventKeepsItsQualifiers() {
        container.select(Lookup.class).loudEvents.get().fire(new Ping("loud lookup"));

        assertEquals(List.of("loud lookup"), container.select(Recorder.class).loudPings());
    }

    @Test
    @DisplayName("an interceptor's @Loud Event<Ping> constructor parameter fires @Loud")
    void anInterceptorEventConstructorParameterKeepsItsQualifiers() {
        assertEquals("spoken", container.select(Speaker.class).speak());

        assertEquals(List.of("announced"), container.select(Recorder.class).loudPings());
    }

    /**
     * An interceptor with no bean is created by the container itself, outside the bean path. A vetoed one is the only
     * such interceptor an application has, as long as BUG-20261116-12 lets it intercept: when that bug is fixed, this
     * test needs another interceptor with no bean.
     */
    @Test
    @DisplayName("an interceptor with no bean keeps the qualifiers of its @Loud Event<Ping> constructor parameter")
    void anInterceptorWithNoBeanKeepsItsEventQualifiers() {
        assertEquals("shouted", container.select(Shouter.class).shout());

        assertEquals(List.of("shouted"), container.select(Recorder.class).loudPings());
    }

    @Test
    @Disabled("BUG-20261116-12: a @Vetoed interceptor still intercepts; the annotation processor weaves it as well")
    @DisplayName("a vetoed interceptor intercepts nothing")
    void aVetoedInterceptorInterceptsNothing() {
        assertEquals("shouted", container.select(Shouter.class).shout());

        assertEquals(List.of(), container.select(Recorder.class).pings());
    }

    // --- fixtures ---

    public record Pong(String text) {
    }

    /**
     * Relays each {@code Ping} as a {@code @Loud Pong}, through the {@code Event} its observer method is given, and
     * keeps that {@code Event} and the {@code Instance} it is given too.
     */
    @ApplicationScoped
    public static class Relay {

        private final List<String> loudPongs = new CopyOnWriteArrayList<>();

        private final List<String> defaultPongs = new CopyOnWriteArrayList<>();

        private volatile Event<Pong> given;

        private volatile Instance<Shelf> givenShelves;

        void onPing(@Observes Ping ping, @Loud Event<Pong> pongs, Instance<Shelf> shelves) {
            given = pongs;
            givenShelves = shelves;
            pongs.fire(new Pong(ping.text()));
        }

        void onLoudPong(@Observes @Loud Pong pong) {
            loudPongs.add(pong.text());
        }

        void onDefaultPong(@Observes @Default Pong pong) {
            defaultPongs.add(pong.text());
        }

        public Event<Pong> given() {
            return given;
        }

        public Instance<Shelf> givenShelves() {
            return givenShelves;
        }

        public List<String> loudPongs() {
            return List.copyOf(loudPongs);
        }

        public List<String> defaultPongs() {
            return List.copyOf(defaultPongs);
        }
    }

    /** Looks an {@code Event<Ping>} up through the {@code Instance} injected into it, a {@code @Loud} one too. */
    @Dependent
    public static class Lookup {

        @Inject
        Instance<Event<Ping>> events;

        @Inject
        @Loud
        Instance<Event<Ping>> loudEvents;
    }

    /** Records each {@code Ping} whose metadata names no injection point. */
    @ApplicationScoped
    public static class Witness {

        private final List<String> withoutInjectionPoint = new CopyOnWriteArrayList<>();

        void onPing(@Observes Ping ping, EventMetadata metadata) {
            if (metadata.getInjectionPoint() == null) {
                withoutInjectionPoint.add(ping.text());
            }
        }

        public List<String> withoutInjectionPoint() {
            return List.copyOf(withoutInjectionPoint);
        }
    }

    /** Fires a {@code @Loud Ping} on each call it intercepts, through the {@code Event} its constructor is given. */
    @Announced
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Announcer {

        private final Event<Ping> pings;

        @Inject
        public Announcer(@Loud Event<Ping> pings) {
            this.pings = pings;
        }

        @AroundInvoke
        Object announce(InvocationContext invocation) throws Exception {
            pings.fire(new Ping("announced"));
            return invocation.proceed();
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Announced {
    }

    /** {@link Announcer}, vetoed: an interceptor with no bean, which the container creates by itself. */
    @Vetoed
    @Shouted
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class VetoedAnnouncer {

        private final Event<Ping> pings;

        @Inject
        public VetoedAnnouncer(@Loud Event<Ping> pings) {
            this.pings = pings;
        }

        @AroundInvoke
        Object announce(InvocationContext invocation) throws Exception {
            pings.fire(new Ping("shouted"));
            return invocation.proceed();
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Shouted {
    }

    /** A bean whose calls {@link VetoedAnnouncer} intercepts, if a vetoed interceptor intercepts at all. */
    @Shouted
    @Dependent
    public static class Shouter {

        public String shout() {
            return "shouted";
        }
    }

    /** A bean whose calls {@link Announcer} intercepts. */
    @Announced
    @Dependent
    public static class Speaker {

        public String speak() {
            return "spoken";
        }
    }

    /** Relays each {@code Ping} as a {@code Pong} with no qualifier, through its observer method's {@code Event}. */
    @Dependent
    public static class PlainRelay {

        void onPing(@Observes Ping ping, Event<Pong> pongs) {
            pongs.fire(new Pong("plain " + ping.text()));
        }
    }

    // --- helpers ---

    /** What {@code getInjectableReference} gives for the injection point of {@code beanClass} at {@code member}. */
    private Object injectableReference(Class<?> beanClass, String member) {
        var beanManager = container.getBeanManager();
        return beanManager.getInjectableReference(injectionPoint(beanClass, member),
                beanManager.createCreationalContext(null));
    }

    /** The injection point of the bean of {@code beanClass} at {@code member}, which that class declares. */
    private InjectionPoint injectionPoint(Class<?> beanClass, String member) {
        var beanManager = container.getBeanManager();
        var bean = beanManager.resolve(beanManager.getBeans(beanClass));
        return bean.getInjectionPoints().stream()
                .filter(ip -> nameOf(ip.getMember()).equals(member) && ip.getMember().getDeclaringClass() == beanClass)
                .findFirst().orElseThrow();
    }

    private static void write(Object value) throws IOException {
        try (var out = new ObjectOutputStream(OutputStream.nullOutputStream())) {
            out.writeObject(value);
        }
    }

    private static String nameOf(Member member) {
        return member instanceof java.lang.reflect.Constructor<?> ? "<init>" : member.getName();
    }
}
