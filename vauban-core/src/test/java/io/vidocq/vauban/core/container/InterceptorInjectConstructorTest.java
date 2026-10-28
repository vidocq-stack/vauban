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
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Intercepted;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interceptor is a managed bean (CDI 4.1 "Declaring a bean constructor"): it may have an {@code @Inject}
 * constructor instead of a no-arg one, whose parameters are ordinary injection points (VAU-INT-009). Two
 * {@code @Inject} constructors, or neither a no-arg nor an {@code @Inject} constructor, are definition errors.
 */
@DisplayName("Interceptors with an @Inject bean constructor")
class InterceptorInjectConstructorTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    private static final String FORMAL = "good day";

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Greeted {
    }

    /** The literal {@link jakarta.enterprise.inject.spi.BeanManager#resolveInterceptors} is asked with. */
    static final class GreetedLiteral extends AnnotationLiteral<Greeted> implements Greeted {
        private static final long serialVersionUID = 1L;
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    public @interface Formal {
    }

    public interface Salutation {
        String text();
    }

    @Formal
    @Dependent
    public static class FormalSalutation implements Salutation {
        @Override
        public String text() {
            return FORMAL;
        }
    }

    @Dependent
    public static class CasualSalutation implements Salutation {
        @Override
        public String text() {
            return "hi";
        }
    }

    /** No no-arg constructor: the container must build it through its {@code @Inject} one. */
    @Greeted
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class GreetingInterceptor {
        private final Salutation salutation;

        @Inject
        public GreetingInterceptor(@Formal Salutation salutation) {
            this.salutation = salutation;
        }

        String salutation() {
            return salutation.text();
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add(salutation.text() + ":" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @Dependent
    public static class GreetedService {
        @Greeted
        public String work() {
            return "worked";
        }
    }

    @Greeted
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class TwoInjectConstructorsInterceptor {
        @Inject
        public TwoInjectConstructorsInterceptor(@Formal Salutation salutation) {
            salutation.text();
        }

        @Inject
        public TwoInjectConstructorsInterceptor(CasualSalutation salutation) {
            salutation.text();
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Greeted
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class NoBeanConstructorInterceptor {
        public NoBeanConstructorInterceptor(CasualSalutation salutation) {
            salutation.text();
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    /** Its own metadata with a type parameter other than the interceptor class: still a definition error. */
    @Greeted
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class WrongMetadataInterceptor {
        @Inject
        public WrongMetadataInterceptor(Interceptor<CasualSalutation> self) {
            self.getBeanClass();
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    /** {@code @Intercepted Bean<T>} with a type parameter other than a wildcard: still a definition error. */
    @Greeted
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class WrongInterceptedInterceptor {
        @Inject
        public WrongInterceptedInterceptor(@Intercepted Bean<GreetedService> intercepted) {
            intercepted.getBeanClass();
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    private static VaubanContainer boot(Class<?> interceptor) {
        return VaubanContainer.builder()
                .addBeanClass(FormalSalutation.class)
                .addBeanClass(CasualSalutation.class)
                .addBeanClass(interceptor)
                .addBeanClass(GreetedService.class)
                .build();
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    @Test
    @DisplayName("an interceptor with only an @Inject constructor is built through it, a qualified parameter resolved")
    void injectConstructorOnly() {
        try (var container = boot(GreetingInterceptor.class)) {

            assertEquals("worked", container.select(GreetedService.class).work());
            assertEquals(List.of(FORMAL + ":work"), List.copyOf(CALLS));
        }
    }

    @Test
    @DisplayName("the Interceptor bean BeanManager.resolveInterceptors returns creates it through its @Inject constructor")
    void spiInterceptorCreate() {
        try (var container = boot(GreetingInterceptor.class)) {
            var interceptors = container.getBeanManager()
                    .resolveInterceptors(InterceptionType.AROUND_INVOKE, new GreetedLiteral());

            assertEquals(1, interceptors.size(), interceptors::toString);
            @SuppressWarnings("unchecked")
            var interceptor = (Interceptor<Object>) interceptors.getFirst();
            var instance = interceptor.create(new CreationalContextImpl<>());

            var greeting = assertInstanceOf(GreetingInterceptor.class, instance);
            assertEquals(FORMAL, greeting.salutation());
            var withoutContext = assertInstanceOf(GreetingInterceptor.class, interceptor.create(null));
            assertEquals(FORMAL, withoutContext.salutation());
        }
    }

    @Test
    @DisplayName("an interceptor with two @Inject constructors is a definition error")
    void twoInjectConstructors() {
        var thrown = assertThrows(DefinitionException.class, () -> boot(TwoInjectConstructorsInterceptor.class));

        assertTrue(thrown.getMessage().contains(TwoInjectConstructorsInterceptor.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("an interceptor with neither a no-arg nor an @Inject constructor is a definition error")
    void noBeanConstructor() {
        var thrown = assertThrows(DefinitionException.class, () -> boot(NoBeanConstructorInterceptor.class));

        assertTrue(thrown.getMessage().contains(NoBeanConstructorInterceptor.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("bean metadata of the wrong type parameter in an @Inject constructor fails for that, not the constructor")
    void metadataRulesApplyToConstructorParameters() {
        for (var interceptor : List.of(WrongMetadataInterceptor.class, WrongInterceptedInterceptor.class)) {
            var thrown = assertThrows(DefinitionException.class, () -> boot(interceptor));

            assertTrue(thrown.getMessage().contains("Illegal injection of built-in metadata type"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains("no-arg constructor"), thrown.getMessage());
        }
    }
}
