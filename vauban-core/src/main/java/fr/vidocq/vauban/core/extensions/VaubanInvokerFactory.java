package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.invoke.InvokerBuilder;
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Creates {@link InvokerBuilder}s that produce reflection-based Invokers.
 */
public final class VaubanInvokerFactory implements InvokerFactory {

    private final ClassLoader classLoader;

    public VaubanInvokerFactory(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public InvokerBuilder<InvokerInfo> createInvoker(BeanInfo bean, MethodInfo method) {
        // Validate: not a producer bean
        if (bean.isProducerMethod() || bean.isProducerField()) {
            throw new IllegalStateException(
                    "Cannot create invoker for producer bean: " + bean.declaringClass().name());
        }

        // Validate: not an interceptor
        if (bean.isInterceptor()) {
            throw new IllegalStateException(
                    "Cannot create invoker for interceptor bean: " + bean.declaringClass().name());
        }

        // Validate: not a constructor
        if ("<init>".equals(method.name())) {
            throw new IllegalStateException(
                    "Cannot create invoker for constructor of: " + bean.declaringClass().name());
        }

        // Resolve the actual java.lang.reflect.Method
        var beanClassName = bean.declaringClass().name();
        try {
            var beanClass = classLoader.loadClass(beanClassName);
            var reflectMethod = findMethod(beanClass, method);

            // Validate: not private
            if (Modifier.isPrivate(reflectMethod.getModifiers())) {
                throw new IllegalStateException(
                        "Cannot create invoker for private method: " + beanClassName + "." + method.name());
            }

            // Validate: method belongs to the bean class (or its supertypes)
            if (!reflectMethod.getDeclaringClass().isAssignableFrom(beanClass)) {
                throw new IllegalStateException(
                        "Method " + method.name() + " does not belong to bean class " + beanClassName);
            }

            // Validate: not an Object method other than toString
            if (reflectMethod.getDeclaringClass() == Object.class && !"toString".equals(method.name())) {
                throw new IllegalStateException(
                        "Cannot create invoker for Object method: " + method.name());
            }

            return new VaubanInvokerBuilder(reflectMethod, beanClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load bean class: " + beanClassName, e);
        }
    }

    private Method findMethod(Class<?> beanClass, MethodInfo methodInfo) {
        var methodName = methodInfo.name();
        var paramCount = methodInfo.parameters().size();

        // Search through class hierarchy
        for (var cls = beanClass; cls != null; cls = cls.getSuperclass()) {
            for (var m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
        }

        // Also search interfaces
        for (var iface : getAllInterfaces(beanClass)) {
            for (var m : iface.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == paramCount) {
                    // Find the actual implementation on the bean class
                    try {
                        return beanClass.getMethod(methodName, m.getParameterTypes());
                    } catch (NoSuchMethodException e) {
                        return m;
                    }
                }
            }
        }

        throw new IllegalStateException(
                "Method " + methodName + " not found on " + beanClass.getName());
    }

    private static java.util.Set<Class<?>> getAllInterfaces(Class<?> cls) {
        var interfaces = new java.util.LinkedHashSet<Class<?>>();
        for (var c = cls; c != null; c = c.getSuperclass()) {
            for (var iface : c.getInterfaces()) {
                interfaces.add(iface);
                interfaces.addAll(getAllInterfaces(iface));
            }
        }
        return interfaces;
    }
}
