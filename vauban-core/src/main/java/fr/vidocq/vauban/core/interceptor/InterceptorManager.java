package fr.vidocq.vauban.core.interceptor;

import fr.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import fr.vidocq.vauban.indexer.model.DotName;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Manages interceptor resolution and instance lifecycle.
 */
public final class InterceptorManager {

    private final List<InterceptorDescriptor> interceptors;
    private final Map<DotName, Object> interceptorInstances = new LinkedHashMap<>();

    public InterceptorManager(List<InterceptorDescriptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
    }

    /**
     * Find interceptors that apply to a bean method based on binding annotations.
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChain(
            Set<DotName> methodBindings) {
        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();

        for (var descriptor : interceptors) {
            // An interceptor matches if all its bindings are present on the target
            if (methodBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                var instance = getOrCreateInstance(descriptor);
                var aroundInvoke = findAroundInvokeMethod(instance.getClass(), descriptor.aroundInvokeMethod());
                if (aroundInvoke != null) {
                    chain.add(new VaubanInvocationContext.InterceptorInvocation(instance, aroundInvoke));
                }
            }
        }

        return chain;
    }

    /**
     * Resolve interceptor descriptors matching the given bindings.
     */
    public List<InterceptorDescriptor> resolveInterceptors(Set<DotName> bindings) {
        var result = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (bindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                result.add(descriptor);
            }
        }
        return result;
    }

    /**
     * Find binding annotations on a bean class (annotations that are themselves @InterceptorBinding).
     */
    public Set<DotName> findBindingsOnClass(Class<?> beanClass, Predicate<DotName> isBinding) {
        var bindings = new LinkedHashSet<DotName>();
        for (var ann : beanClass.getAnnotations()) {
            var annName = DotName.of(ann.annotationType().getName());
            if (isBinding.test(annName)) {
                bindings.add(annName);
            }
        }
        return bindings;
    }

    /**
     * Set a ClassLoader to use for loading interceptor classes.
     * Required when beans are loaded by a custom ClassLoader (e.g., TCK).
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    private ClassLoader classLoader;

    private Object getOrCreateInstance(InterceptorDescriptor descriptor) {
        return interceptorInstances.computeIfAbsent(descriptor.interceptorClass(), name -> {
            try {
                var cl = classLoader != null ? classLoader
                        : Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(name.value(), true, cl)
                                       : Class.forName(name.value());
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create interceptor: " + name, e);
            }
        });
    }

    private Method findAroundInvokeMethod(Class<?> clazz, String methodName) {
        if (methodName == null) return null;
        for (var method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    public boolean hasInterceptors() {
        return !interceptors.isEmpty();
    }

    public List<InterceptorDescriptor> getInterceptors() {
        return interceptors;
    }
}
