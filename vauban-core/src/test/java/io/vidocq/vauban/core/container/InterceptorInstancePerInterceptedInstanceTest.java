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

import io.vidocq.vauban.core.context.CreationalContextImpl;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Intercepted;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * An interceptor instance is a dependent object of the one instance it intercepts (CDI 4.1, 6.4.1), whatever creational
 * context that instance is created with: two intercepted instances created with one context have an interceptor
 * instance each, whose {@code @Intercepted Bean} is their own bean, and each interceptor instance is destroyed once
 * (BUG-20261118-36).
 */
@DisplayName("An interceptor instance for each intercepted instance")
class InterceptorInstancePerInterceptedInstanceTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Logged {
    }

    /** Answers its own number and the class of the bean it intercepts; counts the instances destroyed. */
    @Logged
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class Logger {
        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        private final int number = CREATED.incrementAndGet();
        @Inject
        @Intercepted
        Bean<?> intercepted;

        @AroundInvoke
        Object around(InvocationContext invocation) throws Exception {
            return number + ":" + intercepted.getBeanClass().getSimpleName() + ":" + invocation.proceed();
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Logged
    @Dependent
    public static class First {
        public String name() {
            return "first";
        }
    }

    @Logged
    @Dependent
    public static class Second {
        public String name() {
            return "second";
        }
    }

    /** Injects both through its constructor, and holds an Instance of the first. */
    @Dependent
    public static class Holder {
        final First first;
        final Second second;
        @Inject
        Instance<First> firsts;

        @Inject
        public Holder(First first, Second second) {
            this.first = first;
            this.second = second;
        }
    }

    @BeforeEach
    void reset() {
        Logger.CREATED.set(0);
        Logger.DESTROYED.set(0);
    }

    private static VaubanContainer boot() {
        return VaubanContainer.builder().addBeanClass(Logger.class).addBeanClass(First.class)
                .addBeanClass(Second.class).addBeanClass(Holder.class).build();
    }

    /** The interceptor instance's number in what {@code answer} says. */
    private static String interceptorOf(String answer) {
        return answer.substring(0, answer.indexOf(':'));
    }

    @Test
    @DisplayName("two intercepted instances created with one creational context have an interceptor instance each")
    void twoInstancesOfOneContextHaveAnInterceptorEach() {
        try (var container = boot()) {
            var manager = container.getBeanManager();
            var shared = new CreationalContextImpl<Object>();
            var first = (First) manager.getReference(manager.resolve(manager.getBeans(First.class)), First.class,
                    shared);
            var second = (Second) manager.getReference(manager.resolve(manager.getBeans(Second.class)), Second.class,
                    shared);

            var firstAnswer = first.name();
            var secondAnswer = second.name();

            assertEquals("First:first", firstAnswer.substring(firstAnswer.indexOf(':') + 1));
            assertEquals("Second:second", secondAnswer.substring(secondAnswer.indexOf(':') + 1));
            assertNotEquals(interceptorOf(firstAnswer), interceptorOf(secondAnswer));

            shared.release();
            assertEquals(Logger.CREATED.get(), Logger.DESTROYED.get(), "each interceptor instance destroyed once");
        }
    }

    @Test
    @DisplayName("the beans a constructor injects, and an Instance gives, have an interceptor instance each")
    void theInjectedBeansHaveAnInterceptorEach() {
        try (var container = boot()) {
            var manager = container.getBeanManager();
            var bean = manager.resolve(manager.getBeans(Holder.class));
            var context = manager.createCreationalContext(bean);
            var holder = (Holder) manager.getReference(bean, Holder.class, context);
            var fromInstance = holder.firsts.get();

            var answers = java.util.List.of(holder.first.name(), holder.second.name(), fromInstance.name());

            assertEquals(java.util.List.of("First:first", "Second:second", "First:first"),
                    answers.stream().map(answer -> answer.substring(answer.indexOf(':') + 1)).toList());
            assertEquals(3, answers.stream().map(InterceptorInstancePerInterceptedInstanceTest::interceptorOf)
                    .distinct().count(), answers::toString);

            holder.firsts.destroy(fromInstance);
            context.release();
            assertEquals(3, Logger.DESTROYED.get(), "each interceptor instance destroyed once");
        }
    }
}
