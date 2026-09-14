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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shape in which a qualifier of this module takes part in resolution, run with
 * <strong>{@code -Dvauban.annotations.reflection=forbid}</strong> (set for the whole module in
 * {@code pom.xml}): a field, a constructor parameter, an initializer-method parameter, a producer
 * method and its own parameter, an observer and its non-event parameter, {@code Instance.select},
 * {@code Event}, and {@code Bean#getQualifiers()}.
 *
 * <p>The qualifier is {@link Channel} — <em>package-private</em>, with a binding member, a
 * {@code @Nonbinding} one and one every bean leaves at its default. This module is compiled with
 * the Vauban APT, so it ships what {@code @Channel} declares (vauban#70): the container reads none
 * of that back, and the switch turns any fallback into a failure instead of a silent cost.
 *
 * <p>Nothing here is injected into the test instance — the module has zero {@code opens}, and the
 * point is that it needs none.
 */
@DisplayName("vauban#70: a module's own qualifier resolves with no reflection at all")
class QualifierWithoutReflectionTest {

    private static VaubanContainer container;

    /** Carries the qualifiers the test selects with, built by the JDK from the same declaration. */
    static final class Written {

        @Channel(value = "wire", tier = Tier.PRIORITY)
        void wire() {}

        @Channel
        void defaulted() {}

        @Channel("ledger")
        void ledger() {}

        /** What {@link WirePayment} declares, {@code @Nonbinding} member included. */
        @Channel(value = "wire", tier = Tier.PRIORITY, note = "on the bean")
        void asTheBeanDeclaresIt() {}
    }

    private static Annotation qualifier(String carrier) {
        try {
            return Written.class.getDeclaredMethod(carrier).getDeclaredAnnotations()[0];
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no carrier method " + carrier, e);
        }
    }

    @BeforeAll
    static void boot() {
        container = VaubanContainer.builder()
                .addBeanClass(CardPayment.class)
                .addBeanClass(WirePayment.class)
                .addBeanClass(Checkout.class)
                .addBeanClass(Vault.class)
                .addBeanClass(Auditor.class)
                .addBeanClass(Dispatcher.class)
                .build();
    }

    @AfterAll
    static void shutdown() {
        if (container != null) container.close();
    }

    @Test
    @DisplayName("the switch really is on — otherwise this whole class proves nothing")
    void reflectionIsForbidden() {
        assertEquals("forbid", System.getProperty("vauban.annotations.reflection"),
                "surefire must set it for this module; without it nothing below is a proof");
    }

    @Test
    @DisplayName("a field, a constructor parameter and an initializer parameter all resolve")
    void theThreeInjectionShapes() {
        var checkout = container.select(Checkout.class);

        assertEquals("wire", checkout.fromField(), "field: the @Nonbinding member must not count");
        assertEquals("card", checkout.fromConstructor(), "constructor: members left at their defaults");
        assertEquals("wire", checkout.fromInitializer(), "initializer method parameter");
    }

    @Test
    @DisplayName("a producer method is qualified, and its own parameter is resolved")
    void producerAndItsParameter() {
        var ledger = CDI.current().select(Ledger.class, qualifier("ledger")).get();

        assertEquals("wire", ledger.backedBy(), "the producer's parameter picked the wire bean");
    }

    @Test
    @DisplayName("an observer sees a qualified event, and its other parameter is injected")
    void observerAndItsParameter() {
        Auditor.SEEN.clear();

        container.select(Dispatcher.class).fire("paid");

        assertEquals(Set.of("paid:wire", "any:paid"), Set.copyOf(Auditor.SEEN),
                "the qualified observer matched, the unqualified one always does, "
                        + "and the second parameter resolved");
    }

    /**
     * What a point declares is not what resolution compares: the container completes the latter with
     * {@code @Default} and {@code @Any}, and firing an event with that completed set would reach
     * observers the declaration never asked for. The CDI TCK pins this, and the CI does not run it.
     */
    @Test
    @DisplayName("an unqualified event declares @Default alone, and reaches only what matches it")
    void whatAPointDeclaresIsNotWhatResolutionCompares() {
        Auditor.SEEN.clear();

        container.select(Dispatcher.class).fireAnything("plain");

        assertEquals(List.of("any:plain"), Auditor.SEEN,
                "an event with no qualifier must not reach the @Channel observer");

        var beanManager = CDI.current().getBeanManager();
        var bean = beanManager.resolve(beanManager.getBeans(Dispatcher.class));
        var point = bean.getInjectionPoints().stream()
                .filter(p -> p.getMember() instanceof java.lang.reflect.Field f
                        && "anything".equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no injection point for the plain Event field"));

        assertEquals(Set.of(jakarta.enterprise.inject.Default.Literal.INSTANCE), point.getQualifiers(),
                "getQualifiers() reports what the point declares — @Any is not part of it");
    }

    @Test
    @DisplayName("Instance.select picks on the qualifier the JDK built from the same declaration")
    void programmaticLookup() {
        var dispatcher = container.select(Dispatcher.class);

        assertEquals("wire", dispatcher.select(qualifier("wire")));
        assertEquals("card", dispatcher.select(qualifier("defaulted")));
    }

    @Test
    @DisplayName("Bean#getQualifiers() hands out the module's own literal, equal to the JDK's")
    void beanQualifiers() {
        var beanManager = CDI.current().getBeanManager();
        var bean = beanManager.resolve(beanManager.getBeans(Payment.class, qualifier("wire")));

        var channel = bean.getQualifiers().stream()
                .filter(annotation -> annotation.annotationType() == Channel.class)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no @Channel among " + bean.getQualifiers()));

        // getQualifiers() reports the qualifier as the BEAN declares it, @Nonbinding member and all:
        // @Nonbinding is a resolution rule, not part of the Annotation contract.
        var declared = qualifier("asTheBeanDeclaresIt");
        assertEquals(declared, channel, "equal to the instance the JDK builds from that declaration");
        assertEquals(declared.hashCode(), channel.hashCode(),
                "and hashing like it, so a set holds one of them, not two");
        assertFalse(channel.toString().isEmpty());
    }

    @Test
    @DisplayName("Bean#getInjectionPoints() describes the parameters without reading them back")
    void injectionPointsOfABean() {
        var beanManager = CDI.current().getBeanManager();
        var bean = beanManager.resolve(beanManager.getBeans(Checkout.class));

        var qualifiers = bean.getInjectionPoints().stream()
                .flatMap(point -> point.getQualifiers().stream())
                .filter(annotation -> annotation.annotationType() == Channel.class)
                .map(Channel.class::cast)
                .toList();

        assertEquals(3, qualifiers.size(), "the field, the constructor parameter and the initializer's");
        assertTrue(qualifiers.stream().anyMatch(channel -> "card".equals(channel.value())),
                "the constructor parameter's, at its declared default");
        assertTrue(qualifiers.stream().anyMatch(channel -> channel.tier() == Tier.PRIORITY),
                "and the two that write the enum member out");
    }
}
