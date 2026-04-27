package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Vauban implementation of {@link Interceptor} backed by an {@link InterceptorDescriptor}.
 */
public final class VaubanInterceptor<T> implements Interceptor<T> {

    private final InterceptorDescriptor descriptor;
    private final Class<T> interceptorClass;

    @SuppressWarnings("unchecked")
    public VaubanInterceptor(InterceptorDescriptor descriptor) {
        this.descriptor = descriptor;
        try {
            this.interceptorClass = (Class<T>) Class.forName(descriptor.interceptorClass().value());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Interceptor class not found: " + descriptor.interceptorClass(), e);
        }
    }

    @Override
    public Set<Annotation> getInterceptorBindings() {
        var result = new LinkedHashSet<Annotation>();
        for (var binding : descriptor.bindings()) {
            try {
                @SuppressWarnings("unchecked")
                var annClass = (Class<? extends Annotation>) Class.forName(binding.value());
                // Get the annotation from the interceptor class itself
                var ann = interceptorClass.getAnnotation(annClass);
                if (ann != null) {
                    result.add(ann);
                }
            } catch (ClassNotFoundException e) {
                // skip
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
        return aroundInvoke.invoke(instance, ctx);
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
    @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
    public T create(CreationalContext<T> creationalContext) {
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
