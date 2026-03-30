package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.BeanId;
import fr.vidocq.vauban.core.bean.model.DisposerDescriptor;
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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
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

    /**
     * ThreadLocal tracking the current injection point. When a @Dependent bean is being
     * created as a dependency, this holds the InjectionPoint of the field/parameter
     * that triggered the creation. The dependent bean can then @Inject InjectionPoint
     * to discover where it was injected.
     */
    private static volatile VaubanContainer currentInstance;

    /**
     * Returns the currently running container instance, or {@code null} if none.
     */
    public static VaubanContainer current() {
        return currentInstance;
    }

    private static final ThreadLocal<InjectionPoint> currentInjectionPoint = new ThreadLocal<>();

    /**
     * Returns the current injection point (used by built-in InjectionPoint bean).
     */
    static InjectionPoint getCurrentInjectionPoint() {
        return currentInjectionPoint.get();
    }

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
                            List<DisposerDescriptor> disposers,
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
                factory = createManagedBeanFactory(descriptor, factories);
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

        // Wire up disposer methods for producer beans
        wireDisposers(descriptors, disposers);

        this.beanManager = new VaubanBeanManager(this, contexts, beans.values(), eventDispatcher, interceptorManager);
        this.running = true;
        currentInstance = this;
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
        // 1. Inject fields — use reflection directly to catch all @Inject fields,
        // not just those found by BeanDiscovery (which may miss fields if scanning was incomplete)
        injectFieldsByReflection(instance);

        // 2. Call @Inject initializer methods
        callInitializerMethods(instance);

        // 3. Call @PostConstruct
        callPostConstruct(instance);
    }

    private void injectFieldsByReflection(Object instance) {
        var clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (var field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
                field.setAccessible(true);
                try {

                // Handle InjectionPoint injection — the dependent bean receives the
                // InjectionPoint that describes WHERE it was injected (set by the caller)
                if (field.getType() == InjectionPoint.class) {
                    field.set(instance, currentInjectionPoint.get());
                    continue;
                }

                // Handle Instance<T> and Provider<T> injection
                if (field.getType() == Instance.class
                        || field.getType() == jakarta.inject.Provider.class) {
                    Class<?> instanceType = Object.class;
                    var genericType = field.getGenericType();
                    if (genericType instanceof ParameterizedType pt) {
                        var typeArg = pt.getActualTypeArguments()[0];
                        if (typeArg instanceof Class<?> c) {
                            instanceType = c;
                        }
                    }
                    field.set(instance, new InstanceImpl<>(this, instanceType));
                    continue;
                }

                // Handle BeanManager / BeanContainer injection
                if (BeanManager.class.isAssignableFrom(field.getType())) {
                    field.set(instance, getBeanManager());
                    continue;
                }

                // Handle Event<T> injection
                if (field.getType() == Event.class) {
                    field.set(instance, new EventImpl<>(eventDispatcher));
                    continue;
                }

                // Set the current InjectionPoint before resolving the dependency.
                // This allows @Dependent beans to @Inject InjectionPoint and discover
                // where they were injected.
                var previousIp = currentInjectionPoint.get();
                var bean = findBeanForInstance(instance);
                currentInjectionPoint.set(new VaubanInjectionPoint(field, bean));
                try {
                    var value = select(field.getType());
                    field.set(instance, value);
                } finally {
                    currentInjectionPoint.set(previousIp);
                }
            } catch (Exception e) {
                // Skip fields that can't be resolved (may not be CDI beans)
            }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Find the ManagedBean corresponding to the given instance's class.
     */
    private ManagedBean<?> findBeanForInstance(Object instance) {
        var instanceClass = instance.getClass();
        for (var bean : beans.values()) {
            if (bean.getBeanClass() == instanceClass) {
                return bean;
            }
        }
        return null;
    }

    private void callInitializerMethods(Object instance) {
        for (var method : instance.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                method.setAccessible(true);
                try {
                    var paramTypes = method.getParameterTypes();
                    var genericParamTypes = method.getGenericParameterTypes();
                    var args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i]);
                    }
                    method.invoke(instance, args);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to call initializer method: " + method.getName(), e);
                }
            }
        }
    }

    private void callPostConstruct(Object instance) {
        var clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.annotation.PostConstruct.class)) {
                    method.setAccessible(true);
                    try {
                        method.invoke(instance);
                    } catch (Exception e) {
                        throw new RuntimeException("@PostConstruct failed: " + method, e);
                    }
                    return; // Only call the first (most specific) @PostConstruct
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private void wireDisposers(List<BeanDescriptor> descriptors, List<DisposerDescriptor> disposers) {
        for (var descriptor : descriptors) {
            if (descriptor.kind() != BeanDescriptor.BeanKind.PRODUCER_METHOD
                    && descriptor.kind() != BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                continue;
            }

            var bean = beans.get(descriptor.id());
            if (bean == null) continue;

            // Find a matching disposer: same declaring class, matching disposed type and qualifiers
            for (var disposer : disposers) {
                if (!disposer.declaringClass().equals(descriptor.beanClass())) continue;

                // Check if the disposed type matches any of the producer bean types
                boolean typeMatches = false;
                for (var bt : descriptor.types()) {
                    if (bt.equals(disposer.disposedType())) {
                        typeMatches = true;
                        break;
                    }
                    // Also check by raw class name match for ClassType
                    if (bt instanceof TypeInfo.ClassType btCt
                            && disposer.disposedType() instanceof TypeInfo.ClassType dCt
                            && btCt.name().equals(dCt.name())) {
                        typeMatches = true;
                        break;
                    }
                }
                if (!typeMatches) continue;

                // Check qualifier match: disposer qualifiers must be subset of producer qualifiers
                // CDI spec: disposer qualifiers without @Default/@Any must match
                var disposerQuals = disposer.qualifiers().stream()
                        .filter(q -> !q.isDefault() && !q.isAny())
                        .collect(java.util.stream.Collectors.toSet());
                var producerQuals = descriptor.qualifiers().stream()
                        .filter(q -> !q.isDefault() && !q.isAny())
                        .collect(java.util.stream.Collectors.toSet());
                boolean qualifiersMatch = producerQuals.containsAll(disposerQuals);
                if (!qualifiersMatch) continue;

                bean.setDestroyer(instance -> callDisposer(instance, disposer));
                break;
            }
        }
    }

    private void callDisposer(Object producedInstance, DisposerDescriptor disposer) {
        try {
            var declaringClass = Class.forName(disposer.declaringClass().value());
            var declaringInstance = select(declaringClass);

            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(disposer.methodName())
                        && method.getParameterCount() > disposer.parameterIndex()) {
                    method.setAccessible(true);
                    // Build args - the @Disposes param gets the produced instance, others are injection points
                    var paramTypes = method.getParameterTypes();
                    var args = new Object[method.getParameterCount()];
                    args[disposer.parameterIndex()] = producedInstance;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (i == disposer.parameterIndex()) continue;
                        try {
                            if (paramTypes[i] == BeanManager.class) {
                                args[i] = getBeanManager();
                            } else if (paramTypes[i] == Event.class) {
                                args[i] = new EventImpl<>(eventDispatcher);
                            } else if (paramTypes[i] == Instance.class) {
                                Class<?> instanceType = Object.class;
                                var genericType = method.getGenericParameterTypes()[i];
                                if (genericType instanceof ParameterizedType pt) {
                                    var typeArg = pt.getActualTypeArguments()[0];
                                    if (typeArg instanceof Class<?> c) instanceType = c;
                                }
                                args[i] = new InstanceImpl<>(this, instanceType);
                            } else {
                                args[i] = select(paramTypes[i]);
                            }
                        } catch (Exception e) {
                            // Best effort for other params
                        }
                    }
                    method.invoke(declaringInstance, args);
                    return;
                }
            }
        } catch (Exception e) {
            // CDI spec: exceptions in disposer methods are suppressed
        }
    }

    private static String extractFieldName(String description) {
        // "field ClassName.fieldName" -> "fieldName"
        int dot = description.lastIndexOf('.');
        return dot >= 0 ? description.substring(dot + 1) : null;
    }

    private BeanFactory<?> createManagedBeanFactory(BeanDescriptor descriptor,
                                                     Map<DotName, BeanFactory<?>> factories) {
        // Check if bean has @Inject constructor parameters
        var ctorParams = descriptor.injectionPoints().stream()
                .filter(ip -> ip.kind() == InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER)
                .toList();

        if (ctorParams.isEmpty()) {
            // No @Inject constructor — use the pre-registered factory (no-arg constructor)
            return factories.get(descriptor.beanClass());
        }

        // Has @Inject constructor — create a factory that resolves parameters
        return () -> {
            try {
                var beanClass = Class.forName(descriptor.beanClass().value());

                // Find the @Inject constructor (the one with matching parameter count)
                java.lang.reflect.Constructor<?> injectCtor = null;
                for (var ctor : beanClass.getDeclaredConstructors()) {
                    if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        injectCtor = ctor;
                        break;
                    }
                }

                if (injectCtor == null) {
                    // Fallback: try matching by parameter count
                    for (var ctor : beanClass.getDeclaredConstructors()) {
                        if (ctor.getParameterCount() == ctorParams.size()) {
                            injectCtor = ctor;
                            break;
                        }
                    }
                }

                if (injectCtor == null) {
                    throw new RuntimeException("No @Inject constructor found for " + descriptor.beanClass());
                }

                // Resolve each constructor parameter
                var paramTypes = injectCtor.getParameterTypes();
                var genericParamTypes = injectCtor.getGenericParameterTypes();
                var args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i] == Instance.class
                            || paramTypes[i] == jakarta.inject.Provider.class) {
                        Class<?> instanceType = Object.class;
                        if (genericParamTypes[i] instanceof ParameterizedType pt) {
                            var typeArg = pt.getActualTypeArguments()[0];
                            if (typeArg instanceof Class<?> c) {
                                instanceType = c;
                            }
                        }
                        args[i] = new InstanceImpl<>(this, instanceType);
                    } else if (paramTypes[i] == BeanManager.class) {
                        args[i] = getBeanManager();
                    } else if (paramTypes[i] == Event.class) {
                        args[i] = new EventImpl<>(eventDispatcher);
                    } else {
                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i]);
                    }
                }

                injectCtor.setAccessible(true);
                return injectCtor.newInstance(args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new jakarta.enterprise.inject.CreationException(cause);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new jakarta.enterprise.inject.CreationException(e);
            }
        };
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
                        // Resolve parameters as injection points
                        var paramTypes = method.getParameterTypes();
                        var genericParamTypes = method.getGenericParameterTypes();
                        var args = new Object[paramTypes.length];
                        for (int i = 0; i < paramTypes.length; i++) {
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i]);
                        }
                        return method.invoke(declaringInstance, args);
                    }
                }
                throw new RuntimeException("Producer method not found: " + methodName + " in " + descriptor.beanClass());
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Unwrap the target exception — CDI spec says producer exceptions propagate as-is
                var cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new jakarta.enterprise.inject.CreationException(cause);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new jakarta.enterprise.inject.CreationException(
                        "Failed to invoke producer method: " + descriptor.id(), e);
            }
        };
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType) {
        if (paramType == Event.class) {
            return new EventImpl<>(eventDispatcher);
        }
        if (paramType == Instance.class || paramType == jakarta.inject.Provider.class) {
            Class<?> instanceType = Object.class;
            if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                var typeArg = pt.getActualTypeArguments()[0];
                if (typeArg instanceof Class<?> c) instanceType = c;
            }
            return new InstanceImpl<>(this, instanceType);
        }
        if (BeanManager.class.isAssignableFrom(paramType)) {
            return getBeanManager();
        }
        return select(paramType);
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
                throw new jakarta.enterprise.inject.CreationException(
                        "Failed to read producer field: " + descriptor.id(), e);
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
        if (currentInstance == this) {
            currentInstance = null;
        }
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

                // Auto-register factory — will be replaced with constructor-aware
                // version after discovery if @Inject constructor is found
                if (!factories.containsKey(DotName.of(clazz.getName()))) {
                    var beanClass2 = clazz;
                    factories.put(DotName.of(clazz.getName()), () -> {
                        try {
                            var ctor = beanClass2.getDeclaredConstructor();
                            ctor.setAccessible(true);
                            return ctor.newInstance();
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            var cause = e.getCause();
                            if (cause instanceof RuntimeException re) throw re;
                            throw new jakarta.enterprise.inject.CreationException(cause);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new jakarta.enterprise.inject.CreationException(e);
                        }
                    });
                }
            }

            var index = indexBuilder.build();

            // Validate class-level CDI rules (before bean discovery)
            var classErrors = fr.vidocq.vauban.core.bean.validation.ClassValidator.validate(index);
            if (!classErrors.isEmpty()) {
                var msg = new StringBuilder("CDI definition validation failed:\n");
                for (var error : classErrors) {
                    msg.append("  - ").append(error).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
            }

            // Reflection-based validation (for generic signatures not in bytecode index)
            var reflectionErrors = validateWithReflection(beanClasses);
            if (!reflectionErrors.isEmpty()) {
                var msg = new StringBuilder("CDI definition validation failed:\n");
                for (var error : reflectionErrors) {
                    msg.append("  - ").append(error).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
            }

            var discovery = new BeanDiscovery(index);
            var descriptors = discovery.discoverBeans();
            var observers = discovery.discoverObservers();
            var interceptors = discovery.discoverInterceptors();
            var disposers = discovery.discoverDisposerMethods();

            // Validate deployment — throw if there are errors
            var assignability = new AssignabilityRules(index);
            var tempResolver = new BeanResolver(descriptors, assignability);
            var validator = new fr.vidocq.vauban.core.bean.validation.DeploymentValidator(
                    descriptors, tempResolver);
            var errors = validator.validate();
            if (!errors.isEmpty()) {
                var msg = new StringBuilder("CDI deployment validation failed:\n");
                for (var error : errors) {
                    msg.append("  - ").append(error.message()).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
            }

            return new VaubanContainer(index, descriptors, observers, interceptors, disposers, factories);
        }

        /**
         * Validates CDI rules that require generic type information (only available via reflection).
         * Detects raw Event/Instance injection, producer type variables, generic beans, etc.
         */
        private static List<String> validateWithReflection(List<Class<?>> beanClasses) {
            var errors = new ArrayList<String>();
            for (var clazz : beanClasses) {
                // Skip interfaces, annotations, enums
                if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum()) continue;

                // Generic managed bean — only invalid if it's the only concrete class
                // with unresolved type params and no concrete subclass resolves them.
                // This is too complex to validate here; deferred.

                // Check @Inject fields for raw Event/Instance
                for (var field : clazz.getDeclaredFields()) {
                    if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
                    validateNoRawParameterized(field.getGenericType(), field.getType(),
                            clazz.getName() + "." + field.getName(), errors);
                }

                // Check methods
                for (var method : clazz.getDeclaredMethods()) {
                    // Producer method return type validation
                    if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        validateProducerReturnType(method.getGenericReturnType(),
                                clazz.getName() + "." + method.getName(), errors);
                    }

                    // Generic initializer method
                    if (method.isAnnotationPresent(jakarta.inject.Inject.class)
                            && method.getTypeParameters().length > 0
                            && !method.getName().equals("<init>")) {
                        errors.add("Initializer method " + clazz.getName() + "." + method.getName()
                                + " cannot declare type parameters");
                    }

                    // @Inject method parameters: raw Event/Instance
                    if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        var paramTypes = method.getGenericParameterTypes();
                        var rawTypes = method.getParameterTypes();
                        for (int i = 0; i < paramTypes.length; i++) {
                            validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                    clazz.getName() + "." + method.getName() + " param " + i, errors);
                        }
                    }

                    // Observer method injection parameters: raw Event/Instance
                    var params = method.getParameters();
                    boolean hasObserves = false;
                    for (var p : params) {
                        if (p.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || p.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                            hasObserves = true;
                            break;
                        }
                    }
                    if (hasObserves) {
                        var paramTypes = method.getGenericParameterTypes();
                        var rawTypes = method.getParameterTypes();
                        for (int i = 0; i < params.length; i++) {
                            if (!params[i].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                    && !params[i].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                                validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                        clazz.getName() + "." + method.getName() + " observer param", errors);
                            }
                        }
                    }

                    // Disposer method injection parameters: raw Event/Instance
                    boolean hasDisposes = false;
                    for (var p : params) {
                        if (p.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                            hasDisposes = true;
                            break;
                        }
                    }
                    if (hasDisposes) {
                        var paramTypes = method.getGenericParameterTypes();
                        var rawTypes = method.getParameterTypes();
                        for (int i = 0; i < params.length; i++) {
                            if (!params[i].isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                                validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                        clazz.getName() + "." + method.getName() + " disposer param", errors);
                            }
                        }
                    }

                    // Producer method parameters: raw Event/Instance
                    if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        var paramTypes = method.getGenericParameterTypes();
                        var rawTypes = method.getParameterTypes();
                        for (int i = 0; i < paramTypes.length; i++) {
                            validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                    clazz.getName() + "." + method.getName() + " producer param", errors);
                        }
                    }
                }

                // Check producer fields
                for (var field : clazz.getDeclaredFields()) {
                    if (field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        validateProducerReturnType(field.getGenericType(),
                                clazz.getName() + "." + field.getName(), errors);
                    }
                }

                // Check constructors for raw Event/Instance
                for (var ctor : clazz.getDeclaredConstructors()) {
                    if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        var paramTypes = ctor.getGenericParameterTypes();
                        var rawTypes = ctor.getParameterTypes();
                        for (int i = 0; i < paramTypes.length; i++) {
                            validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                    clazz.getName() + " constructor param " + i, errors);
                        }
                    }
                }
            }
            return errors;
        }

        private static void validateNoRawParameterized(java.lang.reflect.Type genericType,
                Class<?> rawType, String location, List<String> errors) {
            if (rawType == jakarta.enterprise.event.Event.class
                    && !(genericType instanceof java.lang.reflect.ParameterizedType)) {
                errors.add("Raw Event type injected at " + location + " — must be parameterized");
            }
            if (rawType == jakarta.enterprise.inject.Instance.class
                    && !(genericType instanceof java.lang.reflect.ParameterizedType)) {
                errors.add("Raw Instance type injected at " + location + " — must be parameterized");
            }
        }

        private static void validateProducerReturnType(java.lang.reflect.Type type, String location,
                List<String> errors) {
            // CDI spec: producer return type cannot be a naked type variable or wildcard
            if (type instanceof java.lang.reflect.TypeVariable<?>) {
                errors.add("Producer " + location + " has type variable return type");
            }
            if (type instanceof java.lang.reflect.WildcardType) {
                errors.add("Producer " + location + " has wildcard return type");
            }
            // Parameterized types with type variables are OK (e.g. List<T> from a generic class)
            // Only naked wildcards in top-level return type are invalid
            if (type instanceof java.lang.reflect.GenericArrayType gat) {
                var componentType = gat.getGenericComponentType();
                if (componentType instanceof java.lang.reflect.TypeVariable<?>) {
                    errors.add("Producer " + location + " has array type with type variable component");
                }
                if (componentType instanceof java.lang.reflect.WildcardType) {
                    errors.add("Producer " + location + " has array type with wildcard component");
                }
            }
        }

        private static boolean hasBeanDefiningAnnotation(Class<?> clazz) {
            for (var ann : clazz.getAnnotations()) {
                var annType = ann.annotationType();
                if (annType == jakarta.enterprise.context.ApplicationScoped.class
                        || annType == jakarta.enterprise.context.RequestScoped.class
                        || annType == jakarta.enterprise.context.Dependent.class
                        || annType == jakarta.inject.Singleton.class) return true;
                if (annType.isAnnotationPresent(jakarta.inject.Scope.class)
                        || annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                        || annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) return true;
            }
            // @Inject constructor
            for (var ctor : clazz.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
            }
            return false;
        }
    }
}
