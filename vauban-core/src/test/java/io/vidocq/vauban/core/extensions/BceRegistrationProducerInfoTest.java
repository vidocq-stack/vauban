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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CDI 4.1 gives the {@code BeanInfo} of a producer bean the method or the field that declares it, and the disposer
 * method of the producer, and the {@code BeanInfo} of every bean its stereotypes. The container gave {@code null} for
 * the three members and no stereotype (BUG-20261117-13).
 */
@DisplayName("@Registration with a BeanInfo parameter gives a bean its member, its disposer and its stereotypes "
        + "(BUG-20261117-13)")
class BceRegistrationProducerInfoTest {

    /** A stereotype that declares each thing a {@code StereotypeInfo} gives. */
    @Stereotype
    @ApplicationScoped
    @Alternative
    @Priority(7)
    @Named
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Showcase {
    }

    /** A stereotype that declares nothing but itself. */
    @Stereotype
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Plain {
    }

    @Dependent
    public static class Producers {

        @Produces
        Long level = 1L;

        @Produces
        @Plain
        String name() {
            return "name";
        }

        void release(@Disposes String name) {
            // Nothing to release: the disposer is only looked at.
        }
    }

    @Showcase
    public static class Shown {
    }

    /** Records the member, the disposer and the stereotypes of each bean of a type it names. */
    public static class ProducerDescriber implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = {String.class, Long.class, Shown.class})
        public void producers(BeanInfo bean) {
            var disposer = bean.disposer() == null ? "no disposer" : "disposer " + bean.disposer().disposerMethod()
                    .name() + "(" + bean.disposer().disposedParameter().type().asClass().declaration().simpleName()
                    + ")";
            var stereotypes = bean.stereotypes().stream().map(ProducerDescriber::described)
                    .collect(Collectors.joining(", ", "[", "]"));
            if (bean.isProducerMethod()) {
                SEEN.add("method " + bean.producerMethod().name() + ", " + disposer + ", " + stereotypes);
            } else if (bean.isProducerField()) {
                SEEN.add("field " + bean.producerField().name() + ", " + disposer + ", " + stereotypes);
            } else {
                SEEN.add("class " + bean.declaringClass().simpleName() + ", " + disposer + ", " + stereotypes);
            }
        }

        private static String described(StereotypeInfo stereotype) {
            return (stereotype.defaultScope() == null ? "no scope" : stereotype.defaultScope().name())
                    + (stereotype.isAlternative() ? " alternative" : "") + " priority " + stereotype.priority()
                    + (stereotype.isNamed() ? " named" : "") + " bindings " + stereotype.interceptorBindings().size();
        }
    }

    @Test
    @DisplayName("the BeanInfo of a bean gives its producer method or field, its disposer and its stereotypes")
    void theBeanInfoOfAProducerGivesItsMethodOrField() {
        ProducerDescriber.SEEN.clear();

        try (var _ = SeContainerInitializer.newInstance()
                .addBeanClasses(Producers.class, Shown.class, ProducerDescriber.class).initialize()) {
            assertEquals(List.of(
                    "class Shown, no disposer, [jakarta.enterprise.context.ApplicationScoped alternative priority 7"
                            + " named bindings 0]",
                    "field level, no disposer, []",
                    "method name, disposer release(String), [no scope priority null bindings 0]"),
                    ProducerDescriber.SEEN.stream().sorted().toList());
        }
    }
}
