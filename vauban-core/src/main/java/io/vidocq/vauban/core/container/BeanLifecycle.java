package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.interceptor.InterceptorManager;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BeanLifecycle {

    private final InterceptorManager interceptorManager;
    private final VaubanLookup vaubanLookup;

    BeanLifecycle(InterceptorManager interceptorManager, VaubanLookup vaubanLookup) {
        this.interceptorManager = interceptorManager;
        this.vaubanLookup = vaubanLookup;
    }

    void callPostConstruct(Object instance, BeanDescriptor descriptor, CreationalContext<?> ctx) {
        if (instance.getClass().isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
            Method pc = null;
            for (var m : instance.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(jakarta.annotation.PostConstruct.class) && m.getParameterCount() == 0) {
                    pc = m;
                    break;
                }
            }
            if (pc != null) {
                try {
                    vaubanLookup.invokeMethod(instance, pc);
                } catch (Exception e) {
                    throw new RuntimeException("@PostConstruct on interceptor instance failed: " + pc, e);
                }
            }
            return;
        }

        var postConstructMethods = collectLifecycleMethodsInHierarchy(
                instance.getClass(), jakarta.annotation.PostConstruct.class);

        Set<DotName> beanBindings = (descriptor != null)
                ? descriptor.interceptorBindings() : findInterceptorBindings(instance);
        if ((beanBindings == null || beanBindings.isEmpty()) && interceptorManager.hasInterceptors()) {
            beanBindings = findInterceptorBindings(instance);
        }
        if (beanBindings != null && !beanBindings.isEmpty() && interceptorManager.hasInterceptors()) {
            interceptorManager.setClassLoader(instance.getClass().getClassLoader());
            var bindingAnns = (descriptor != null && !descriptor.interceptorBindingAnnotations().isEmpty())
                    ? new ArrayList<Annotation>(descriptor.interceptorBindingAnnotations())
                    : new ArrayList<Annotation>(collectBindingAnnotations(instance));
            var lifecycleChain = interceptorManager.resolveLifecycleChain(
                    beanBindings, jakarta.annotation.PostConstruct.class, bindingAnns, ctx);
            if (!lifecycleChain.isEmpty()) {
                var bindingAnnotations = new LinkedHashSet<Annotation>(bindingAnns);
                final var pcMethods = postConstructMethods;
                var invocationCtx = new io.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                        instance, null, new Object[0], lifecycleChain,
                        (target, params) -> {
                            for (var m : pcMethods) {
                                vaubanLookup.invokeMethod(target, m);
                            }
                            return null;
                        });
                invocationCtx.setInterceptorBindings(bindingAnnotations);
                try {
                    invocationCtx.proceed();
                } catch (Throwable e) {
                    if (e instanceof RuntimeException re) throw re;
                    if (e instanceof Error err) throw err;
                    throw new RuntimeException("Lifecycle interceptor failed", e);
                }
                return;
            }
        }

        for (var pcMethod : postConstructMethods) {
            try {
                vaubanLookup.invokeMethod(instance, pcMethod);
            } catch (Exception e) {
                throw new RuntimeException("@PostConstruct failed: " + pcMethod, e);
            }
        }
    }

    static List<Method> collectLifecycleMethodsInHierarchy(
            Class<?> clazz, Class<? extends Annotation> annotation) {
        if (clazz.getName().contains("$$Intercepted") || clazz.getName().contains("$$Proxy")) {
            clazz = clazz.getSuperclass();
        }
        var result = new ArrayList<Method>();
        collectLifecycleMethodsRecursive(clazz, annotation, result);
        return result;
    }

    private static void collectLifecycleMethodsRecursive(Class<?> clazz,
            Class<? extends Annotation> annotation,
            List<Method> result) {
        if (clazz == null || clazz == Object.class) return;
        collectLifecycleMethodsRecursive(clazz.getSuperclass(), annotation, result);
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                result.removeIf(m -> m.getName().equals(method.getName())
                        && Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
                result.add(method);
            } else {
                result.removeIf(m -> m.getName().equals(method.getName())
                        && Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
            }
        }
    }

    static void collectTransitiveBindings(Set<Annotation> annotations, Set<DotName> dotNames) {
        var toAdd = new LinkedHashSet<Annotation>();
        for (var ann : annotations) {
            collectTransitiveMeta(ann.annotationType(), toAdd, annotations, dotNames);
        }
        annotations.addAll(toAdd);
    }

    private static void collectTransitiveMeta(Class<? extends Annotation> annType,
            Set<Annotation> toAdd,
            Set<Annotation> existing, Set<DotName> dotNames) {
        for (var meta : annType.getAnnotations()) {
            if (meta.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    && !existing.contains(meta) && !toAdd.contains(meta)) {
                toAdd.add(meta);
                dotNames.add(DotName.of(meta.annotationType().getName()));
                collectTransitiveMeta(meta.annotationType(), toAdd, existing, dotNames);
            }
        }
    }

    Set<DotName> findInterceptorBindings(Object instance) {
        var bindings = new LinkedHashSet<DotName>();
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted")) {
            clazz = clazz.getSuperclass();
        }
        var annotations = new LinkedHashSet<Annotation>();
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                bindings.add(DotName.of(ann.annotationType().getName()));
                annotations.add(ann);
            }
        }
        collectTransitiveBindings(annotations, bindings);
        return bindings;
    }

    Set<Annotation> collectBindingAnnotations(Object instance) {
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted") || clazz.getName().contains("$$Proxy")) {
            clazz = clazz.getSuperclass();
        }
        var annotations = new LinkedHashSet<Annotation>();
        var dotNames = new LinkedHashSet<DotName>();
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                annotations.add(ann);
                dotNames.add(DotName.of(ann.annotationType().getName()));
            }
        }
        collectTransitiveBindings(annotations, dotNames);
        return annotations;
    }
}
