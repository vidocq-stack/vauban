package fr.vidocq.vauban.tck;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Arquillian TestEnricher that injects the VaubanBeanManager into test instances.
 * Handles @Inject BeanManager fields used by the CDI TCK's AbstractTest.
 */
public class VaubanTestEnricher implements TestEnricher {

    @Override
    public void enrich(Object testCase) {
        var container = ContainerHolder.get();
        if (container == null) {
            return;
        }

        var beanManager = container.getBeanManager();

        // Walk up the class hierarchy to find all @Inject fields
        Class<?> clazz = testCase.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)) {
                    try {
                        field.setAccessible(true);
                        if (BeanManager.class.isAssignableFrom(field.getType())) {
                            field.set(testCase, beanManager);
                        } else {
                            // Try to resolve other injected types from the container
                            try {
                                var instance = container.select(field.getType());
                                field.set(testCase, instance);
                            } catch (Exception e) {
                                // Could not resolve, leave null
                            }
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Failed to enrich test field: " + field.getName(), e);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    @Override
    public Object[] resolve(Method method) {
        return new Object[method.getParameterCount()];
    }
}
