package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.InjectionPointInfo;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.context.ApplicationContext;
import fr.vidocq.vauban.core.context.CreationalContextImpl;
import fr.vidocq.vauban.core.context.DependentContext;
import fr.vidocq.vauban.core.context.RequestContext;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Vauban CDI container.
 * Minimal SE container supporting bootstrap, lookup, contexts, and shutdown.
 */
public final class VaubanContainer implements AutoCloseable {

    private final Map<DotName, ManagedBean<?>> beans = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, Context> contexts = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;
    private final RequestContext requestContext;
    private final DependentContext dependentContext;
    private final BeanResolver resolver;
    private final VaubanIndex index;
    private final VaubanBeanManager beanManager;
    private volatile boolean running;

    private VaubanContainer(VaubanIndex index, List<BeanDescriptor> descriptors,
                            Map<DotName, BeanFactory<?>> factories) {
        this.index = index;
        this.applicationContext = new ApplicationContext();
        this.requestContext = new RequestContext();
        this.dependentContext = new DependentContext();

        contexts.put(jakarta.enterprise.context.ApplicationScoped.class, applicationContext);
        contexts.put(jakarta.enterprise.context.RequestScoped.class, requestContext);
        contexts.put(jakarta.enterprise.context.Dependent.class, dependentContext);
        contexts.put(jakarta.inject.Singleton.class, applicationContext);

        for (var descriptor : descriptors) {
            var factory = factories.get(descriptor.beanClass());
            if (factory != null) {
                beans.put(descriptor.beanClass(), new ManagedBean<>(descriptor, factory));
            }
        }

        var assignability = new AssignabilityRules(index);
        this.resolver = new BeanResolver(descriptors, assignability);

        // Wire up field injection on each bean
        for (var bean : beans.values()) {
            bean.setInjector(instance -> injectFields(instance, bean.descriptor()));
        }

        this.beanManager = new VaubanBeanManager(this, contexts, beans.values());
        this.running = true;
    }

    /**
     * Look up a bean by type. Returns a contextual instance.
     */
    @SuppressWarnings("unchecked")
    public <T> T select(Class<T> type) {
        var typeInfo = new TypeInfo.ClassType(DotName.of(type.getName()));
        var resolved = resolver.resolve(typeInfo, Set.of(QualifierInstance.DEFAULT));
        if (resolved.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No bean found for type: " + type.getName());
        }
        if (resolved.size() > 1) {
            throw new jakarta.enterprise.inject.AmbiguousResolutionException(
                    "Multiple beans found for type: " + type.getName());
        }

        var descriptor = resolved.getFirst();
        var bean = (ManagedBean<T>) beans.get(descriptor.beanClass());
        if (bean == null) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No factory registered for bean: " + descriptor.beanClass());
        }

        return getContextualInstance(bean);
    }

    @SuppressWarnings("unchecked")
    private <T> T getContextualInstance(ManagedBean<T> bean) {
        var scopeClass = bean.getScope();
        var context = contexts.get(scopeClass);
        if (context == null) {
            context = dependentContext;
        }
        return context.get((Contextual<T>) bean, new CreationalContextImpl<>());
    }

    public boolean isRunning() {
        return running;
    }

    public RequestContext requestContext() {
        return requestContext;
    }

    public VaubanBeanManager getBeanManager() {
        return beanManager;
    }

    Map<Class<? extends Annotation>, Context> contexts() {
        return contexts;
    }

    private void injectFields(Object instance, BeanDescriptor descriptor) {
        for (var ip : descriptor.injectionPoints()) {
            if (ip.kind() != InjectionPointInfo.InjectionKind.FIELD) continue;

            var fieldName = extractFieldName(ip.description());
            if (fieldName == null) continue;

            try {
                var field = instance.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                var value = select(field.getType());
                field.set(instance, value);
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject field: " + ip.description(), e);
            }
        }
    }

    private static String extractFieldName(String description) {
        // "field ClassName.fieldName" -> "fieldName"
        int dot = description.lastIndexOf('.');
        return dot >= 0 ? description.substring(dot + 1) : null;
    }

    @Override
    public void close() {
        if (!running) return;
        running = false;
        requestContext.deactivate();
        applicationContext.deactivate();
    }

    /**
     * Builder for creating a VaubanContainer.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<Class<?>> beanClasses = new ArrayList<>();
        private final Map<DotName, BeanFactory<?>> factories = new LinkedHashMap<>();

        /**
         * Add a bean class. The container will scan it and create a default factory.
         */
        public Builder addBeanClass(Class<?> beanClass) {
            beanClasses.add(beanClass);
            return this;
        }

        /**
         * Register a custom factory for a bean class.
         */
        public <T> Builder addFactory(Class<T> beanClass, BeanFactory<T> factory) {
            factories.put(DotName.of(beanClass.getName()), factory);
            return this;
        }

        public VaubanContainer build() {
            var indexBuilder = new IndexBuilder();

            for (var clazz : beanClasses) {
                try {
                    String resource = clazz.getName().replace('.', '/') + ".class";
                    try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
                        if (is != null) {
                            indexBuilder.add(ClassFileScanner.scan(is.readAllBytes()));
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to scan class: " + clazz.getName(), e);
                }

                // Auto-register factory using reflection if none provided
                if (!factories.containsKey(DotName.of(clazz.getName()))) {
                    var beanClass2 = clazz;
                    factories.put(DotName.of(clazz.getName()), () -> {
                        try {
                            return beanClass2.getDeclaredConstructor().newInstance();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to create: " + beanClass2.getName(), e);
                        }
                    });
                }
            }

            var index = indexBuilder.build();
            var discovery = new BeanDiscovery(index);
            var descriptors = discovery.discoverBeans();

            return new VaubanContainer(index, descriptors, factories);
        }
    }
}
