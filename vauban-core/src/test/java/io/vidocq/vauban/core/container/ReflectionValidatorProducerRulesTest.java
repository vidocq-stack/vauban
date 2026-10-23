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
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Definition errors {@link ReflectionValidator} finds on loaded classes: producer types built on type variables or
 * wildcards, initializer methods declaring type parameters, and interceptor bindings that carry the same binding
 * with different values.
 */
@DisplayName("ReflectionValidator: generic producers, generic initializers and conflicting transitive bindings")
class ReflectionValidatorProducerRulesTest {

    @Dependent
    public static class GenericProducers<T> {

        @Produces
        @Singleton
        public <U> List<U> scoped() {
            return List.of();
        }

        @Produces
        @Dependent
        public <U> List<U> dependent() {
            return List.of();
        }

        @Produces
        @Named("named")
        public <U> List<U> named() {
            return List.of();
        }

        @Produces
        @Singleton
        public List<? extends T> bounded() {
            return List.of();
        }

        @Produces
        @Singleton
        public List<? super Integer> lowerBounded() {
            return List.of();
        }

        @Produces
        public List<? super T> lowerVariable() {
            return List.of();
        }

        @Produces
        @SuppressWarnings("unchecked")
        public List<?>[] arrays() {
            return new List[0];
        }

        @Produces
        @SuppressWarnings("unchecked")
        public List<String>[] typedArrays() {
            return new List[0];
        }
    }

    @Dependent
    public static class GenericInitializer {
        @Inject
        public <T> void init() {
            // declares a type parameter: a definition error
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Level {
        String value();
    }

    @InterceptorBinding
    @Level("low")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Quiet {
    }

    @InterceptorBinding
    @Level("low")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Calm {
    }

    @InterceptorBinding
    @Level("high")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Loud {
    }

    @Quiet
    @Loud
    @Dependent
    public static class Conflicted {
    }

    @Quiet
    @Calm
    @Dependent
    public static class Consistent {
    }

    private static List<String> errors(Class<?> beanClass) {
        return ReflectionValidator.validateWithReflection(List.of(beanClass));
    }

    @Test
    @DisplayName("a producer of a type variable type must be @Dependent; a wildcard argument or component is illegal")
    void genericProducers() {
        var errors = errors(GenericProducers.class);

        assertEquals(2, errors.stream().filter(e -> e.contains("TypeVariable return type and non-@Dependent scope"))
                .count(), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains(".scoped") && e.contains("non-@Dependent")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains(".bounded") && e.contains("non-@Dependent")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("array of parameterized type with wildcard")), errors::toString);
        assertFalse(errors.stream().anyMatch(e -> e.contains(".dependent has TypeVariable")), errors::toString);
        assertFalse(errors.stream().anyMatch(e -> e.contains(".lowerBounded has TypeVariable")), errors::toString);
        assertFalse(errors.stream().anyMatch(e -> e.contains(".typedArrays")), errors::toString);
    }

    @Test
    @DisplayName("an initializer method must not declare type parameters")
    void genericInitializer() {
        var errors = errors(GenericInitializer.class);

        assertTrue(errors.stream().anyMatch(e -> e.contains("cannot declare type parameters")), errors::toString);
    }

    @Test
    @DisplayName("two bindings carrying the same binding with different values conflict; with equal values they do not")
    void transitiveBindingValues() {
        var conflicted = errors(Conflicted.class);
        var consistent = errors(Consistent.class);

        assertTrue(conflicted.stream().anyMatch(e -> e.contains("conflicting interceptor binding values for Level")),
                conflicted::toString);
        assertEquals(List.of(), consistent);
    }
}
