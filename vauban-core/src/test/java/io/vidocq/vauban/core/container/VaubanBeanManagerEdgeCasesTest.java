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

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaubanBeanManager} at its edges: a scope with no context, a {@code @PreDestroy} that fails through an
 * injection target, and array injection points whose generic component the bean type's erased array class matches
 * only when every type argument is unbounded.
 */
@DisplayName("VaubanBeanManager: missing contexts, failing @PreDestroy and generic array types")
class VaubanBeanManagerEdgeCasesTest {

    @NormalScope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Unregistered {
    }

    public static class Fragile {
        @PreDestroy
        void shutdown() {
            throw new IllegalStateException("cannot shut down");
        }
    }

    @Dependent
    public static class ArrayProducer {
        @Produces
        @SuppressWarnings("rawtypes")
        public Class[] rawClasses() {
            return new Class[]{String.class};
        }
    }

    private static VaubanContainer boot() {
        return VaubanContainer.builder().addBeanClass(ArrayProducer.class).build();
    }

    @Test
    @DisplayName("a scope no context is registered for has no context")
    void scopeWithoutContext() {
        try (var container = boot()) {
            var beanManager = container.getBeanManager();

            var thrown = assertThrows(ContextNotActiveException.class, () -> beanManager.getContext(Unregistered.class));

            assertTrue(thrown.getMessage().startsWith("No context for scope"), thrown.getMessage());
            assertEquals(List.of(), List.copyOf(beanManager.getContexts(Unregistered.class)));
        }
    }

    @Test
    @DisplayName("a failing @PreDestroy called through an injection target is a lifecycle callback failure")
    void failingPreDestroyThroughInjectionTarget() {
        try (var container = boot()) {
            var beanManager = container.getBeanManager();
            var target = beanManager.getInjectionTargetFactory(beanManager.createAnnotatedType(Fragile.class))
                    .createInjectionTarget(null);
            var fragile = new Fragile();

            var thrown = assertThrows(LifecycleCallbackException.class, () -> target.preDestroy(fragile));

            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
    }

    @Test
    @DisplayName("an array of a class with only unbounded arguments matches the erased array bean type")
    void unboundedGenericArrays() {
        try (var container = boot()) {
            var beanManager = container.getBeanManager();

            assertEquals(1, beanManager.getBeans(new TypeLiteral<Class<?>[]>() { }.getType()).size());
            assertEquals(1, beanManager.getBeans(new TypeLiteral<Class<Object>[]>() { }.getType()).size());
        }
    }

    @Test
    @DisplayName("an array of a class with a bounded argument, of arrays, or of a type variable matches no erased bean type")
    <T> void boundedGenericArrays() {
        try (var container = boot()) {
            var beanManager = container.getBeanManager();

            assertTrue(beanManager.getBeans(new TypeLiteral<Class<? super Integer>[]>() { }.getType()).isEmpty());
            assertTrue(beanManager.getBeans(new TypeLiteral<Class<String>[]>() { }.getType()).isEmpty());
            assertTrue(beanManager.getBeans(new TypeLiteral<Class<?>[][]>() { }.getType()).isEmpty());
            assertTrue(beanManager.getBeans(new TypeLiteral<T[]>() { }.getType()).isEmpty());
        }
    }
}
