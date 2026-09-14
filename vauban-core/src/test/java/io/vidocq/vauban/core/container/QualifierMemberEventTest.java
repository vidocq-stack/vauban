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
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Safety net for vauban#70: qualifier members on observer resolution. Each scenario boots only its
 * own listener, so an observer that matched too broadly shows up as an extra entry.
 *
 * <p>The event qualifiers are read from {@link Qualifiers}, so each one is built by the JDK. A test
 * disabled with a BUG id reproduces a defect logged in {@code BUG.md}.
 */
@DisplayName("vauban#70 safety net: qualifier members in observer resolution")
class QualifierMemberEventTest {

    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    public record Ping(String text) {
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Channel {
        String value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Routed {
        String value();

        @Nonbinding String note() default "";
    }

    /** A qualifier only through {@link StreamBce}, which also makes {@code value} non-binding. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Stream {
        String value();

        String group();
    }

    /** Carries the event qualifiers. Never a bean. */
    static final class Qualifiers {
        @Channel("alpha") void alpha() {}

        @Routed(value = "r", note = "event") void routed() {}

        @Stream(value = "event", group = "g") void stream() {}

        @Stream(value = "observer", group = "g") void streamIdentical() {}
    }

    @Dependent
    public static class Emitter {
        @Inject public Event<Ping> event;
    }

    @ApplicationScoped
    public static class ChannelListener {
        public void alpha(@Observes @Channel("alpha") Ping ping) {
            RECEIVED.add("alpha:" + ping.text());
        }

        public void beta(@Observes @Channel("beta") Ping ping) {
            RECEIVED.add("beta:" + ping.text());
        }
    }

    @ApplicationScoped
    public static class RoutedListener {
        public void routed(@Observes @Routed(value = "r", note = "observer") Ping ping) {
            RECEIVED.add("routed:" + ping.text());
        }
    }

    @ApplicationScoped
    public static class AsyncChannelListener {
        public void alpha(@ObservesAsync @Channel("alpha") Ping ping) {
            RECEIVED.add("async-alpha:" + ping.text());
        }

        public void beta(@ObservesAsync @Channel("beta") Ping ping) {
            RECEIVED.add("async-beta:" + ping.text());
        }
    }

    public static class StreamBce implements BuildCompatibleExtension {
        @Discovery
        public void register(MetaAnnotations meta) {
            meta.addQualifier(Stream.class).methods().stream()
                    .filter(method -> method.info().name().equals("value"))
                    .forEach(method -> method.addAnnotation(Nonbinding.class));
        }
    }

    @ApplicationScoped
    public static class StreamListener {
        public void grouped(@Observes @Stream(value = "observer", group = "g") Ping ping) {
            RECEIVED.add("stream:" + ping.text());
        }
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    private static VaubanContainer boot(Class<?>... classes) {
        var builder = VaubanContainer.builder();
        builder.addBeanClass(Emitter.class);
        for (var type : classes) {
            builder.addBeanClass(type);
        }
        return builder.build();
    }

    private static Annotation qualifier(String carrierMethod) {
        try {
            return Qualifiers.class.getDeclaredMethod(carrierMethod).getDeclaredAnnotations()[0];
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no carrier method " + carrierMethod, e);
        }
    }

    @Test
    @DisplayName("a synchronous event reaches only the observer whose member value matches")
    void synchronousMemberValue() {
        try (var container = boot(ChannelListener.class)) {
            container.select(Emitter.class).event.select(qualifier("alpha")).fire(new Ping("1"));
        }
        assertEquals(List.of("alpha:1"), RECEIVED);
    }

    @Test
    @DisplayName("a @Nonbinding member does not take part in observer resolution")
    void synchronousNonbindingMember() {
        try (var container = boot(RoutedListener.class)) {
            container.select(Emitter.class).event.select(qualifier("routed")).fire(new Ping("2"));
        }
        assertEquals(List.of("routed:2"), RECEIVED);
    }

    @Test
    @DisplayName("an asynchronous event reaches only the observer whose member value matches")
    @Disabled("BUG-20260914-06: asynchronous observers ignore qualifier member values")
    void asynchronousMemberValue() throws Exception {
        try (var container = boot(AsyncChannelListener.class)) {
            container.select(Emitter.class).event.select(qualifier("alpha")).fireAsync(new Ping("3"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of("async-alpha:3"), RECEIVED);
    }

    @Test
    @DisplayName("control: a qualifier declared by an extension reaches its observer when every member is equal")
    void extensionQualifierIdenticalMembers() {
        try (var container = boot(StreamBce.class, StreamListener.class)) {
            container.select(Emitter.class).event.select(qualifier("streamIdentical")).fire(new Ping("5"));
        }
        assertEquals(List.of("stream:5"), RECEIVED);
    }

    @Test
    @DisplayName("a member made non-binding by an extension does not take part in observer resolution")
    @Disabled("BUG-20260914-07: observers ignore members an extension made non-binding")
    void extensionNonbindingMember() {
        try (var container = boot(StreamBce.class, StreamListener.class)) {
            container.select(Emitter.class).event.select(qualifier("stream")).fire(new Ping("4"));
        }
        assertEquals(List.of("stream:4"), RECEIVED);
    }
}
