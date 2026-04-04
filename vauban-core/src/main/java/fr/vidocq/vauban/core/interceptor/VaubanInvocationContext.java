package fr.vidocq.vauban.core.interceptor;

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
public final class VaubanInvocationContext implements InvocationContext {

    private Object target;
    private final Method method;
    private Object[] parameters;
    private final List<InterceptorInvocation> chain;
    private int currentIndex = -1;
    private final Map<String, Object> contextData = new HashMap<>();
    private final TargetInvoker targetInvoker;
    private Set<java.lang.annotation.Annotation> interceptorBindings;
    private Constructor<?> constructor;

    /**
     * Functional interface for the final target invocation (to support super calls).
     */
    @FunctionalInterface
    public interface TargetInvoker {
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
                            "Wrong number of parameters: expected " + expectedTypes.length + " but got " + params.length);
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
                                        + " but got " + params[i].getClass().getName());
                    }
                }
            }
        } else if (constructor != null) {
            var expectedTypes = constructor.getParameterTypes();
            if (params.length != expectedTypes.length) {
                throw new IllegalArgumentException(
                        "Wrong number of parameters: expected " + expectedTypes.length + " but got " + params.length);
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
                                    + " but got " + params[i].getClass().getName());
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
        return ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class);
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

    @Override
    public Object proceed() throws Exception {
        try {
            int savedIndex = currentIndex;
            currentIndex++;
            try {
                if (currentIndex < chain.size()) {
                    var invocation = chain.get(currentIndex);
                    if (constructor != null && invocation.target() == null) {
                        if (target == null) {
                            return invocation.method().invoke(null, this);
                        } else {
                            invocation.method().setAccessible(true);
                            invocation.method().invoke(target, this);
                            return null;
                        }
                    }
                    return invocation.invoke(this);
                } else {
                    if (targetInvoker != null) {
                        var result = targetInvoker.invoke(target, parameters);
                        if (constructor != null && result != null) {
                            target = result;
                        }
                        return constructor != null ? null : result;
                    }
                    if (method != null) {
                        method.setAccessible(true);
                        return method.invoke(target, parameters);
                    }
                    return null;
                }
            } finally {
                currentIndex = savedIndex;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    /**
     * Represents one interceptor in the chain.
     */
    public record InterceptorInvocation(Object target, Method method) {
        public Object invoke(InvocationContext ctx) throws Exception {
            try {
                method.setAccessible(true);
                return method.invoke(target, ctx);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause instanceof Error err) throw err;
                throw e;
            }
        }
    }
    public static VaubanInvocationContext dummy() {
        return dummy(new Object[0]);
    }

    public static VaubanInvocationContext dummy(Object[] params) {
        return new VaubanInvocationContext(null, null, null, params, java.util.Collections.emptyList(), null);
    }
}
