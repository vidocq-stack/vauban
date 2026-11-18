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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Intercepted;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The bean metadata beans of CDI 4.1, 5.5.8, in CDI Lite: a bean gets its own {@code Bean}, an interceptor its own
 * {@code Interceptor} and the {@code @Intercepted Bean} of the bean it intercepts, at every kind of injection point
 * (BUG-20261117-47). {@code VetoedInterceptorCompileTimeTest}, in {@code vauban-processor}, checks the processor, the
 * start and a frozen start; this test covers the reflective injection of classes no processor compiled.
 */
@DisplayName("Bean metadata beans")
class BeanMetadataTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Watched {
    }

    /** Its own metadata through a constructor, a field and an initializer. */
    @Dependent
    public static class SelfAware {
        final Bean<SelfAware> constructed;
        @Inject
        Bean<SelfAware> field;
        Bean<SelfAware> initialized;

        @Inject
        public SelfAware(Bean<SelfAware> constructed) {
            this.constructed = constructed;
        }

        @Inject
        void initialize(Bean<SelfAware> metadata) {
            initialized = metadata;
        }
    }

    /** Its own metadata through its constructor, and that of the bean it intercepts through a field. */
    @Watched
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class Watcher {
        private final Interceptor<Watcher> self;
        @Inject
        @Intercepted
        Bean<?> intercepted;

        @Inject
        public Watcher(Interceptor<Watcher> self) {
            this.self = self;
        }

        @AroundInvoke
        Object around(InvocationContext invocation) throws Exception {
            return self.getBeanClass().getSimpleName() + "/" + intercepted.getBeanClass().getSimpleName() + ": "
                    + invocation.proceed();
        }
    }

    @Watched
    @Dependent
    public static class FirstService {
        public String hello() {
            return "first";
        }
    }

    @Watched
    @ApplicationScoped
    public static class SecondService {
        public String hello() {
            return "second";
        }
    }

    @Test
    @DisplayName("a bean gets its own Bean at its constructor, a field and an initializer")
    void aBeanGetsItsOwnBean() {
        try (var container = VaubanContainer.builder().addBeanClass(SelfAware.class).build()) {
            var bean = container.getBeanManager().resolve(container.getBeanManager().getBeans(SelfAware.class));
            var selfAware = container.select(SelfAware.class);

            assertSame(bean, selfAware.constructed);
            assertSame(bean, selfAware.field);
            assertSame(bean, selfAware.initialized);
        }
    }

    @Test
    @DisplayName("an interceptor gets its own Interceptor and the Bean of each bean it intercepts")
    void anInterceptorGetsItsOwnInterceptorAndTheInterceptedBean() {
        try (var container = VaubanContainer.builder().addBeanClass(Watcher.class).addBeanClass(FirstService.class)
                .addBeanClass(SecondService.class).build()) {
            assertEquals("Watcher/FirstService: first", container.select(FirstService.class).hello());
            assertEquals("Watcher/SecondService: second", container.select(SecondService.class).hello());
        }
    }

    @Test
    @DisplayName("outside the creation of an interceptor, the Interceptor and @Intercepted Bean beans give nothing")
    void outsideTheCreationOfAnInterceptorTheMetadataBeansGiveNothing() {
        try (var container = VaubanContainer.builder().addBeanClass(SelfAware.class).build()) {
            var manager = container.getBeanManager();
            var interceptor = manager.resolve(manager.getBeans(Interceptor.class));
            var intercepted = manager.resolve(manager.getBeans(Bean.class, BeanMetadata.INTERCEPTED_LITERAL));

            assertNull(manager.getReference(interceptor, Interceptor.class,
                    manager.createCreationalContext(interceptor)));
            assertNull(manager.getReference(intercepted, Bean.class, manager.createCreationalContext(intercepted)));
        }
    }
}
