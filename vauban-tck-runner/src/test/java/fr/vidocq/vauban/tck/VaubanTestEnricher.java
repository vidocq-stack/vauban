package fr.vidocq.vauban.tck;

import fr.vidocq.vauban.core.container.InstanceImpl;
import fr.vidocq.vauban.core.event.EventImpl;
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

    private Object resolveField(Field field, fr.vidocq.vauban.core.container.VaubanContainer container) {
        var type = field.getType();
        var qualifiers = extractQualifiers(field);

        // BeanManager
        if (BeanManager.class.isAssignableFrom(type)) {
            return container.getBeanManager();
        }

        // Instance<T> or Provider<T>
        if (type == Instance.class || type == Provider.class) {
            var instanceType = extractGenericType(field);
            return new InstanceImpl<>(container, instanceType);
        }

        // Event<T>
        if (type == Event.class) {
            return new EventImpl<>(container.eventDispatcher());
        }

        // Regular bean — use BeanManager with qualifiers for proper resolution
        try {
            var bm = container.getBeanManager();
            var beans = bm.getBeans(type, qualifiers);
            if (beans.isEmpty()) {
                // Fallback: try without qualifiers (some test classes inject without explicit qualifiers)
                beans = bm.getBeans(type);
            }
            if (beans.isEmpty()) return null;
            var bean = bm.resolve(beans);
            var ctx = bm.createCreationalContext(bean);
            return bm.getReference(bean, type, ctx);
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
        return new Object[method.getParameterCount()];
    }
}
