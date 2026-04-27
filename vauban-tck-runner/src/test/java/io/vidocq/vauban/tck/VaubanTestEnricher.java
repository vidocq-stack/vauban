package io.vidocq.vauban.tck;

import io.vidocq.vauban.core.container.InstanceImpl;
import io.vidocq.vauban.core.event.EventImpl;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;

/**
 * Arquillian TestEnricher that injects CDI objects into test instances.
 * Handles @Inject for BeanManager, Instance, Event, Provider, and bean types.
 * Supports qualifier annotations on injection points.
 */
public class VaubanTestEnricher implements TestEnricher {

    @Override
    public void enrich(Object testCase) {
        var container = ContainerHolder.get();
        if (container == null) return;

        Class<?> clazz = testCase.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)) {
                    try {
                        field.setAccessible(true);
                        var value = resolveField(field, container);
                        if (value != null) {
                            field.set(testCase, value);
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Failed to enrich test field: " + field.getName(), e);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private Object resolveField(Field field, io.vidocq.vauban.core.container.VaubanContainer container) {
        var type = field.getType();
        var genericType = field.getGenericType();
        var qualifiers = extractQualifiers(field);

        // BeanManager / BeanContainer
        if (BeanManager.class.isAssignableFrom(type)
                || type == jakarta.enterprise.inject.spi.BeanContainer.class) {
            return container.getBeanManager();
        }

        // Instance<T> or Provider<T>
        if (type == Instance.class || type == Provider.class) {
            var instanceType = extractGenericType(field);
            var ownerBean = container.findManagedBeanByExactClass(field.getDeclaringClass());
            var ip = new io.vidocq.vauban.core.container.VaubanInjectionPoint(field, ownerBean);
            return new InstanceImpl<>(container, instanceType, qualifiers, ip);
        }

        // Event<T>
        if (type == Event.class) {
            var ownerBean = container.findManagedBeanByExactClass(field.getDeclaringClass());
            var ip = new io.vidocq.vauban.core.container.VaubanInjectionPoint(field, ownerBean);
            return new EventImpl<>(container.eventDispatcher(), qualifiers, ip);
        }

        // Regular bean — use BeanManager with qualifiers for proper resolution
        try {
            var bm = container.getBeanManager();
            var beans = bm.getBeans(genericType, qualifiers);
            if (beans.isEmpty()) {
                beans = bm.getBeans(type, qualifiers);
            }
            if (beans.isEmpty()) {
                beans = bm.getBeans(type);
            }
            if (beans.isEmpty()) return null;
            var bean = bm.resolve(beans);
            var ctx = bm.createCreationalContext(bean);
            return bm.getReference(bean, genericType, ctx);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts qualifier annotations from a field.
     * A qualifier is an annotation that is itself annotated with @jakarta.inject.Qualifier.
     */
    private Annotation[] extractQualifiers(Field field) {
        var qualifiers = new ArrayList<Annotation>();
        for (var ann : field.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType == Inject.class) continue;
            if (annType.isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || annType == jakarta.enterprise.inject.Default.class
                    || annType == jakarta.enterprise.inject.Any.class
                    || annType == jakarta.inject.Named.class) {
                qualifiers.add(ann);
            }
        }
        return qualifiers.toArray(new Annotation[0]);
    }

    private Class<?> extractGenericType(Field field) {
        var genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType pt) {
            var typeArg = pt.getActualTypeArguments()[0];
            if (typeArg instanceof Class<?> c) return c;
        }
        return Object.class;
    }

    @Override
    public Object[] resolve(Method method) {
        var container = ContainerHolder.get();
        if (container == null) {
            return new Object[method.getParameterCount()];
        }
        var paramTypes = method.getParameterTypes();
        var genericParamTypes = method.getGenericParameterTypes();
        var params = method.getParameters();
        var args = new Object[paramTypes.length];
        var bm = container.getBeanManager();

        for (int i = 0; i < paramTypes.length; i++) {
            try {
                var type = paramTypes[i];

                // BeanManager / BeanContainer
                if (BeanManager.class.isAssignableFrom(type)
                        || type == jakarta.enterprise.inject.spi.BeanContainer.class) {
                    args[i] = bm;
                    continue;
                }

                // Instance<T> or Provider<T>
                if (type == Instance.class || type == Provider.class) {
                    Class<?> instanceType = Object.class;
                    if (genericParamTypes[i] instanceof ParameterizedType pt) {
                        var typeArg = pt.getActualTypeArguments()[0];
                        if (typeArg instanceof Class<?> c) instanceType = c;
                    }
                    var ownerBean = container.findManagedBeanByExactClass(method.getDeclaringClass());
                    var qualifiers = extractParamQualifiers(params[i]);
                    var ip = new io.vidocq.vauban.core.container.VaubanInjectionPoint(genericParamTypes[i], new java.util.HashSet<>(java.util.Arrays.asList(qualifiers)), ownerBean, method);
                    args[i] = new InstanceImpl<>(container, instanceType, qualifiers, ip);
                    continue;
                }

                // Event<T>
                if (type == Event.class) {
                    var ownerBean = container.findManagedBeanByExactClass(method.getDeclaringClass());
                    var qualifiers = extractParamQualifiers(params[i]);
                    var ip = new io.vidocq.vauban.core.container.VaubanInjectionPoint(genericParamTypes[i], new java.util.HashSet<>(java.util.Arrays.asList(qualifiers)), ownerBean, method);
                    args[i] = new EventImpl<>(container.eventDispatcher(), qualifiers, ip);
                    continue;
                }

                // Regular bean — resolve with qualifiers
                var qualifiers = extractParamQualifiers(params[i]);
                var beans = bm.getBeans(type, qualifiers);
                if (beans.isEmpty() && qualifiers.length > 0) {
                    beans = bm.getBeans(type);
                }
                if (!beans.isEmpty()) {
                    var bean = bm.resolve(beans);
                    var ctx = bm.createCreationalContext(bean);
                    args[i] = bm.getReference(bean, type, ctx);
                }
            } catch (Exception e) {
                // Leave null if resolution fails
            }
        }
        return args;
    }

    private Annotation[] extractParamQualifiers(java.lang.reflect.Parameter param) {
        var qualifiers = new ArrayList<Annotation>();
        for (var ann : param.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType == Inject.class) continue;
            if (annType.isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || annType == jakarta.enterprise.inject.Default.class
                    || annType == jakarta.enterprise.inject.Any.class
                    || annType == jakarta.inject.Named.class) {
                qualifiers.add(ann);
            }
        }
        return qualifiers.toArray(new Annotation[0]);
    }
}
