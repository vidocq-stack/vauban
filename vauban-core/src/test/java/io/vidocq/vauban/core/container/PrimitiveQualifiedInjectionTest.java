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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for VAU-INJ-PRIM: a primitive injection point ({@code boolean}/{@code int}/…) must be
 * exposed to a Build Compatible Extension as a {@link PrimitiveType} (CDI Lite lang model), so a
 * portable extension can detect it with {@code instanceof PrimitiveType} and box it into a synthetic
 * bean of the wrapper type.
 *
 * <p>Before fix: {@code BeanInfo.injectionPoints().get(i).type()} returned a {@code ClassType} named
 * "boolean" instead of a {@code PrimitiveType} (the indexer encodes primitives as named class types,
 * and {@code TypeMapper} forwarded that verbatim). An extension boxing on {@code instanceof
 * PrimitiveType} therefore never boxed, registered the synthetic bean under the bogus
 * {@code ClassType[boolean]}, and the primitive field silently stayed at its default. This is the gap
 * surfaced by Cervantes (MicroProfile JWT) {@code @Claim boolean}.</p>
 */
@DisplayName("VAU-INJ-PRIM - primitive injection point is a PrimitiveType in the BCE lang model")
class PrimitiveQualifiedInjectionTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
    public @interface Tag {
        @Nonbinding String value() default "";
    }

    // --- Guard: producer-backed wrapper beans already worked ---

    @Dependent
    public static class FlagProducer {
        @Produces @Tag public Boolean flag() { return Boolean.TRUE; }
    }

    @Dependent
    public static class ProducerConsumer {
        @Inject @Tag("a") public boolean primitiveFlag;
    }

    @Test
    @DisplayName("primitive field receives a wrapper-typed qualified PRODUCER value (guard)")
    void primitiveQualifiedProducerIsInjected() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(FlagProducer.class)
                .addBeanClass(ProducerConsumer.class)
                .build()) {
            assertTrue(container.select(ProducerConsumer.class).primitiveFlag, "@Inject @Tag boolean (producer)");
        }
    }

    // --- The regression: synthetic beans driven by the @Registration lang model (Cervantes shape) ---

    /** Records the lang-model type seen for each @Tag injection point, so the test can assert it. */
    static final AtomicReference<Map<String, Type>> SEEN = new AtomicReference<>(Map.of());

    public static class TrueCreator implements SyntheticBeanCreator<Boolean> {
        @Override public Boolean create(Instance<Object> lookup, Parameters params) { return Boolean.TRUE; }
    }

    public static class SevenCreator implements SyntheticBeanCreator<Integer> {
        @Override public Integer create(Instance<Object> lookup, Parameters params) { return 7; }
    }

    /**
     * Mirrors {@code io.vidocq.cervantes.cdi.CervantesClaimExtension}: collect every {@code @Tag}
     * injection-point type during {@code @Registration}, then in {@code @Synthesis} box primitive
     * types (detected with {@code instanceof PrimitiveType}) and register a wrapper-typed synthetic
     * bean per type.
     */
    public static class BoxingBce implements BuildCompatibleExtension {
        private final Map<String, Type> collected = new LinkedHashMap<>();

        @Registration(types = Object.class)
        public void collect(BeanInfo bean) {
            for (var ip : bean.injectionPoints()) {
                boolean tagged = ip.qualifiers().stream().anyMatch(q -> q.name().equals(Tag.class.getName()));
                if (tagged) {
                    collected.putIfAbsent(ip.type().toString(), ip.type());
                }
            }
            if (!collected.isEmpty()) {
                SEEN.set(Map.copyOf(collected));
            }
        }

        @Synthesis
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void synthesize(SyntheticComponents components, Types types) {
            for (Type t : collected.values()) {
                if (t instanceof PrimitiveType pt) {
                    Class<?> boxed = box(pt.primitiveKind());
                    SyntheticBeanBuilder b = components.addBean(boxed);
                    b.type(boxed).qualifier(Tag.class).scope(Dependent.class)
                            .createWith(boxed == Boolean.class ? TrueCreator.class : SevenCreator.class);
                }
            }
        }

        private static Class<?> box(PrimitiveType.PrimitiveKind kind) {
            return switch (kind) {
                case BOOLEAN -> Boolean.class;
                case INT -> Integer.class;
                default -> Object.class;
            };
        }
    }

    @Dependent
    public static class SyntheticConsumer {
        @Inject @Tag("active") public boolean primitiveFlag;
        @Inject @Tag("count") public int primitiveCount;
    }

    @Test
    @DisplayName("primitive injection points are PrimitiveType and box into wrapper synthetic beans")
    void primitiveInjectionPointIsPrimitiveTypeAndBoxes() {
        SEEN.set(Map.of());
        try (var container = VaubanContainer.builder()
                .addBeanClass(BoxingBce.class)
                .addBeanClass(SyntheticConsumer.class)
                .build()) {

            SyntheticConsumer consumer = container.select(SyntheticConsumer.class);

            // (1) The lang model must expose primitive IP types as PrimitiveType.
            Type booleanType = SEEN.get().get("boolean");
            assertNotNull(booleanType, "the boolean injection point type must be collected");
            assertTrue(booleanType instanceof PrimitiveType,
                    "boolean injection point must be a PrimitiveType in the BCE lang model, was "
                            + booleanType.getClass().getSimpleName());

            // (2) End-to-end: the boxed synthetic bean injects into the primitive field.
            assertTrue(consumer.primitiveFlag, "@Inject @Tag boolean must be injected (was silently false)");
            assertEquals(7, consumer.primitiveCount, "@Inject @Tag int must be injected (was silently 0)");
        }
    }
}
