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
package io.vidocq.vauban.core.interceptor;

import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vauban implementation of {@link InvocationContext}.
 * Chains interceptors and ultimately invokes the target method.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class VaubanInvocationContext implements InvocationContext {

    private static final String MSG_BUT_GOT = " but got ";

    private Object target;
    private final Method method;
    private Object[] parameters;
    private final List<InterceptorInvocation> chain;
    private final Map<String, Object> contextData = new HashMap<>();
    private final TargetInvoker targetInvoker;
    private Set<java.lang.annotation.Annotation> interceptorBindings;
    private Constructor<?> constructor;
    // Note: the current position in the chain is NOT an instance field.
    // It is carried by the {@code idx} parameter of {@link #proceedAt(int)} and
    // propagated to the next interceptor via {@link ChainedContext}. This avoids any
    // mutable shared state between threads - a typical @Asynchronous situation where
    // a virtual thread executes {@code ctx.proceed()} after the initial thread
    // has returned (and would have reset a shared counter to -1).

    /**
     * Functional interface for the final target invocation (to support super calls).
     */
    @FunctionalInterface
    public interface TargetInvoker {
        @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
        Object invoke(Object target, Object[] params) throws Exception;
    }

    public VaubanInvocationContext(Object target, Method method, Object[] parameters,
                                   List<InterceptorInvocation> chain) {
        this(target, method, null, parameters, chain, null);
    }

    public VaubanInvocationContext(Object target, Method method, Object[] parameters,
                                   List<InterceptorInvocation> chain, TargetInvoker targetInvoker) {
        this(target, method, null, parameters, chain, targetInvoker);
    }

    public VaubanInvocationContext(Object target, Method method, Constructor<?> constructor, Object[] parameters,
                                   List<InterceptorInvocation> chain, TargetInvoker targetInvoker) {
        this.target = target;
        this.method = method;
        this.constructor = constructor;
        this.parameters = parameters != null ? parameters.clone() : new Object[0];
        this.chain = chain;
        this.targetInvoker = targetInvoker;
    }

    @Override
    public Object getTarget() {
        return target;
    }

    @Override
    public Object getTimer() {
        return null;
    }

    @Override
    public Method getMethod() {
        // If method is a $$super$ bridge, return the original method
        if (method != null && method.getName().startsWith("$$super$")) {
            var originalName = method.getName().substring("$$super$".length());
            try {
                return method.getDeclaringClass().getSuperclass()
                        .getDeclaredMethod(originalName, method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                // fallback
            }
        }
        return method;
    }

    @Override
    public Constructor<?> getConstructor() {
        return constructor;
    }

    /**
     * Set the constructor for @AroundConstruct interception.
     */
    public void setConstructor(Constructor<?> constructor) {
        this.constructor = constructor;
    }

    /**
     * Set the interceptor bindings explicitly (for AroundConstruct/lifecycle where target is null).
     */
    public void setInterceptorBindings(Set<java.lang.annotation.Annotation> bindings) {
        this.interceptorBindings = bindings;
    }

    @Override
    public Object[] getParameters() {
        return parameters.clone();
    }

    @Override
    public void setParameters(Object[] params) {
        if (method != null) {
            // Validate parameter count
            var originalMethod = getMethod();
            if (originalMethod != null) {
                var expectedTypes = originalMethod.getParameterTypes();
                if (params.length != expectedTypes.length) {
                    throw new IllegalArgumentException(
                            "Wrong number of parameters: expected " + expectedTypes.length + MSG_BUT_GOT + params.length);
                }
                // Validate parameter types
                for (int i = 0; i < params.length; i++) {
                    if (params[i] == null) {
                        if (expectedTypes[i].isPrimitive()) {
                            throw new IllegalArgumentException("Cannot set null for primitive parameter " + i + " of type " + expectedTypes[i].getName());
                        }
                        continue;
                    }
                    if (!isAssignableTo(params[i].getClass(), expectedTypes[i])) {
                        throw new IllegalArgumentException(
                                "Parameter " + i + " type mismatch: expected " + expectedTypes[i].getName()
                                        + MSG_BUT_GOT + params[i].getClass().getName());
                    }
                }
            }
        } else if (constructor != null) {
            var expectedTypes = constructor.getParameterTypes();
            if (params.length != expectedTypes.length) {
                throw new IllegalArgumentException(
                        "Wrong number of parameters: expected " + expectedTypes.length + MSG_BUT_GOT + params.length);
            }
            // Validate parameter types
            for (int i = 0; i < params.length; i++) {
                if (params[i] == null) {
                    if (expectedTypes[i].isPrimitive()) {
                        throw new IllegalArgumentException("Cannot set null for primitive parameter " + i + " of type " + expectedTypes[i].getName());
                    }
                    continue;
                }
                if (!isAssignableTo(params[i].getClass(), expectedTypes[i])) {
                    throw new IllegalArgumentException(
                            "Parameter " + i + " type mismatch: expected " + expectedTypes[i].getName()
                                    + MSG_BUT_GOT + params[i].getClass().getName());
                }
            }
        }
        this.parameters = params.clone();
    }

    private static boolean isAssignableTo(Class<?> from, Class<?> to) {
        if (to.isAssignableFrom(from)) return true;
        // Handle primitive/wrapper conversion
        if (to.isPrimitive()) {
            String toName = to.getName();
            if (toName.equals("int")) return from == Integer.class;
            if (toName.equals("long")) return from == Long.class;
            if (toName.equals("double")) return from == Double.class;
            if (toName.equals("float")) return from == Float.class;
            if (toName.equals("boolean")) return from == Boolean.class;
            if (toName.equals("byte")) return from == Byte.class;
            if (toName.equals("char")) return from == Character.class;
            if (toName.equals("short")) return from == Short.class;
            return false;
        }
        return false;
    }

    @Override
    public Map<String, Object> getContextData() {
        return contextData;
    }

    @Override
    public Set<java.lang.annotation.Annotation> getInterceptorBindings() {
        if (interceptorBindings != null) return interceptorBindings;
        // Derive from target class annotations
        if (target != null) {
            var beanClass = target.getClass();
            if (beanClass.getName().contains("$$Intercepted") || beanClass.getName().contains("$$Proxy")) {
                beanClass = beanClass.getSuperclass();
            }
            // Class-level bindings (indexed by annotation type for override logic)
            var bindingsByType = new java.util.LinkedHashMap<Class<? extends java.lang.annotation.Annotation>,
                    java.lang.annotation.Annotation>();
            for (var ann : beanClass.getAnnotations()) {
                if (isInterceptorBinding(ann)) {
                    bindingsByType.put(ann.annotationType(), ann);
                }
            }
            // Method-level bindings override class-level of the same type
            var resolvedMethod = getMethod();
            if (resolvedMethod != null) {
                var methodBindingTypes = new java.util.HashSet<Class<? extends java.lang.annotation.Annotation>>();
                for (var ann : resolvedMethod.getAnnotations()) {
                    if (isInterceptorBinding(ann)) {
                        methodBindingTypes.add(ann.annotationType());
                        bindingsByType.put(ann.annotationType(), ann);
                    }
                }
                // Remove class-level bindings that are overridden by method-level of same type
                // (already handled by put above — method replaces class)
            }
            // Include enhanced bindings added via BCE Enhancement
            var mgr = InterceptorManager.currentInstance();
            if (mgr != null) {
                for (var ann : mgr.getEnhancedBindings(beanClass.getName())) {
                    if (isInterceptorBinding(ann)) {
                        bindingsByType.put(ann.annotationType(), ann);
                    }
                }
            }
            // Transitively resolve meta-bindings
            var result = new java.util.LinkedHashSet<java.lang.annotation.Annotation>(bindingsByType.values());
            addTransitiveBindings(result);
            interceptorBindings = result;
            return result;
        }
        return Set.of();
    }

    private static boolean isInterceptorBinding(java.lang.annotation.Annotation ann) {
        var t = ann.annotationType();
        return t.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                || io.vidocq.vauban.core.container.VaubanBeanManager.isCustomInterceptorBinding(t);
    }

    private static void addTransitiveBindings(Set<java.lang.annotation.Annotation> bindings) {
        var toAdd = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        for (var ann : bindings) {
            collectTransitive(ann.annotationType(), toAdd, bindings);
        }
        bindings.addAll(toAdd);
    }

    private static void collectTransitive(Class<? extends java.lang.annotation.Annotation> annType,
            Set<java.lang.annotation.Annotation> toAdd, Set<java.lang.annotation.Annotation> existing) {
        for (var meta : annType.getAnnotations()) {
            if (isInterceptorBinding(meta) && !existing.contains(meta) && !toAdd.contains(meta)) {
                toAdd.add(meta);
                collectTransitive(meta.annotationType(), toAdd, existing);
            }
        }
    }

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    @Override
    public Object proceed() throws Exception {
        return proceedAt(0);
    }

    /**
     * Advances through the interceptor chain starting at index {@code idx}. This
     * method does not mutate any instance state: {@code idx} is carried exclusively by
     * the call stack and propagated to the next interceptor via {@link ChainedContext}.
     *
     * <p>Consequence: the instance is safe with respect to cross-thread calls (vthreads
     * for {@code @Asynchronous}). Multiple successive calls to {@code proceed()} from
     * the same interceptor ({@code @Retry} semantics) are still supported because each
     * call re-enters at current index + 1, determined by the context passed to that
     * interceptor, and not by a shared field.</p>
     */
    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    Object proceedAt(int idx) throws Exception {
        try {
            if (idx < chain.size()) {
                var invocation = chain.get(idx);
                var nextCtx = new ChainedContext(this, idx + 1);
                if (constructor != null && invocation.target() == null) {
                    if (target == null) {
                        return invocation.method().invoke(null, nextCtx);
                    } else {
                        makeAccessibleSafe(invocation.method());
                        invocation.method().invoke(target, nextCtx);
                        return null;
                    }
                }
                return invocation.invoke(nextCtx);
            } else {
                if (targetInvoker != null) {
                    var result = targetInvoker.invoke(target, parameters);
                    if (constructor != null && result != null) {
                        target = result;
                    }
                    return constructor != null ? null : result;
                }
                if (method != null) {
                    makeAccessibleSafe(method);
                    return method.invoke(target, parameters);
                }
                return null;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    /**
     * Decorator view of the parent context that captures the chain position to visit on
     * the next {@code proceed()}. All other methods delegate to the parent
     * so that all interceptors share parameters / contextData /
     * interceptor bindings.
     */
    private record ChainedContext(VaubanInvocationContext parent, int nextIdx) implements InvocationContext {
        @Override public Object getTarget() { return parent.getTarget(); }
        @Override public Object getTimer() { return parent.getTimer(); }
        @Override public Method getMethod() { return parent.getMethod(); }
        @Override public Constructor<?> getConstructor() { return parent.getConstructor(); }
        @Override public Object[] getParameters() { return parent.getParameters(); }
        @Override public void setParameters(Object[] params) { parent.setParameters(params); }
        @Override public Map<String, Object> getContextData() { return parent.getContextData(); }
        @Override public Set<java.lang.annotation.Annotation> getInterceptorBindings() {
            return parent.getInterceptorBindings();
        }
        @Override public Object proceed() throws Exception { return parent.proceedAt(nextIdx); }
    }

    /**
     * Represents one interceptor in the chain.
     */
    public record InterceptorInvocation(Object target, Method method) {
        @SuppressWarnings({"java:S3011", "java:S112"}) // CDI spec requires reflective access; container exceptions propagate as RuntimeException
        public Object invoke(InvocationContext ctx) throws Exception {
            try {
                makeAccessibleSafe(method);
                return method.invoke(target, ctx);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause instanceof Error err) throw err;
                throw e;
            }
        }
    }
    private static void makeAccessibleSafe(java.lang.reflect.AccessibleObject member) {
        var mgr = InterceptorManager.currentInstance();
        if (mgr != null && mgr.getVaubanLookup() != null) {
            mgr.getVaubanLookup().makeAccessible(member);
        } else {
            member.trySetAccessible();
        }
    }

    public static VaubanInvocationContext dummy() {
        return dummy(new Object[0]);
    }

    public static VaubanInvocationContext dummy(Object[] params) {
        return new VaubanInvocationContext(null, null, null, params, java.util.Collections.emptyList(), null);
    }
}
