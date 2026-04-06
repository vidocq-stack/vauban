package fr.vidocq.vauban.junit;

import fr.vidocq.vauban.core.container.VaubanContainer;

import org.junit.jupiter.api.extension.*;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * JUnit 6 extension that bootstraps a Vauban CDI container for test classes.
 * <p>
 * Usage:
 * <pre>
 * &#64;VaubanTest
 * &#64;AddBeans({MyService.class, MyRepository.class})
 * class MyServiceTest {
 *     &#64;Inject MyService service;
 *
 *     &#64;Test void shouldWork() { ... }
 * }
 * </pre>
 */
public final class VaubanExtension
        implements BeforeAllCallback, AfterAllCallback, TestInstancePostProcessor {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(VaubanExtension.class);
    private static final String CONTAINER_KEY = "vaubanContainer";

    @Override
    public void beforeAll(ExtensionContext context) {
        var testClass = context.getRequiredTestClass();
        var addBeans = testClass.getAnnotation(AddBeans.class);

        var builder = VaubanContainer.builder();

        if (addBeans != null) {
            for (var beanClass : addBeans.value()) {
                builder.addBeanClass(beanClass);
            }
        }

        var container = builder.build();
        context.getStore(NS).put(CONTAINER_KEY, container);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        var container = getContainer(context);
        if (container != null) {
            container.close();
        }
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        var container = getContainer(context);
        if (container == null) return;

        injectFields(testInstance, container);
    }

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private void injectFields(Object instance, VaubanContainer container) throws IllegalAccessException {
        var lookup = container.getVaubanLookup();
        var fields = collectInjectableFields(instance.getClass());
        for (var field : fields) {
            var bean = container.select(field.getType());
            lookup.makeAccessible(field);
            field.set(instance, bean);
        }
    }

    private java.util.List<Field> collectInjectableFields(Class<?> clazz) {
        var fields = new ArrayList<Field>();
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private VaubanContainer getContainer(ExtensionContext context) {
        // Walk up to class-level store
        var store = context.getStore(NS);
        var container = store.get(CONTAINER_KEY, VaubanContainer.class);
        if (container == null && context.getParent().isPresent()) {
            container = context.getParent().get().getStore(NS).get(CONTAINER_KEY, VaubanContainer.class);
        }
        return container;
    }
}
