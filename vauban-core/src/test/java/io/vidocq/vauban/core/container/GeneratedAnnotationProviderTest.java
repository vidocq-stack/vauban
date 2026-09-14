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

import io.vidocq.vauban.api.AnnotationTypeMetadata;
import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.annotation.AnnotationReflection;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A module compiled with the Vauban processor ships what its annotation types declare, a reader that
 * calls their members directly and a literal for each one. The container asks that module first, so a
 * qualifier of a generated module costs no reflection at all: resolution, injection and
 * {@code Bean#getQualifiers()} go through the module's own code.
 *
 * <p>The provider below is written by hand exactly as the processor will render it — vauban#70 PR 4b
 * makes the container consult it; PR 4c generates it. The whole scenario runs under
 * {@code -Dvauban.annotations.reflection=forbid}, so any reflective read of an annotation fails the
 * test rather than passing unnoticed.
 */
@DisplayName("vauban#70: a module's generated provider carries its own annotation types")
class GeneratedAnnotationProviderTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Channel {
        String value() default "card";

        @Nonbinding String note() default "";
    }

    public interface Payment {
        String id();
    }

    /** Qualified by the member's default, which the metadata carries. */
    @Channel
    @Dependent
    public static class CardPayment implements Payment {
        @Override public String id() { return "card"; }
    }

    @Channel("wire")
    @Dependent
    public static class WirePayment implements Payment {
        @Override public String id() { return "wire"; }
    }

    /** Writes the default out, and a {@code @Nonbinding} member the bean does not carry. */
    @Dependent
    public static class Checkout {
        @Inject @Channel(value = "card", note = "checkout") public Payment payment;
    }

    /** Carries the qualifier the programmatic lookup selects with, built by the JDK. */
    static final class Qualifiers {
        @Channel("wire") void wire() {}
    }

    /** The literal the processor renders for {@code @Channel}, in the annotation type's own package. */
    public static final class ChannelLiteral implements Channel {

        private final String value;
        private final String note;

        ChannelLiteral(Map<String, Object> members) {
            this.value = members.containsKey("value") ? (String) members.get("value") : "card";
            this.note = members.containsKey("note") ? (String) members.get("note") : "";
        }

        @Override public String value() { return value; }

        @Override public String note() { return note; }

        @Override public Class<? extends Annotation> annotationType() { return Channel.class; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Channel channel
                    && value.equals(channel.value()) && note.equals(channel.note());
        }

        @Override
        public int hashCode() {
            // Each member's term is parenthesised: `+` binds tighter than `^`.
            return ((127 * "value".hashCode()) ^ value.hashCode())
                    + ((127 * "note".hashCode()) ^ note.hashCode());
        }

        @Override
        public String toString() {
            // Members sorted by name, as AnnotationInstances renders an instance it builds itself.
            return "@" + Channel.class.getCanonicalName() + "(note=\"" + note + "\", value=\"" + value + "\")";
        }
    }

    /** What the processor renders into the module's {@code _VaubanComponents}. */
    static final class GeneratedProvider implements VaubanComponentProvider {

        @Override
        public Object create(String className) {
            return null;
        }

        @Override
        public AnnotationTypeMetadata annotationMetadata(String annotationClassName) {
            if (!Channel.class.getName().equals(annotationClassName)) return null;
            return new AnnotationTypeMetadata(
                    List.of("value", "note"),
                    Map.of("value", String.class, "note", String.class),
                    Map.of("value", "card", "note", ""),
                    Set.of("note"));
        }

        @Override
        public Map<String, Object> readAnnotation(Annotation annotation) {
            if (!(annotation instanceof Channel channel)) return null;
            var members = new LinkedHashMap<String, Object>();
            members.put("value", channel.value());
            members.put("note", channel.note());
            return members;
        }

        @Override
        public Annotation annotationLiteral(String annotationClassName, Map<String, Object> members) {
            return Channel.class.getName().equals(annotationClassName) ? new ChannelLiteral(members) : null;
        }
    }

    private static Annotation wireQualifier() {
        try {
            return Qualifiers.class.getDeclaredMethod("wire").getDeclaredAnnotations()[0];
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no carrier method", e);
        }
    }

    /** Runs {@code action} with the reflection switch set to {@code mode}, and puts it back. */
    private static void withReflection(String mode, Runnable action) {
        var previous = System.getProperty(AnnotationReflection.PROPERTY);
        System.setProperty(AnnotationReflection.PROPERTY, mode);
        try {
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(AnnotationReflection.PROPERTY);
            } else {
                System.setProperty(AnnotationReflection.PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("injection, lookup and getQualifiers() go through it, with no reflection allowed")
    void theGeneratedProviderCarriesTheWholePath() {
        withReflection("forbid", () -> {
            try (var container = VaubanContainer.builder()
                    .addComponentProvider(new GeneratedProvider())
                    .addBeanClass(CardPayment.class)
                    .addBeanClass(WirePayment.class)
                    .addBeanClass(Checkout.class)
                    .build()) {

                // The member default counts as a written value, the @Nonbinding member does not count.
                assertEquals("card", container.select(Checkout.class).payment.id(), "field injection");

                assertEquals("wire", CDI.current().select(Payment.class, wireQualifier()).get().id(),
                        "programmatic lookup");

                var beanManager = CDI.current().getBeanManager();
                var bean = beanManager.resolve(beanManager.getBeans(Payment.class, wireQualifier()));
                var qualifier = bean.getQualifiers().stream()
                        .filter(annotation -> annotation.annotationType() == Channel.class)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no @Channel among " + bean.getQualifiers()));

                assertInstanceOf(ChannelLiteral.class, qualifier, "the module's own literal");
                assertEquals("wire", ((Channel) qualifier).value());
                assertEquals(wireQualifier(), qualifier, "equal to the instance the JDK builds");
                assertEquals(wireQualifier().hashCode(), qualifier.hashCode(),
                        "and hashing like it, so a set of qualifiers holds one of them, not two");
            }
        });
    }

    @Test
    @DisplayName("a qualifier no provider owns still resolves, by reading it")
    void anUnownedQualifierStillResolves() {
        try (var container = VaubanContainer.builder()
                .addComponentProvider(new GeneratedProvider())
                .addBeanClass(CardPayment.class)
                .addBeanClass(WirePayment.class)
                .addBeanClass(Checkout.class)
                .build()) {
            var beanManager = CDI.current().getBeanManager();

            // @Any is a built-in qualifier: no provider owns it, and nothing has to be read for it.
            var beans = beanManager.getBeans(Payment.class, jakarta.enterprise.inject.Any.Literal.INSTANCE);

            assertEquals(2, beans.size(), Objects.toString(beans));
        }
    }
}
