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
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * CDI 4.1 §11.1 — {@code CDI.current().select(type, qualifiers...)} must honor
 * the given qualifiers. They used to be silently dropped, so a qualified
 * programmatic lookup either resolved the {@code @Default} bean or failed
 * with {@code UnsatisfiedResolutionException} (seen in the MP Config TCK's
 * {@code ConfigPropertiesTest} programmatic lookups).
 */
@DisplayName("CDI.current().select honors explicit qualifiers")
class VaubanCDISelectQualifierTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Flavored {
    }

    @SuppressWarnings("all")
    public static final class FlavoredLiteral extends AnnotationLiteral<Flavored> implements Flavored {
        public static final FlavoredLiteral INSTANCE = new FlavoredLiteral();
    }

    public interface Dish {
    }

    @Dependent
    public static class PlainDish implements Dish {
    }

    @Flavored
    @Dependent
    public static class SpicyDish implements Dish {
    }

    public interface Echo {
        String value();
    }

    /** Mirrors MP Config's @ConfigProperties creator: reads type + qualifiers from the current IP. */
    public static class EchoCreator implements jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator<Object> {
        @Override
        public Object create(jakarta.enterprise.inject.Instance<Object> lookup,
                             jakarta.enterprise.inject.build.compatible.spi.Parameters params) {
            var ip = lookup.select(jakarta.enterprise.inject.spi.InjectionPoint.class).get();
            String seen = ip.getType().getTypeName() + "|" + (ip.getQualifiers().stream()
                    .anyMatch(q -> q.annotationType() == Flavored.class));
            return (Echo) () -> seen;
        }
    }

    public static class EchoBce implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {
        @jakarta.enterprise.inject.build.compatible.spi.Synthesis
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void synthesize(jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents components) {
            ((jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder) components.addBean(Echo.class))
                    .type(Echo.class)
                    .qualifier(Flavored.class)
                    .scope(Dependent.class)
                    .createWith(EchoCreator.class);
        }
    }

    @Test
    @DisplayName("programmatic lookup exposes a synthetic InjectionPoint with the selected type and qualifiers")
    void programmaticLookupExposesSyntheticInjectionPoint() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(EchoBce.class)
                .build()) {

            Echo echo = CDI.current().select(Echo.class, FlavoredLiteral.INSTANCE).get();
            assertInstanceOf(Echo.class, echo);
            org.junit.jupiter.api.Assertions.assertEquals(
                    Echo.class.getName() + "|true", echo.value(),
                    "the creator must see the selected type and qualifiers on the current InjectionPoint");
        }
    }

    @Test
    @DisplayName("select(Class, qualifier) resolves the qualified bean, not @Default")
    void selectWithQualifierResolvesQualifiedBean() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(PlainDish.class)
                .addBeanClass(SpicyDish.class)
                .build()) {

            Dish dish = CDI.current().select(Dish.class, FlavoredLiteral.INSTANCE).get();
            assertInstanceOf(SpicyDish.class, dish,
                    "select(Dish, @Flavored) must resolve the @Flavored bean");

            Dish plain = CDI.current().select(Dish.class).get();
            assertInstanceOf(PlainDish.class, plain,
                    "select(Dish) without qualifiers must resolve the @Default bean");
        }
    }
}
