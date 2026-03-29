package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.BeanId;
import fr.vidocq.vauban.core.bean.model.InjectionPointInfo;
import fr.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.interceptor.InterceptorManager;
import fr.vidocq.vauban.core.context.ApplicationContext;
import fr.vidocq.vauban.core.context.CreationalContextImpl;
import fr.vidocq.vauban.core.context.DependentContext;
import fr.vidocq.vauban.core.context.RequestContext;
import fr.vidocq.vauban.core.event.EventDispatcher;
import fr.vidocq.vauban.core.event.EventImpl;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.event.Event;

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

    private final Map<BeanId, ManagedBean<?>> beans = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, Context> contexts = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;
    private final RequestContext requestContext;
    private final DependentContext dependentContext;
    private final BeanResolver resolver;
    private final VaubanIndex index;
    private final EventDispatcher eventDispatcher;
    private final InterceptorManager interceptorManager;
    private final VaubanBeanManager beanManager;
    private volatile boolean running;

    private VaubanContainer(VaubanIndex index, List<BeanDescriptor> descriptors,
                            List<ObserverDescriptor> observers,
                            List<InterceptorDescriptor> interceptorDescriptors,
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
            BeanFactory<?> factory;
            if (descriptor.kind() == BeanDescriptor.BeanKind.MANAGED) {
                factory = factories.get(descriptor.beanClass());
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD) {
                factory = createProducerMethodFactory(descriptor);
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                factory = createProducerFieldFactory(descriptor);
            } else {
                continue;
            }
            if (factory != null) {
                beans.put(descriptor.id(), new ManagedBean<>(descriptor, factory));
            }
        }

        var assignability = new AssignabilityRules(index);
        this.resolver = new BeanResolver(descriptors, assignability);
        this.eventDispatcher = new EventDispatcher(observers, this);
        this.interceptorManager = new InterceptorManager(interceptorDescriptors);

        // Wire up field injection on each bean
        for (var bean : beans.values()) {
            bean.setInjector(instance -> injectFields(instance, bean.descriptor()));
        }

        this.beanManager = new VaubanBeanManager(this, contexts, beans.values(), eventDispatcher, interceptorManager);
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
        var bean = (ManagedBean<T>) beans.get(descriptor.id());
        if (bean == null) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No factory registered for bean: " + descriptor.id());
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

    public EventDispatcher eventDispatcher() {
        return eventDispatcher;
    }

    public InterceptorManager interceptorManager() {
        return interceptorManager;
    }

    private void injectFields(Object instance, BeanDescriptor descriptor) {
        for (var ip : descriptor.injectionPoints()) {
            if (ip.kind() != InjectionPointInfo.InjectionKind.FIELD) continue;

            var fieldName = extractFieldName(ip.description());
            if (fieldName == null) continue;

            try {
                var field = instance.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);

                // Handle Event<T> injection
                if (field.getType() == Event.class) {
                    field.set(instance, new EventImpl<>(eventDispatcher));
                    continue;
                }

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

    private BeanFactory<?> createProducerMethodFactory(BeanDescriptor descriptor) {
        var methodName = extractProducerMethodName(descriptor.id());
        return () -> {
            try {
                var declaringClass = Class.forName(descriptor.beanClass().value());
                var declaringInstance = select(declaringClass);

                for (var method : declaringClass.getDeclaredMethods()) {
                    if (method.getName().equals(methodName)) {
                        method.setAccessible(true);
                        if (method.getParameterCount() == 0) {
                            return method.invoke(declaringInstance);
                        }
                    }
                }
                throw new RuntimeException("Producer method not found: " + methodName + " in " + descriptor.beanClass());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke producer method: " + descriptor.id(), e);
            }
        };
    }

    private BeanFactory<?> createProducerFieldFactory(BeanDescriptor descriptor) {
        var fieldName = extractProducerFieldName(descriptor.id());
        return () -> {
            try {
                var declaringClass = Class.forName(descriptor.beanClass().value());
                var declaringInstance = select(declaringClass);
                var field = declaringClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(declaringInstance);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to read producer field: " + descriptor.id(), e);
            }
        };
    }

    private static String extractProducerMethodName(BeanId id) {
        var value = id.value();
        int hash = value.lastIndexOf('#');
        return hash >= 0 ? value.substring(hash + 1) : value;
    }

    private static String extractProducerFieldName(BeanId id) {
        var value = id.value();
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
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
            var observers = discovery.discoverObservers();
            var interceptors = discovery.discoverInterceptors();

            return new VaubanContainer(index, descriptors, observers, interceptors, factories);
        }
    }
}
