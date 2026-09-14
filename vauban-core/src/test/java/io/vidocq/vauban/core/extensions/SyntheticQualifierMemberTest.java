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
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A synthetic bean or observer is qualified through {@link AnnotationBuilder}, the only way an
 * extension can write an annotation. The member values it writes must reach resolution: a synthetic
 * bean qualified {@code @Channel("alpha")} answers that lookup and no other, and a synthetic observer
 * qualified the same way receives that event and no other.
 *
 * <p>The qualifiers the test selects and fires with are read from {@link Qualifiers}, so each one is
 * built by the JDK.
 */
@DisplayName("vauban#70 safety net: a synthetic bean or observer keeps its qualifier's member values")
class SyntheticQualifierMemberTest {

    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Channel {
        String value();
    }

    public record Ping(String text) {
    }

    /** Carries the qualifiers the test selects and fires with. Never a bean. */
    static final class Qualifiers {
        @Channel("alpha") void alpha() {}

        @Channel("beta") void beta() {}
    }

    public static class AlphaCreator implements SyntheticBeanCreator<String> {
        @Override
        public String create(Instance<Object> lookup, Parameters params) {
            return "alpha-bean";
        }
    }

    public static class RecordingObserver implements SyntheticObserver<Ping> {
        @Override
        public void observe(EventContext<Ping> event, Parameters params) {
            RECEIVED.add("synthetic:" + event.getEvent().text());
        }
    }

    @Dependent
    public static class Emitter {
        @Inject public Event<Ping> event;
    }

    public static class SyntheticBce implements BuildCompatibleExtension {
        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(String.class)
                    .type(String.class)
                    .qualifier(AnnotationBuilder.of(Channel.class).member("value", "alpha").build())
                    .scope(Dependent.class)
                    .createWith(AlphaCreator.class);
            components.addObserver(Ping.class)
                    .observeWith(RecordingObserver.class)
                    .qualifier(AnnotationBuilder.of(Channel.class).member("value", "alpha").build());
        }
    }

    private static Annotation qualifier(String carrierMethod) {
        try {
            return Qualifiers.class.getDeclaredMethod(carrierMethod).getDeclaredAnnotations()[0];
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no carrier method " + carrierMethod, e);
        }
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    @Test
    @DisplayName("a synthetic bean answers the lookup its qualifier's member value selects")
    void syntheticBeanKeepsItsQualifierMembers() {
        try (var container = VaubanContainer.builder().addBeanClass(SyntheticBce.class).build()) {
            assertEquals("alpha-bean", CDI.current().select(String.class, qualifier("alpha")).get());
        }
    }

    @Test
    @DisplayName("another member value selects no synthetic bean")
    void anotherMemberValueSelectsNoSyntheticBean() {
        try (var container = VaubanContainer.builder().addBeanClass(SyntheticBce.class).build()) {
            var instance = CDI.current().select(String.class, qualifier("beta"));
            assertThrows(UnsatisfiedResolutionException.class, instance::get);
        }
    }

    @Test
    @DisplayName("a synthetic observer receives only the event whose qualifier member matches")
    void syntheticObserverKeepsItsQualifierMembers() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(SyntheticBce.class)
                .addBeanClass(Emitter.class)
                .build()) {
            var emitter = container.select(Emitter.class);
            emitter.event.select(qualifier("alpha")).fire(new Ping("1"));
            emitter.event.select(qualifier("beta")).fire(new Ping("2"));
        }

        assertEquals(List.of("synthetic:1"), RECEIVED);
    }
}
