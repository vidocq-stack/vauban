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

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A priority that is declared is the priority: 0 on an observer (BUG-20261113-11), and the one an {@code @Enhancement}
 * puts on an interceptor in place of the declared one, whichever {@code addAnnotation} overload it uses
 * (BUG-20261113-12).
 */
@DisplayName("A declared priority is the priority")
class DeclaredPriorityTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    // ---- an observer at priority 0 ----

    public record Ping() {
    }

    /** {@code Interceptor.Priority.PLATFORM_BEFORE} is 0: the earliest an observer of a library can ask for. */
    @ApplicationScoped
    public static class AtZero {
        void on(@Observes @Priority(0) Ping ping) {
            CALLS.add("zero");
        }
    }

    @ApplicationScoped
    public static class AtOneHundred {
        void on(@Observes @Priority(100) Ping ping) {
            CALLS.add("hundred");
        }
    }

    /** No priority declared: the default, {@code ObserverMethod.DEFAULT_PRIORITY}, 2500. */
    @ApplicationScoped
    public static class Undeclared {
        void on(@Observes Ping ping) {
            CALLS.add("undeclared");
        }
    }

    @Test
    @DisplayName("an observer declared at priority 0 is notified first, not as if it declared none")
    void anObserverAtPriorityZeroIsNotifiedFirst() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(AtOneHundred.class).addBeanClass(Undeclared.class).addBeanClass(AtZero.class).build()) {
            container.getBeanManager().getEvent().select(Ping.class).fire(new Ping());
        }

        assertEquals(List.of("zero", "hundred", "undeclared"), List.copyOf(CALLS));
    }

    // ---- an interceptor whose @Priority an extension replaces ----

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Traced {
    }

    @Interceptor
    @Priority(1000)
    @Traced
    public static class Moved {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("moved");
            return context.proceed();
        }
    }

    @Interceptor
    @Priority(2000)
    @Traced
    public static class Fixed {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("fixed");
            return context.proceed();
        }
    }

    @Dependent
    @Traced
    public static class Service {
        public String call() {
            return "ok";
        }
    }

    static final class PriorityLiteral extends AnnotationLiteral<Priority> implements Priority {
        private static final long serialVersionUID = 1L;
        private final int value;

        PriorityLiteral(int value) {
            this.value = value;
        }

        @Override
        public int value() {
            return value;
        }
    }

    /** Replaces the priority with an annotation instance, as a portable extension would. */
    public static class MoveWithAnAnnotation implements BuildCompatibleExtension {
        @Enhancement(types = Moved.class)
        public void move(ClassConfig moved) {
            moved.removeAnnotation(annotation -> Priority.class.getName().equals(annotation.name()));
            moved.addAnnotation(new PriorityLiteral(3000));
        }
    }

    /** The same replacement through the language model. */
    public static class MoveWithAnAnnotationInfo implements BuildCompatibleExtension {
        @Enhancement(types = Moved.class)
        public void move(ClassConfig moved) {
            moved.removeAnnotation(annotation -> Priority.class.getName().equals(annotation.name()));
            moved.addAnnotation(AnnotationBuilder.of(Priority.class).value(3000).build());
        }
    }

    private static List<String> orderWith(Class<? extends BuildCompatibleExtension> extension) {
        try (VaubanContainer container = VaubanContainer.builder().addBeanClass(extension)
                .addBeanClass(Moved.class).addBeanClass(Fixed.class).addBeanClass(Service.class).build()) {
            container.select(Service.class).call();
        }
        return List.copyOf(CALLS);
    }

    @Test
    @DisplayName("without an extension the interceptors run in the order they declare")
    void theDeclaredOrder() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Moved.class).addBeanClass(Fixed.class).addBeanClass(Service.class).build()) {
            container.select(Service.class).call();
        }

        assertEquals(List.of("moved", "fixed"), List.copyOf(CALLS));
    }

    @Test
    @DisplayName("a @Priority an extension adds as an annotation moves the interceptor")
    void aPriorityAddedAsAnAnnotationMovesTheInterceptor() {
        assertEquals(List.of("fixed", "moved"), orderWith(MoveWithAnAnnotation.class));
    }

    @Test
    @DisplayName("a @Priority an extension adds through the language model moves the interceptor")
    void aPriorityAddedAsAnAnnotationInfoMovesTheInterceptor() {
        assertEquals(List.of("fixed", "moved"), orderWith(MoveWithAnAnnotationInfo.class));
    }
}
