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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * VAU-BCE-004 — synthetic beans registered with a runtime array class
 * ({@code SyntheticComponents.addBean(Boolean[].class).type(Boolean[].class)})
 * must be resolvable from injection points of the same array type.
 *
 * <p>MicroProfile Config's {@code ConfigCdiExtension} synthesizes exactly such
 * beans for {@code @ConfigProperty Boolean[]} / {@code boolean[]} /
 * {@code Class[]} injection points (MP Config 3.1 §5.4 array converters). The
 * converter must map {@code Class#getName()} array forms ({@code
 * [Ljava.lang.Boolean;}, {@code [Z}) to the index model's {@code ArrayType},
 * not to a flat {@code ClassType} that can never match the injection point's
 * {@code ArrayType}.</p>
 */
@DisplayName("VAU-BCE-004 — synthetic bean types given as runtime array classes resolve")
class SyntheticArrayBeanTypeTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
    public @interface ArrayQual {
    }

    public static class BoxedArrayCreator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(Instance<Object> lookup, Parameters params) {
            return new Boolean[] {true, false};
        }
    }

    public static class PrimitiveArrayCreator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(Instance<Object> lookup, Parameters params) {
            return new boolean[] {true};
        }
    }

    public static class ClassArrayCreator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(Instance<Object> lookup, Parameters params) {
            return new Class<?>[] {String.class, Integer.class};
        }
    }

    /** Mirrors ConfigCdiExtension's @Synthesis for array-typed @ConfigProperty IPs. */
    public static class ArrayBce implements BuildCompatibleExtension {
        @Synthesis
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void synthesize(SyntheticComponents components) {
            ((jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder)
                    components.addBean(Boolean[].class))
                    .type(Boolean[].class)
                    .qualifier(ArrayQual.class)
                    .scope(Dependent.class)
                    .createWith(BoxedArrayCreator.class);
            ((jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder)
                    components.addBean(boolean[].class))
                    .type(boolean[].class)
                    .qualifier(ArrayQual.class)
                    .scope(Dependent.class)
                    .createWith(PrimitiveArrayCreator.class);
            ((jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder)
                    components.addBean(Class[].class))
                    .type(Class[].class)
                    .qualifier(ArrayQual.class)
                    .scope(Dependent.class)
                    .createWith(ClassArrayCreator.class);
        }
    }

    @Dependent
    public static class ArrayConsumer {
        @Inject
        @ArrayQual
        Boolean[] boxed;

        @Inject
        @ArrayQual
        boolean[] primitive;

        @Inject
        @ArrayQual
        Class<?>[] classes;
    }

    @Test
    @DisplayName("Boolean[], boolean[] and Class[] synthetic bean types satisfy array injection points")
    void arrayTypedSyntheticBeansResolve() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(ArrayBce.class)
                .addBeanClass(ArrayConsumer.class)
                .build()) {

            ArrayConsumer consumer = container.select(ArrayConsumer.class);
            assertNotNull(consumer.boxed, "@Inject @ArrayQual Boolean[] must be satisfied");
            assertArrayEquals(new Boolean[] {true, false}, consumer.boxed);
            assertNotNull(consumer.primitive, "@Inject @ArrayQual boolean[] must be satisfied");
            assertEquals(1, consumer.primitive.length);
            assertNotNull(consumer.classes, "@Inject @ArrayQual Class<?>[] must be satisfied");
            assertEquals(2, consumer.classes.length);
        }
    }
}
