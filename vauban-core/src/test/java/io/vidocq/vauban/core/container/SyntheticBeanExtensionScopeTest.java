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

import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261113-01: a synthetic bean an extension declares in a normal scope of its own, the way Jakarta EE 11 Platform
 * §13.12.1 puts a persistence unit's {@code EntityManager} in {@code jakarta.transaction.TransactionScoped}, lives in
 * that scope's context. Its scope fell back to {@code @Dependent}, so each lookup created a new instance.
 */
@DisplayName("BUG-20261113-01 - a synthetic bean in the normal scope of an extension")
class SyntheticBeanExtensionScopeTest {

    @NormalScope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Ledgered {
    }

    /** One instance per bean for the whole container, as long as the scope is active. */
    public static class LedgeredContext implements AlterableContext {
        private final Map<Contextual<?>, Object> instances = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return Ledgered.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) instances.computeIfAbsent(contextual, c -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) instances.get(contextual);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            instances.remove(contextual);
        }
    }

    public interface Counter {
        int next();
    }

    public static class CounterCreator implements SyntheticBeanCreator<Counter> {
        @Override
        public Counter create(Instance<Object> lookup, Parameters params) {
            var count = new AtomicInteger();
            return count::incrementAndGet;
        }
    }

    public static class LedgeredExtension implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(Ledgered.class, LedgeredContext.class);
        }

        @Synthesis
        public void synthesise(SyntheticComponents components) {
            components.addBean(Counter.class).type(Counter.class).scope(Ledgered.class).createWith(CounterCreator.class);
        }
    }

    @Test
    @DisplayName("the bean keeps the scope of the extension and its lookups share the instance of its context")
    void theBeanKeepsTheScopeOfTheExtensionAndLivesInItsContext() {
        try (var container = VaubanContainer.builder().addBeanClass(LedgeredExtension.class).build()) {
            var manager = container.getBeanManager();

            assertEquals(Ledgered.class, manager.resolve(manager.getBeans(Counter.class)).getScope());
            Counter first = manager.createInstance().select(Counter.class).get();
            Counter second = manager.createInstance().select(Counter.class).get();
            assertEquals(1, first.next());
            assertEquals(2, second.next(), "one contextual instance in the extension's context, not one per lookup");
        }
    }
}
