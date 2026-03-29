package fr.vidocq.vauban.tck;

import fr.vidocq.vauban.core.container.InstanceImpl;
import fr.vidocq.vauban.core.event.EventImpl;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;

/**
 * Arquillian TestEnricher that injects CDI objects into test instances.
 * Handles @Inject for BeanManager, Instance, Event, Provider, and bean types.
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

        // Regular bean
        try {
            return container.select(type);
        } catch (Exception e) {
            return null;
        }
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
