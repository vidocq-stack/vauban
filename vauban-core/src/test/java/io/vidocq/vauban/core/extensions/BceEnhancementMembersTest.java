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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An annotation an {@code @Enhancement} adds keeps its member values: a name, a qualifier member
 * (BUG-20261008-05).
 */
@DisplayName("BCE @Enhancement - added annotations keep their members")
class BceEnhancementMembersTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Channel {
        String value();

        final class Literal extends AnnotationLiteral<Channel> implements Channel {
            private final String value;

            Literal(String value) {
                this.value = value;
            }

            @Override
            public String value() {
                return value;
            }
        }
    }

    @Dependent
    public static class Named1 {
    }

    @Dependent
    public static class DefaultNamed {
    }

    public interface Sink {
    }

    @Dependent
    public static class AlphaSink implements Sink {
    }

    @Dependent
    public static class BetaSink implements Sink {
    }

    @Dependent
    public static class Client {
        @Inject
        @Channel("beta")
        Sink sink;
    }

    /** Its field gets its qualifier from an extension. */
    @Dependent
    public static class FieldClient {
        @Inject
        Sink sink;
    }

    /** Listed first, so it also checks that @Registration sees what the other extension added. */
    public static class RecordingBce implements BuildCompatibleExtension {
        static final Map<String, String> NAMES = new ConcurrentHashMap<>();

        @Registration(types = {Named1.class, DefaultNamed.class})
        public void registration(BeanInfo bean) {
            NAMES.put(bean.declaringClass().simpleName(), String.valueOf(bean.name()));
        }
    }

    public static class AnnotatingBce implements BuildCompatibleExtension {
        @Enhancement(types = Named1.class)
        public void name(ClassConfig clazz) {
            clazz.addAnnotation(NamedLiteral.of("enhanced"));
        }

        @Enhancement(types = DefaultNamed.class)
        public void defaultName(ClassConfig clazz) {
            clazz.addAnnotation(Named.class);
        }

        @Enhancement(types = AlphaSink.class)
        public void alpha(ClassConfig clazz) {
            clazz.addAnnotation(new Channel.Literal("alpha"));
        }

        @Enhancement(types = FieldClient.class)
        public void fieldQualifier(ClassConfig clazz) {
            clazz.fields().forEach(field -> field.addAnnotation(new Channel.Literal("alpha")));
        }

        @Enhancement(types = BetaSink.class)
        public void beta(ClassConfig clazz) {
            clazz.addAnnotation(new Channel.Literal("beta"));
        }
    }

    private static jakarta.enterprise.inject.se.SeContainer boot() {
        return SeContainerInitializer.newInstance()
                .addBeanClasses(Named1.class, DefaultNamed.class, AlphaSink.class, BetaSink.class, Client.class,
                        FieldClient.class,
                        RecordingBce.class, AnnotatingBce.class)
                .initialize();
    }

    @Test
    @DisplayName("an added @Named keeps its value, and a member-less one gives the default name")
    void addedNamedKeepsItsValue() {
        RecordingBce.NAMES.clear();
        try (var container = boot()) {
            var bm = container.getBeanManager();
            assertEquals(List.of(Named1.class),
                    bm.getBeans("enhanced").stream().map(b -> (Object) b.getBeanClass()).toList());
            assertEquals(List.of(DefaultNamed.class),
                    bm.getBeans("defaultNamed").stream().map(b -> (Object) b.getBeanClass()).toList());
        }
        assertEquals(Map.of("Named1", "enhanced", "DefaultNamed", "defaultNamed"), RecordingBce.NAMES);
    }

    @Test
    @DisplayName("an added qualifier keeps its members, so two beans it tells apart resolve")
    void addedQualifierKeepsItsMembers() {
        try (var container = boot()) {
            assertInstanceOf(BetaSink.class, container.select(Client.class).get().sink);
            assertInstanceOf(AlphaSink.class, container.select(FieldClient.class).get().sink,
                    "a qualifier an extension adds to a field keeps its members too");
        }
    }
}
