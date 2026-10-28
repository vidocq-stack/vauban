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

import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.loading.DeploymentClasses;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Vauban implementation of {@link Interceptor} backed by an {@link InterceptorDescriptor}.
 */
public final class VaubanInterceptor<T> implements Interceptor<T> {

    private final InterceptorDescriptor descriptor;
    private final Class<T> interceptorClass;

    /**
     * How the container creates an instance of this interceptor, through its bean constructor, {@code @Inject} one
     * included (VAU-INT-009); {@code null} outside a container, where only a no-arg constructor can be called.
     */
    private final BiFunction<InterceptorDescriptor, CreationalContext<?>, Object> instanceFactory;

    public VaubanInterceptor(InterceptorDescriptor descriptor) {
        this(descriptor, null, null);
    }

    /**
     * @param instanceFactory the container's own interceptor creation, the one interception uses, so that
     *                        {@link #create} builds the instance the same way: constructor injection, field
     *                        injection, {@code @PostConstruct}
     * @param classLoader     the loader of the container's bean classes; {@code null} outside a container, where
     *                        the interceptor class loads the way discovery loads the deployment's classes
     */
    @SuppressWarnings("unchecked")
    public VaubanInterceptor(InterceptorDescriptor descriptor,
                             BiFunction<InterceptorDescriptor, CreationalContext<?>, Object> instanceFactory,
                             ClassLoader classLoader) {
        this.descriptor = descriptor;
        this.instanceFactory = instanceFactory;
        try {
            this.interceptorClass = (Class<T>) DeploymentClasses.load(descriptor.interceptorClass().value(), classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Interceptor class not found: " + descriptor.interceptorClass(), e);
        }
    }

    /**
     * The annotations of the interceptor class whose type is one of its bindings, in the descriptor's order. Read off
     * the class itself: a binding type the interceptor does not carry, or whose class cannot be loaded, is not one.
     */
    @Override
    public Set<Annotation> getInterceptorBindings() {
        var carried = new HashMap<String, Annotation>();
        for (var ann : interceptorClass.getAnnotations()) {
            carried.put(ann.annotationType().getName(), ann);
        }
        var result = new LinkedHashSet<Annotation>();
        for (var binding : descriptor.bindings()) {
            var ann = carried.get(binding.value());
            if (ann != null) {
                result.add(ann);
            }
        }
        return result;
    }

    @Override
    public boolean intercepts(InterceptionType type) {
        return switch (type) {
            case AROUND_INVOKE -> hasMethodWithAnnotation(jakarta.interceptor.AroundInvoke.class);
            case AROUND_CONSTRUCT -> hasMethodWithAnnotation(jakarta.interceptor.AroundConstruct.class);
            case POST_CONSTRUCT -> hasMethodWithAnnotation(jakarta.annotation.PostConstruct.class);
            case PRE_DESTROY -> hasMethodWithAnnotation(jakarta.annotation.PreDestroy.class);
            default -> false;
        };
    }

    private boolean hasMethodWithAnnotation(Class<? extends java.lang.annotation.Annotation> annotation) {
        Class<?> current = interceptorClass;
        while (current != null && current != Object.class) {
            for (var m : current.getDeclaredMethods()) {
                if (m.isAnnotationPresent(annotation)) return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    @Override
    public Object intercept(InterceptionType type, T instance, InvocationContext ctx) throws Exception {
        if (type != InterceptionType.AROUND_INVOKE || descriptor.aroundInvokeMethod() == null) {
            throw new UnsupportedOperationException("Interception type not supported: " + type);
        }
        Method aroundInvoke = findAroundInvokeMethod();
        if (aroundInvoke == null) {
            throw new IllegalStateException("AroundInvoke method not found: " + descriptor.aroundInvokeMethod());
        }
        makeAccessibleSafe(aroundInvoke);
        try {
            return aroundInvoke.invoke(instance, ctx);
        } catch (InvocationTargetException e) {
            // The caller sees what the interceptor method or its chain threw, never the reflective wrapper
            // (Jakarta Interceptors 2.2 "Exceptions", VAU-INT-010).
            throw unwrapped(e.getCause());
        }
    }

    /**
     * {@code cause} as the exception to rethrow: an {@link Error} is thrown here, an {@link Exception} returned as is,
     * and a bare {@link Throwable} (neither) returned wrapped, the only case left to wrap.
     */
    private static Exception unwrapped(Throwable cause) {
        return switch (cause) {
            case Error error -> throw error;
            case Exception exception -> exception;
            default -> new java.lang.reflect.UndeclaredThrowableException(cause);
        };
    }

    @Override
    public Class<?> getBeanClass() {
        return interceptorClass;
    }

    @Override
    public Set<Type> getTypes() {
        return Set.of(interceptorClass, Object.class);
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return Set.of();
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return Dependent.class;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public Set<Class<? extends Annotation>> getStereotypes() {
        return Set.of();
    }

    @Override
    public boolean isAlternative() {
        return false;
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return Set.of();
    }

    @Override
    @SuppressWarnings({"unchecked", "java:S112"}) // CDI spec: container exceptions propagate as RuntimeException
    public T create(CreationalContext<T> creationalContext) {
        if (instanceFactory != null) {
            // A context of its own when the caller passes none: the instance is created, not looked up in one.
            CreationalContext<?> ctx = creationalContext != null
                    ? creationalContext : new io.vidocq.vauban.core.context.CreationalContextImpl<>();
            return (T) instanceFactory.apply(descriptor, ctx);
        }
        try {
            return interceptorClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create interceptor: " + interceptorClass.getName(), e);
        }
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        if (creationalContext != null) {
            creationalContext.release();
        }
    }

    public InterceptorDescriptor descriptor() {
        return descriptor;
    }

    private static void makeAccessibleSafe(java.lang.reflect.AccessibleObject member) {
        var mgr = io.vidocq.vauban.core.interceptor.InterceptorManager.currentInstance();
        if (mgr != null && mgr.getVaubanLookup() != null) {
            mgr.getVaubanLookup().makeAccessible(member);
        } else {
            member.trySetAccessible();
        }
    }

    private Method findAroundInvokeMethod() {
        for (var method : interceptorClass.getDeclaredMethods()) {
            if (method.getName().equals(descriptor.aroundInvokeMethod())) {
                return method;
            }
        }
        return null;
    }
}
