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
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VaubanInterceptor#intercept}, the SPI entry point a caller of
 * {@link jakarta.enterprise.inject.spi.BeanManager#resolveInterceptors} uses, lets the exception of the interceptor
 * method, or of the chain it proceeds to, reach the caller unchanged: never wrapped in the
 * {@link java.lang.reflect.InvocationTargetException} of the reflective call (Jakarta Interceptors 2.2 "Exceptions",
 * VAU-INT-010).
 */
@DisplayName("VaubanInterceptor.intercept propagates exceptions unchanged")
class VaubanInterceptorExceptionTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Guarded {
    }

    static final class GuardedLiteral extends AnnotationLiteral<Guarded> implements Guarded {
        private static final long serialVersionUID = 1L;
    }

    /** A checked exception of the intercepted method, as its throws clause would declare it. */
    static final class BusinessException extends Exception {
        private static final long serialVersionUID = 1L;

        BusinessException(String message) {
            super(message);
        }
    }

    /** Neither an {@link Exception} nor an {@link Error}: Java code can only throw it unchecked, by erasure. */
    static final class BareThrowable extends Throwable {
        private static final long serialVersionUID = 1L;

        BareThrowable(String message) {
            super(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> Object sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Proceeds: whatever the chain throws or returns comes back through it. */
    @Guarded
    @jakarta.interceptor.Interceptor
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
    public static class GuardInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    /** The smallest context the SPI caller hands over: {@link #proceed} runs the given step. */
    private record StepContext(Callable<Object> step) implements InvocationContext {
        @Override
        public Object getTarget() {
            return null;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Method getMethod() {
            return null;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return new Object[0];
        }

        @Override
        public void setParameters(Object[] params) {
            // no parameters to replace: the step takes none
        }

        @Override
        public Map<String, Object> getContextData() {
            return new HashMap<>();
        }

        @Override
        public Object proceed() throws Exception {
            return step.call();
        }
    }

    private static Object intercept(Callable<Object> step) throws Exception {
        try (var container = VaubanContainer.builder().addBeanClass(GuardInterceptor.class).build()) {
            var interceptors = container.getBeanManager()
                    .resolveInterceptors(InterceptionType.AROUND_INVOKE, new GuardedLiteral());
            assertEquals(1, interceptors.size(), interceptors::toString);
            @SuppressWarnings("unchecked")
            var interceptor = (Interceptor<Object>) interceptors.getFirst();
            var instance = interceptor.create(new CreationalContextImpl<>());
            return interceptor.intercept(InterceptionType.AROUND_INVOKE, instance, new StepContext(step));
        }
    }

    @Test
    @DisplayName("the value the chain returns comes back")
    void returnsTheChainValue() throws Exception {
        assertEquals("done", intercept(() -> "done"));
    }

    @Test
    @DisplayName("a checked exception of the chain reaches the caller as itself")
    void checkedExceptionUnchanged() {
        var failure = new BusinessException("refused");

        var thrown = assertThrows(BusinessException.class, () -> intercept(() -> {
            throw failure;
        }));

        assertSame(failure, thrown);
    }

    @Test
    @DisplayName("a runtime exception of the chain reaches the caller as itself")
    void runtimeExceptionUnchanged() {
        var failure = new IllegalStateException("broken");

        var thrown = assertThrows(IllegalStateException.class, () -> intercept(() -> {
            throw failure;
        }));

        assertSame(failure, thrown);
    }

    @Test
    @DisplayName("an error of the chain reaches the caller as itself")
    void errorUnchanged() {
        var failure = new AssertionError("fatal");

        var thrown = assertThrows(AssertionError.class, () -> intercept(() -> {
            throw failure;
        }));

        assertSame(failure, thrown);
    }

    @Test
    @DisplayName("a bare Throwable, neither an Exception nor an Error, is the one thing wrapped, as itself the cause")
    void bareThrowableWrapped() {
        var failure = new BareThrowable("odd");

        var thrown = assertThrows(UndeclaredThrowableException.class,
                () -> intercept(() -> VaubanInterceptorExceptionTest.<RuntimeException>sneakyThrow(failure)));

        assertSame(failure, thrown.getCause());
    }
}
