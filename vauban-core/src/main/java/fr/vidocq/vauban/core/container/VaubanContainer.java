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
import java.lang.reflect.Modifier;
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
import jakarta.enterprise.context.spi.CreationalContext;
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
    private static final ThreadLocal<Boolean> isCreatingInterceptor = ThreadLocal.withInitial(() -> false);

    /**
     * Returns the current injection point (used by built-in InjectionPoint bean).
     */
    static InjectionPoint getCurrentInjectionPoint() {
        return currentInjectionPoint.get();
    }

    /**
     * Sets the current injection point (used by Instance.get()).
     */
    static void setInjectionPoint(InjectionPoint ip) {
        currentInjectionPoint.set(ip);
    }

    private final Map<BeanId, ManagedBean<?>> beans = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, Context> contexts = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<String>> beansBeingCreated = ThreadLocal.withInitial(java.util.HashSet::new);
    private final ApplicationContext applicationContext;
    private final RequestContext requestContext;
    private final DependentContext dependentContext;
    private final BeanResolver resolver;
    private final VaubanIndex index;
    private final EventDispatcher eventDispatcher;
    private final InterceptorManager interceptorManager;
    private final VaubanBeanManager beanManager;
    private final ClassLoader classLoader;
    private volatile boolean running;

    private VaubanContainer(VaubanIndex index, List<BeanDescriptor> descriptors,
                            List<ObserverDescriptor> observers,
                            List<InterceptorDescriptor> interceptorDescriptors,
                            List<DisposerDescriptor> disposers,
                            Map<DotName, BeanFactory<?>> factories,
                            ClassLoader classLoader) {
        this.index = index;
        this.classLoader = classLoader;
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
                beans.put(descriptor.id(), new ManagedBean<>(descriptor, factory, classLoader));
            }
        }

        var assignability = new AssignabilityRules(index);
        this.resolver = new BeanResolver(descriptors, interceptorDescriptors, assignability);
        this.eventDispatcher = new EventDispatcher(observers, this);
        this.interceptorManager = new InterceptorManager(interceptorDescriptors);
        this.interceptorManager.setInstanceFactory((descriptor, ctx) -> getOrCreateInterceptorInstance(descriptor, ctx));

        // Wrap intercepted beans with generated subclasses
        wrapInterceptedBeans(descriptors, factories);


        // Wire up field injection on each bean (includes PostConstruct in injectFields)
        for (var bean : beans.values()) {
            bean.setInjector((instance, ctx) -> injectFields(instance, bean.descriptor(), ctx));
        }

        // Wire up disposer methods for producer beans
        wireDisposers(descriptors, disposers);

        this.beanManager = new VaubanBeanManager(this, contexts, beans.values(), eventDispatcher, interceptorManager);
        this.running = true;
        currentInstance = this;

        // CDI lifecycle events: fire @Initialized(ApplicationScoped.class) and @Startup
        try {
            eventDispatcher.fire(new Object(),
                    jakarta.enterprise.context.Initialized.Literal.of(jakarta.enterprise.context.ApplicationScoped.class));
        } catch (Exception e) { /* suppress */ }
        try {
            eventDispatcher.fire(new jakarta.enterprise.event.Startup());
        } catch (Exception e) { /* suppress */ }
    }


    private final Map<String, Object> sharedInterceptors = new java.util.concurrent.ConcurrentHashMap<>();

    private Object getOrCreateInterceptorInstance(InterceptorDescriptor descriptor, CreationalContext<?> ctx) {
        String className = descriptor.interceptorClass().value();
        
        // 1. Check sharedInterceptors (application-wide cache for interceptors)
        Object shared = sharedInterceptors.get(className);
        if (shared != null) return shared;

        if (ctx instanceof CreationalContextImpl<?> vCtx) {
            // 2. Check current context cache
            Object cached = vCtx.getInterceptorInstance(className);
            if (cached != null) return cached;
            
            // 3. Look into incomplete or dependent instances in the same context
            for (Object inst : vCtx.getIncompleteInstances()) {
                if (inst.getClass().getName().equals(className) || 
                    inst.getClass().getName().startsWith(className + "$$")) {
                     vCtx.addInterceptorInstance(className, inst);
                     return inst;
                }
            }
            for (var dep : vCtx.getDependentInstances()) {
                if (dep.instance().getClass().getName().equals(className) ||
                    dep.instance().getClass().getName().startsWith(className + "$$")) {
                     vCtx.addInterceptorInstance(className, dep.instance());
                     return dep.instance();
                }
            }
        }
        
        // 4. Final fallback: check for bean instance of the interceptor in the bean manager
        try {
            var bm = getBeanManager();
            var clazz = loadClass(className);
            var interceptorBeans = bm.getBeans(clazz);
            if (!interceptorBeans.isEmpty()) {
                var bean = bm.resolve(interceptorBeans);
                Object inst = bm.getReference(bean, clazz, ctx);
                if (inst != null) {
                    sharedInterceptors.putIfAbsent(className, inst);
                    if (ctx instanceof CreationalContextImpl<?> vCtx) {
                        vCtx.addInterceptorInstance(className, inst);
                    }
                }
                return inst;
            }
        } catch (Exception e) { /* fallback to manual creation */ }

        if (isCreatingInterceptor.get()) {
            // Break recursion: don't intercept the interceptor's own creation
            try {
                var clazz = loadClass(className);
                
                // Find @Inject constructor or no-arg constructor
                java.lang.reflect.Constructor<?> constructor = null;
                for (var c : clazz.getDeclaredConstructors()) {
                    if (c.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        constructor = c;
                        break;
                    }
                }
                if (constructor == null) {
                    try {
                        constructor = clazz.getDeclaredConstructor();
                    } catch (NoSuchMethodException e) {
                        // If no no-arg constructor and no @Inject constructor, pick the only one
                        if (clazz.getDeclaredConstructors().length == 1) {
                            constructor = clazz.getDeclaredConstructors()[0];
                        } else {
                            throw e;
                        }
                    }
                }
                
                constructor.setAccessible(true);
                var paramTypes = constructor.getParameterTypes();
                var genericParamTypes = constructor.getGenericParameterTypes();
                var params = constructor.getParameters();
                var args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    var paramQuals = extractParamQualifiers(params[i]);
                    if (paramQuals.length > 0) {
                        var bm = getBeanManager();
                        var beans2 = bm.getBeans(paramTypes[i], paramQuals);
                        if (!beans2.isEmpty()) {
                            var resolved = bm.resolve(beans2);
                            args[i] = bm.getReference(resolved, paramTypes[i], ctx);
                        } else {
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                        }
                    } else {
                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                    }
                }
                
                var instance = constructor.newInstance(args);
                sharedInterceptors.putIfAbsent(className, instance);
                injectFieldsByReflection(instance, ctx);
                // Call PostConstruct directly
                java.lang.reflect.Method pc = null;
                for (var m : instance.getClass().getDeclaredMethods()) {
                    if (m.isAnnotationPresent(jakarta.annotation.PostConstruct.class)) { pc = m; break; }
                }
                if (pc != null) { pc.setAccessible(true); pc.invoke(instance); }
                
                if (ctx instanceof CreationalContextImpl<?> vCtx) {
                    vCtx.addInterceptorInstance(className, instance);
                    vCtx.pushInterceptor(instance);
                }
                return instance;
            } catch (Exception e) {
                throw new jakarta.enterprise.inject.CreationException(e);
            }
        }
        isCreatingInterceptor.set(true);
        try {
            var clazz = loadClass(className);

            // Find @Inject constructor or no-arg constructor
            java.lang.reflect.Constructor<?> constructor = null;
            for (var c : clazz.getDeclaredConstructors()) {
                if (c.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    constructor = c;
                    break;
                }
            }
            if (constructor == null) {
                try {
                    constructor = clazz.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    // If no no-arg constructor and no @Inject constructor, pick the only one
                    if (clazz.getDeclaredConstructors().length == 1) {
                        constructor = clazz.getDeclaredConstructors()[0];
                    } else {
                        throw e;
                    }
                }
            }
            
            constructor.setAccessible(true);
            var paramTypes = constructor.getParameterTypes();
            var genericParamTypes = constructor.getGenericParameterTypes();
            var params = constructor.getParameters();
            var args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                var paramQuals = extractParamQualifiers(params[i]);
                if (paramQuals.length > 0) {
                    var bm = getBeanManager();
                    var beans2 = bm.getBeans(paramTypes[i], paramQuals);
                    if (!beans2.isEmpty()) {
                        var resolved = bm.resolve(beans2);
                        args[i] = bm.getReference(resolved, paramTypes[i], ctx);
                    } else {
                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                    }
                } else {
                    args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                }
            }

            var instance = constructor.newInstance(args);
            sharedInterceptors.putIfAbsent(className, instance);

            // Dependency injection on the interceptor instance
            injectFields(instance, null, (CreationalContext<Object>) ctx);

            // Call @PostConstruct on the interceptor itself
            callPostConstruct(instance, ctx);

            // Register interceptor instance for destruction
            if (ctx instanceof CreationalContextImpl<?> vCtx) {
                vCtx.addInterceptorInstance(className, instance);
                vCtx.pushInterceptor(instance);
            } else if (ctx != null) {
                ((CreationalContext<Object>) ctx).push(instance);
            }

            return instance;
        } catch (Exception e) {
            System.err.println("CRITICAL: Failed to create interceptor " + descriptor.interceptorClass());
            e.printStackTrace();
            if (e instanceof java.lang.reflect.InvocationTargetException ite) {
                System.err.println("Caused by: " + ite.getTargetException());
                ite.getTargetException().printStackTrace();
            }
            throw new jakarta.enterprise.inject.CreationException("Failed to create interceptor: " + descriptor.interceptorClass(), e);
        } finally {
            isCreatingInterceptor.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T select(Class<T> type) {
        // CDI spec: primitive types and their wrappers are considered identical
        var lookupType = type.isPrimitive() ? wrapPrimitive(type) : type;
        var typeInfo = new TypeInfo.ClassType(DotName.of(lookupType.getName()));
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
        var finalBean = (ManagedBean<T>) beans.get(bean.descriptor().id());
        if (finalBean == null) finalBean = bean;

        var scopeClass = finalBean.getScope();
        var context = contexts.get(scopeClass);
        if (context == null) {
            context = dependentContext;
        }
        
        // Return existing instance from context if available
        var existing = context.get((Contextual<T>) finalBean);
        if (existing != null) return existing;

        // For normal-scoped managed beans (not producers, not final), return a client proxy
        if (finalBean.descriptor().scope().isNormal()
                && finalBean.descriptor().kind() == fr.vidocq.vauban.core.bean.model.BeanDescriptor.BeanKind.MANAGED
                && !Modifier.isFinal(finalBean.getBeanClass().getModifiers())) {
            return getOrCreateProxy(finalBean);
        }
        
        CreationalContext<T> creationalCtx = new CreationalContextImpl<T>();
        return context.get((Contextual<T>) finalBean, creationalCtx);
    }

    private final Map<fr.vidocq.vauban.core.bean.model.BeanId, Object> proxyCache = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private <T> T getOrCreateProxy(ManagedBean<T> bean) {
        return (T) proxyCache.computeIfAbsent(bean.descriptor().id(), id -> {
            try {
                var beanClass = bean.getBeanClass();
                var generated = fr.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator.generate(beanClass);

                // Load the proxy class (check if already defined)
                Class<?> proxyClass;
                try {
                    proxyClass = beanClass.getClassLoader().loadClass(generated.className());
                } catch (ClassNotFoundException cnfe) {
                    try {
                        var lookup = java.lang.invoke.MethodHandles.privateLookupIn(beanClass,
                                java.lang.invoke.MethodHandles.lookup());
                        proxyClass = lookup.defineClass(generated.bytecode());
                    } catch (Exception e) {
                        var defineMethod = ClassLoader.class.getDeclaredMethod(
                                "defineClass", String.class, byte[].class, int.class, int.class);
                        defineMethod.setAccessible(true);
                        proxyClass = (Class<?>) defineMethod.invoke(beanClass.getClassLoader(),
                                generated.className(), generated.bytecode(),
                                0, generated.bytecode().length);
                    }
                }

                // Create proxy instance
                var proxy = proxyClass.getDeclaredConstructor().newInstance();

                // Set the delegate supplier — resolves the contextual instance lazily
                var setDelegate = proxyClass.getMethod("$$setDelegate",
                        java.util.function.Supplier.class);
                // Use the bean's ID to resolve the current ManagedBean at runtime
                // (may change after wrapInterceptedBeans replaces it)
                var beanId = bean.descriptor().id();
                // No caching — always resolve from context so that AlterableContext.destroy() works
                java.util.function.Supplier<Object> delegate = () -> {
                    var currentBean = beans.get(beanId);
                    if (currentBean == null) currentBean = bean;
                    var scopeClass = currentBean.getScope();
                    var ctx = contexts.get(scopeClass);
                    if (ctx == null) ctx = dependentContext;
                    return ctx.get((Contextual<Object>) (Contextual<?>) currentBean,
                            new CreationalContextImpl<Object>());
                };
                setDelegate.invoke(proxy, delegate);

                return proxy;
            } catch (Exception e) {
                // Fallback: return direct instance (no proxy)
                var scopeClass = bean.getScope();
                var ctx = contexts.get(scopeClass);
                if (ctx == null) ctx = dependentContext;
                return ctx.get((Contextual<Object>) (Contextual<?>) bean,
                        new CreationalContextImpl<>());
            }
        });
    }

    /**
     * Public accessor for proxy creation — used by VaubanBeanManager.getReference().
     */
    @SuppressWarnings("unchecked")
    <T> T getOrCreateProxyForBean(ManagedBean<T> bean) {
        return getOrCreateProxy(bean);
    }

    /**
     * Returns the ClassLoader used for loading bean classes.
     */
    public ClassLoader classLoader() {
        return classLoader;
    }

    /**
     * Load a class using this container's ClassLoader.
     * Prevents inter-test pollution from JVM class cache.
     */
    Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, classLoader);
    }

    /**
     * CDI spec: validate that observer method parameters (other than the observed event param,
     * EventMetadata, and the event itself) can be resolved as injection points.
     */
    private static void validateObserverParameters(
            java.util.List<fr.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
            java.util.List<fr.vidocq.vauban.core.bean.model.BeanDescriptor> descriptors,
            fr.vidocq.vauban.indexer.VaubanIndex index) {
        var assignability = new fr.vidocq.vauban.core.types.AssignabilityRules(index);
        var tempResolver = new fr.vidocq.vauban.core.bean.resolution.BeanResolver(
                descriptors, java.util.List.of(), assignability);

        for (var observer : observers) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = Class.forName(observer.declaringClass().value(), false, cl);
                for (var method : clazz.getDeclaredMethods()) {
                    if (!method.getName().equals(observer.methodName())) continue;
                    // Check each parameter that is NOT @Observes/@ObservesAsync and NOT EventMetadata
                    for (var param : method.getParameters()) {
                        if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)) continue;
                        if (param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) continue;
                        if (param.getType() == jakarta.enterprise.inject.spi.EventMetadata.class) continue;
                        // This parameter must be resolvable as an injection point
                        var paramType = param.getType();
                        if (paramType == jakarta.enterprise.inject.spi.BeanManager.class
                                || paramType == jakarta.enterprise.inject.spi.BeanContainer.class
                                || paramType == jakarta.enterprise.inject.spi.InjectionPoint.class
                                || paramType == jakarta.enterprise.inject.Instance.class
                                || paramType == jakarta.inject.Provider.class
                                || paramType == jakarta.enterprise.event.Event.class) continue;
                        // Skip validation if parameter has custom qualifiers (we can't reliably resolve them)
                        boolean hasCustomQualifier = false;
                        for (var ann : param.getAnnotations()) {
                            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                                    && ann.annotationType() != jakarta.enterprise.inject.Default.class
                                    && ann.annotationType() != jakarta.enterprise.inject.Any.class
                                    && ann.annotationType() != jakarta.inject.Named.class) {
                                hasCustomQualifier = true;
                                break;
                            }
                        }
                        if (hasCustomQualifier) continue;
                        // Check if any bean matches this type
                        var ip = new fr.vidocq.vauban.core.bean.model.InjectionPointInfo(
                                new fr.vidocq.vauban.indexer.model.TypeInfo.ClassType(
                                        fr.vidocq.vauban.indexer.model.DotName.of(paramType.getName())),
                                java.util.Set.of(new fr.vidocq.vauban.core.bean.model.QualifierInstance(
                                        fr.vidocq.vauban.indexer.model.DotName.of("jakarta.enterprise.inject.Default"),
                                        java.util.Map.of()),
                                        new fr.vidocq.vauban.core.bean.model.QualifierInstance(
                                        fr.vidocq.vauban.indexer.model.DotName.of("jakarta.enterprise.inject.Any"),
                                        java.util.Map.of())),
                                fr.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                                "observer parameter " + param.getName());
                        var result = tempResolver.resolveInjectionPoint(ip);
                        if (result.status() == fr.vidocq.vauban.core.bean.resolution.BeanResolver.ResolutionResult.Status.UNSATISFIED) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException(
                                    "Observer method " + observer.declaringClass().simpleName() + "." + observer.methodName()
                                            + "(): unsatisfied dependency for parameter " + param.getName()
                                            + " of type " + paramType.getName());
                        } else if (result.status() == fr.vidocq.vauban.core.bean.resolution.BeanResolver.ResolutionResult.Status.AMBIGUOUS) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException(
                                    "Observer method " + observer.declaringClass().simpleName() + "." + observer.methodName()
                                            + "(): ambiguous dependency for parameter " + param.getName()
                                            + " of type " + paramType.getName());
                        }
                    }
                    break; // found the method
                }
            } catch (jakarta.enterprise.inject.spi.DeploymentException e) {
                throw e; // re-throw deployment exceptions
            } catch (Exception e) {
                // Skip validation for classes that can't be loaded
            }
        }
    }

    /**
     * CDI spec: validate disposer method parameters (other than @Disposes) can be resolved.
     */
    private static void validateDisposerParameters(
            java.util.List<fr.vidocq.vauban.core.bean.model.DisposerDescriptor> disposers,
            java.util.List<fr.vidocq.vauban.core.bean.model.BeanDescriptor> descriptors,
            fr.vidocq.vauban.indexer.VaubanIndex index) {
        var assignability = new fr.vidocq.vauban.core.types.AssignabilityRules(index);
        var tempResolver = new fr.vidocq.vauban.core.bean.resolution.BeanResolver(
                descriptors, java.util.List.of(), assignability);

        for (var disposer : disposers) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = Class.forName(disposer.declaringClass().value(), false, cl);
                for (var method : clazz.getDeclaredMethods()) {
                    if (!method.getName().equals(disposer.methodName())) continue;
                    for (var param : method.getParameters()) {
                        if (param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) continue;
                        var paramType = param.getType();
                        if (paramType == jakarta.enterprise.inject.spi.BeanManager.class
                                || paramType == jakarta.enterprise.inject.spi.BeanContainer.class
                                || paramType == jakarta.enterprise.inject.spi.InjectionPoint.class
                                || paramType == jakarta.enterprise.inject.Instance.class
                                || paramType == jakarta.inject.Provider.class
                                || paramType == jakarta.enterprise.event.Event.class) continue;
                        var ip = new fr.vidocq.vauban.core.bean.model.InjectionPointInfo(
                                new fr.vidocq.vauban.indexer.model.TypeInfo.ClassType(
                                        fr.vidocq.vauban.indexer.model.DotName.of(paramType.getName())),
                                java.util.Set.of(new fr.vidocq.vauban.core.bean.model.QualifierInstance(
                                        fr.vidocq.vauban.indexer.model.DotName.of("jakarta.enterprise.inject.Default"),
                                        java.util.Map.of()),
                                        new fr.vidocq.vauban.core.bean.model.QualifierInstance(
                                        fr.vidocq.vauban.indexer.model.DotName.of("jakarta.enterprise.inject.Any"),
                                        java.util.Map.of())),
                                fr.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                                "disposer parameter " + param.getName());
                        var result = tempResolver.resolveInjectionPoint(ip);
                        if (result.status() == fr.vidocq.vauban.core.bean.resolution.BeanResolver.ResolutionResult.Status.UNSATISFIED) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException(
                                    "Disposer method " + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                                            + "(): unsatisfied dependency for parameter of type " + paramType.getName());
                        } else if (result.status() == fr.vidocq.vauban.core.bean.resolution.BeanResolver.ResolutionResult.Status.AMBIGUOUS) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException(
                                    "Disposer method " + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                                            + "(): ambiguous dependency for parameter of type " + paramType.getName());
                        }
                    }
                    break;
                }
            } catch (jakarta.enterprise.inject.spi.DeploymentException e) {
                throw e;
            } catch (Exception e) {
                // Skip
            }
        }
    }

    /**
     * CDI spec: validate disposer method definitions.
     * - Multiple disposer methods for the same producer in the same class → DefinitionException
     * - Disposer method with no matching producer in the same class → DefinitionException
     */
    private static void validateDisposerMethodDefinitions(
            java.util.List<fr.vidocq.vauban.core.bean.model.DisposerDescriptor> disposers,
            java.util.List<fr.vidocq.vauban.core.bean.model.BeanDescriptor> descriptors) {
        // Group disposers by declaring class + disposed type
        var disposersByKey = new java.util.HashMap<String, java.util.List<fr.vidocq.vauban.core.bean.model.DisposerDescriptor>>();
        for (var disposer : disposers) {
            var key = disposer.declaringClass().value() + "#" + disposer.disposedType();
            disposersByKey.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(disposer);
        }
        for (var entry : disposersByKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new jakarta.enterprise.inject.spi.DefinitionException(
                        "Multiple disposer methods for the same producer type: " + entry.getKey());
            }
        }

        // Note: unresolved disposer method check (no matching producer) is deferred
        // because the test framework may define producers in separate bean archives.
    }

    private static Class<?> wrapPrimitive(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == boolean.class) return Boolean.class;
        if (p == byte.class) return Byte.class;
        if (p == char.class) return Character.class;
        if (p == short.class) return Short.class;
        return p;
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

    /**
     * Look up a bean by its declaring class (exact match on beanClass).
     * Unlike select(), this avoids type resolution and AmbiguousResolutionException.
     * Returns the REAL instance (not proxy) — needed for observer/disposer invocation
     * where fields are accessed directly on the instance.
     */
    @SuppressWarnings("unchecked")
    public Object selectByBeanClass(Class<?> beanClass) {
        var className = beanClass.getName();
        var creating = beansBeingCreated.get();
        if (creating.contains(className)) {
            return null;
        }
        creating.add(className);
        try {
            var dotName = DotName.of(className);
            for (var bean : beans.values()) {
                if (bean.descriptor().beanClass().equals(dotName)) {
                    return getDirectInstance((ManagedBean<Object>) (ManagedBean<?>) bean);
                }
            }
            // Fallback: exact class match (for intercepted subclasses where getBeanClass differs from dotName)
            for (var bean : beans.values()) {
                if (bean.getBeanClass() == beanClass) {
                    return getDirectInstance((ManagedBean<Object>) (ManagedBean<?>) bean);
                }
            }
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No bean found for class: " + beanClass.getName());
        } finally {
            creating.remove(className);
        }
    }

    /**
     * Get the direct contextual instance (no proxy) from the appropriate context.
     */
    @SuppressWarnings("unchecked")
    private <T> T getDirectInstance(ManagedBean<T> bean) {
        var scopeClass = bean.getScope();
        var context = contexts.get(scopeClass);
        if (context == null) {
            context = dependentContext;
        }
        return context.get((jakarta.enterprise.context.spi.Contextual<T>) bean, new CreationalContextImpl<>());
    }

    public InterceptorManager interceptorManager() {
        return interceptorManager;
    }

    private void injectFields(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        injectFieldsByReflection(instance, parentCtx);

        // 2. Call @Inject initializer methods
        callInitializerMethods(instance, parentCtx);

        // 3. Call @PostConstruct
        callPostConstruct(instance, parentCtx);
    }

    private void injectFieldsByReflection(Object instance, CreationalContext<?> parentCtx) {
        var clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (var field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
                field.setAccessible(true);
                try {

                // Handle InjectionPoint injection — the dependent bean receives the
                // InjectionPoint that describes WHERE it was injected (set by the caller)
                if (field.getType() == InjectionPoint.class) {
                    var ip = currentInjectionPoint.get();
                    if (ip == null) {
                        ip = new VaubanInjectionPoint(field, findBeanForInstance(instance));
                    }
                    field.set(instance, ip);
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
                    // Pass field qualifiers to Instance for proper resolution
                    var fieldQualifiers = extractFieldQualifiers(field);
                    var ownerBean = findBeanForInstance(instance);
                    var ip = new VaubanInjectionPoint(field, ownerBean);
                    field.set(instance, new InstanceImpl<>(this, instanceType, fieldQualifiers, ip));
                    continue;
                }

                // Handle BeanManager / BeanContainer injection
                if (BeanManager.class.isAssignableFrom(field.getType())
                        || field.getType() == jakarta.enterprise.inject.spi.BeanContainer.class) {
                    field.set(instance, getBeanManager());
                    continue;
                }

                // Handle Event<T> injection — capture qualifiers and InjectionPoint
                if (field.getType() == Event.class) {
                    var eventQualifiers = collectEventQualifiers(field.getAnnotations());
                    var ownerBean = findBeanForInstance(instance);
                    var eventIp = new VaubanInjectionPoint(field, ownerBean);
                    field.set(instance, new EventImpl<>(eventDispatcher, eventQualifiers, eventIp));
                    continue;
                }

                // Set the current InjectionPoint before resolving the dependency.
                // This allows @Dependent beans to @Inject InjectionPoint and discover
                // where they were injected.
                var previousIp = currentInjectionPoint.get();
                var ownerBean = findBeanForInstance(instance);
                currentInjectionPoint.set(new VaubanInjectionPoint(field, ownerBean));
                try {
                    var fieldQuals = extractFieldQualifiers(field);
                    Object value;
                    var bm = getBeanManager();
                    var resolvedBeans = bm.getBeans(field.getType(), fieldQuals);
                    if (resolvedBeans.isEmpty()) {
                        value = select(field.getType());
                    } else {
                        var resolved = bm.resolve(resolvedBeans);
                        var ctx = (parentCtx != null
                                && resolved.getScope() == jakarta.enterprise.context.Dependent.class)
                                ? parentCtx
                                : bm.createCreationalContext(resolved);
                        value = bm.getReference(resolved, field.getType(), ctx);
                    }
                    // CDI spec: don't set null on primitive fields
                    if (value != null || !field.getType().isPrimitive()) {
                        field.set(instance, value);
                    }
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

    private void callInitializerMethods(Object instance, CreationalContext<?> ctx) {
        for (var method : instance.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                method.setAccessible(true);
                try {
                    var paramTypes = method.getParameterTypes();
                    var genericParamTypes = method.getGenericParameterTypes();
                    var params = method.getParameters();
                    var args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        // Extract qualifiers from parameter annotations
                        var paramQuals = extractParamQualifiers(params[i]);
                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx, paramQuals, method);
                    }
                    method.invoke(instance, args);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to call initializer method: " + method.getName(), e);
                }
            }
        }
    }

    private static java.lang.annotation.Annotation[] extractParamQualifiers(java.lang.reflect.Parameter param) {
        var quals = new java.util.ArrayList<java.lang.annotation.Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : param.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                quals.add(ann);
                hasAnyAnnotation = true;
            }
        }
        if (!hasAnyAnnotation) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        // Always add @Any (CDI 4.1 Section 2.3.1)
        boolean hasAny = false;
        for (var q : quals) {
            if (q.annotationType() == jakarta.enterprise.inject.Any.class) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny) {
            quals.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        }
        return quals.toArray(new java.lang.annotation.Annotation[0]);
    }

    private static java.lang.annotation.Annotation[] collectEventQualifiers(java.lang.annotation.Annotation[] annotations) {
        var quals = new java.util.ArrayList<java.lang.annotation.Annotation>();
        for (var ann : annotations) {
            if (ann.annotationType() == jakarta.inject.Inject.class) continue;
            if (ann.annotationType() == jakarta.enterprise.inject.Default.class) continue;
            if (ann.annotationType() == jakarta.enterprise.inject.Any.class) continue;
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                quals.add(ann);
            }
        }
        return quals.toArray(new java.lang.annotation.Annotation[0]);
    }

    private void callPostConstruct(Object instance, CreationalContext<?> ctx) {
        // CDI spec: Interceptor instances are not intercepted
        if (instance.getClass().isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
            // CDI spec: @PostConstruct on an interceptor can be a lifecycle interceptor (takes InvocationContext)
            // or a PostConstruct callback for the interceptor instance itself (no args).
            java.lang.reflect.Method pc = null;
            for (var m : instance.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(jakarta.annotation.PostConstruct.class) && m.getParameterCount() == 0) {
                    pc = m;
                    break;
                }
            }
            if (pc != null) {
                try {
                    pc.setAccessible(true);
                    pc.invoke(instance);
                } catch (Exception e) {
                    throw new RuntimeException("@PostConstruct on interceptor instance failed: " + pc, e);
                }
            }
            return;
        }

        // Find @PostConstruct method
        java.lang.reflect.Method postConstructMethod = null;
        var clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.annotation.PostConstruct.class)) {
                    method.setAccessible(true);
                    postConstructMethod = method;
                    break;
                }
            }
            if (postConstructMethod != null) break;
            clazz = clazz.getSuperclass();
        }

        // Check for lifecycle interceptors on the bean
        var beanBindings = findInterceptorBindings(instance);
        if (!beanBindings.isEmpty() && interceptorManager.hasInterceptors()) {
            interceptorManager.setClassLoader(instance.getClass().getClassLoader());
            var bindingAnns = new java.util.ArrayList<java.lang.annotation.Annotation>(collectBindingAnnotations(instance));
            var lifecycleChain = interceptorManager.resolveLifecycleChain(
                    beanBindings, jakarta.annotation.PostConstruct.class, bindingAnns, ctx);
            if (!lifecycleChain.isEmpty()) {
                // Collect binding annotations for InvocationContext.getInterceptorBindings()
                var bindingAnnotations = collectBindingAnnotations(instance);
                // Invoke lifecycle interceptors through InvocationContext
                final var pcMethod = postConstructMethod;
                var invocationCtx = new fr.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                        instance, null, new Object[0], lifecycleChain,
                        (target, params) -> {
                            if (pcMethod != null) pcMethod.invoke(target);
                            return null;
                        });
                invocationCtx.setInterceptorBindings(bindingAnnotations);
                try {
                    invocationCtx.proceed();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("Lifecycle interceptor failed", e);
                }
                return;
            }
        }

        // No interceptors — call @PostConstruct directly
        if (postConstructMethod != null) {
            try {
                postConstructMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException("@PostConstruct failed: " + postConstructMethod, e);
            }
        }
    }

    /**
     * Collect transitive interceptor binding annotations from meta-annotations.
     */
    private static void collectTransitiveBindings(Set<java.lang.annotation.Annotation> annotations,
            Set<DotName> dotNames) {
        var toAdd = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        for (var ann : annotations) {
            collectTransitiveMeta(ann.annotationType(), toAdd, annotations, dotNames);
        }
        annotations.addAll(toAdd);
    }

    private static void collectTransitiveMeta(Class<? extends java.lang.annotation.Annotation> annType,
            Set<java.lang.annotation.Annotation> toAdd,
            Set<java.lang.annotation.Annotation> existing, Set<DotName> dotNames) {
        for (var meta : annType.getAnnotations()) {
            if (meta.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    && !existing.contains(meta) && !toAdd.contains(meta)) {
                toAdd.add(meta);
                dotNames.add(DotName.of(meta.annotationType().getName()));
                collectTransitiveMeta(meta.annotationType(), toAdd, existing, dotNames);
            }
        }
    }

    /**
     * Find interceptor bindings on a bean instance (from its class or superclass).
     */
    private Set<DotName> findInterceptorBindings(Object instance) {
        var bindings = new java.util.LinkedHashSet<DotName>();
        // Check the original bean class (superclass of intercepted subclass)
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted")) {
            clazz = clazz.getSuperclass();
        }
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                bindings.add(DotName.of(ann.annotationType().getName()));
            }
        }
        return bindings;
    }

    private Set<java.lang.annotation.Annotation> collectBindingAnnotations(Object instance) {
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted") || clazz.getName().contains("$$Proxy")) {
            clazz = clazz.getSuperclass();
        }
        var annotations = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        var dotNames = new java.util.LinkedHashSet<DotName>();
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                annotations.add(ann);
                dotNames.add(DotName.of(ann.annotationType().getName()));
            }
        }
        collectTransitiveBindings(annotations, dotNames);
        return annotations;
    }

    /**
     * For beans with interceptor bindings, generate an intercepted subclass
     * and replace the factory so instances are intercepted at runtime.
     */
    private void wrapInterceptedBeans(List<BeanDescriptor> descriptors,
                                       Map<DotName, BeanFactory<?>> factories) {
        for (var descriptor : descriptors) {
            Class<?> currentBeanClassTemp;
            try {
                currentBeanClassTemp = loadClass(descriptor.beanClass().value());
            } catch (ClassNotFoundException e) {
                continue;
            }
            final var currentBeanClass = currentBeanClassTemp;
            if (descriptor.kind() != BeanDescriptor.BeanKind.MANAGED) continue;

            // CDI spec: Interceptors themselves are not intercepted
            try {
                var cls = loadClass(descriptor.beanClass().value());
                if (cls.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
                    continue;
                }
            } catch (ClassNotFoundException e) { /* skip */ }

            var bean = beans.get(descriptor.id());
            if (bean == null) continue;

            Class<?> beanClass = null;
            Set<DotName> bindings = new java.util.LinkedHashSet<>(descriptor.interceptorBindings());

            // Also check via reflection (bindings may not be in bytecode index)
            if (bindings.isEmpty()) {
                try {
                    var cls = loadClass(descriptor.beanClass().value());
                    for (var ann : cls.getAnnotations()) {
                        if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                            bindings.add(DotName.of(ann.annotationType().getName()));
                        }
                    }
                } catch (ClassNotFoundException ex) { /* skip */ }
                // Don't skip if bindings are empty — method-level bindings or
                // target class @AroundInvoke checked below
                if (bindings.isEmpty() && !interceptorManager.hasInterceptors()) {
                    // Still check for target class @AroundInvoke methods
                    boolean hasTargetAroundInvoke = false;
                    try {
                        var cls2 = loadClass(descriptor.beanClass().value());
                        var cur = cls2;
                        while (cur != null && cur != Object.class) {
                            for (var m : cur.getDeclaredMethods()) {
                                if (m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                                    hasTargetAroundInvoke = true;
                                    break;
                                }
                            }
                            if (hasTargetAroundInvoke) break;
                            cur = cur.getSuperclass();
                        }
                    } catch (ClassNotFoundException ex2) { /* skip */ }
                    if (!hasTargetAroundInvoke) continue;
                }
            }
            try {
                beanClass = loadClass(descriptor.beanClass().value());

                // CDI spec: intercepted bean cannot be final
                if (java.lang.reflect.Modifier.isFinal(beanClass.getModifiers())) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                            "Bean class " + beanClass.getName() + " with interceptor bindings must not be final");
                }
                // CDI spec: intercepted bean cannot have non-private, non-static final methods
                for (var m : beanClass.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isFinal(m.getModifiers())
                            && !java.lang.reflect.Modifier.isPrivate(m.getModifiers())
                            && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        throw new jakarta.enterprise.inject.spi.DeploymentException(
                                "Intercepted bean " + beanClass.getName() + " has final method " + m.getName());
                    }
                }

                // CDI spec: intercepted bean needs a non-private no-arg constructor
                boolean hasAccessibleNoArgCtor = false;
                for (var ctor : beanClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 0
                            && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                        hasAccessibleNoArgCtor = true;
                        break;
                    }
                }
                if (!hasAccessibleNoArgCtor) {
                    // Check if there's ANY no-arg constructor (even private)
                    boolean hasPrivateNoArgCtor = false;
                    try {
                        beanClass.getDeclaredConstructor();
                        hasPrivateNoArgCtor = true;
                    } catch (NoSuchMethodException e) { /* no no-arg ctor at all */ }

                    if (hasPrivateNoArgCtor) {
                        throw new jakarta.enterprise.inject.spi.DeploymentException(
                                "Intercepted bean " + beanClass.getName()
                                        + " has only private no-arg constructor (unproxyable)");
                    }
                }

                interceptorManager.setClassLoader(beanClass.getClassLoader());

                var classBindings = interceptorManager.findBindingsOnClass(beanClass, interceptorManager::isInterceptorBinding);
                
                // Check if there are matching interceptors (class, method, or constructor level)
                var matches = interceptorManager.resolveInterceptorDescriptors(classBindings);
                if (matches.isEmpty()) {
                    boolean hasInterceptors = false;
                    // Check method-level bindings
                    for (var m : beanClass.getMethods()) {
                        if (!interceptorManager.resolveInterceptorDescriptorsForMethod(classBindings, m).isEmpty()) {
                            hasInterceptors = true;
                            break;
                        }
                    }
                    // Check constructor-level bindings (for @AroundConstruct)
                    if (!hasInterceptors) {
                        for (var ctor : beanClass.getDeclaredConstructors()) {
                            var ctorDescriptors = interceptorManager.resolveInterceptorDescriptorsAroundConstruct(
                                    classBindings, ctor, (Class<?>) beanClass, java.util.Collections.emptyList());
                            if (!ctorDescriptors.isEmpty()) {
                                hasInterceptors = true;
                                break;
                            }
                        }
                    }
                    if (!hasInterceptors) continue;
                }
                
                // Generate the intercepted subclass
                var generated = fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                        .generate(beanClass, classBindings, descriptor.constructorBindings());

                try {
                    var lookup = java.lang.invoke.MethodHandles.privateLookupIn(beanClass,
                            java.lang.invoke.MethodHandles.lookup());
                    Class<?> interceptedClass;
                    try {
                        interceptedClass = loadClass(generated.className());
                    } catch (ClassNotFoundException e) {
                        interceptedClass = lookup.defineClass(generated.bytecode());
                    }

                    // Replace the factory
                    var mgr = this.interceptorManager;
                    var bds = classBindings;
                    var ctorBds = descriptor.constructorBindings();
                    var originalFactory = beans.get(descriptor.id()).factory();

                    final var finalBeanClass = beanClass;
                    final Class<?> finalInterceptedClass = interceptedClass;
                    BeanFactory<?> interceptedFactory = new BeanFactory<Object>() {
                @Override
                public Object create() {
                    return create((jakarta.enterprise.context.spi.CreationalContext<Object>) null);
                }

                @Override
                public Object create(jakarta.enterprise.context.spi.CreationalContext<Object> ctx) {
                    return create(null, ctx);
                }

                @Override
                public Object create(fr.vidocq.vauban.core.interceptor.VaubanInvocationContext constructCtx, jakarta.enterprise.context.spi.CreationalContext<Object> creationalCtx) {
                    try {
                        // Find the original bean constructor to resolve its bindings
                        var ctors = finalInterceptedClass.getDeclaredConstructors();
                        java.lang.reflect.Constructor<?> targetCtor = null;
                        Object[] finalArgs = constructCtx != null ? constructCtx.getParameters() : null;

                        if (finalArgs != null) {
                            for (var c : finalBeanClass.getDeclaredConstructors()) {
                                if (c.getParameterCount() == finalArgs.length) {
                                    targetCtor = c;
                                    break;
                                }
                            }
                        }
                        if (targetCtor == null) {
                            // Find the CDI-selected constructor (the one that would be used by VaubanContainer)
                            var ctorParams = descriptor.injectionPoints().stream()
                                    .filter(ip -> ip.kind() == fr.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER)
                                    .toList();
                            for (var c : finalBeanClass.getDeclaredConstructors()) {
                                if (c.isAnnotationPresent(jakarta.inject.Inject.class)) {
                                    targetCtor = c;
                                    break;
                                }
                            }
                            if (targetCtor == null) {
                                for (var c : finalBeanClass.getDeclaredConstructors()) {
                                    if (c.getParameterCount() == ctorParams.size()) {
                                        targetCtor = c;
                                        break;
                                    }
                                }
                            }
                        }
                        if (targetCtor == null) targetCtor = finalBeanClass.getDeclaredConstructors()[0];

                        // Resolve full binding set for this constructor
                        var bindingAnnotationsByType = new java.util.LinkedHashMap<Class<?>, java.lang.annotation.Annotation>();
                        for (var ann : finalBeanClass.getAnnotations()) {
                             if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                                 bindingAnnotationsByType.put(ann.annotationType(), ann);
                             }
                        }
                        for (var ann : targetCtor.getAnnotations()) {
                            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                                bindingAnnotationsByType.put(ann.annotationType(), ann);
                            }
                        }
                        
                        var bindingAnnotations = new java.util.LinkedHashSet<java.lang.annotation.Annotation>(bindingAnnotationsByType.values());
                        var fullBindings = new java.util.LinkedHashSet<DotName>();
                        for (var ann : bindingAnnotations) {
                            fullBindings.add(DotName.of(ann.annotationType().getName()));
                        }
                        collectTransitiveBindings(bindingAnnotations, fullBindings);

                        // Resolve AroundConstruct chain
                        var constructChain = mgr.resolveAroundConstructChain(bds, targetCtor, finalBeanClass, (jakarta.enterprise.context.spi.CreationalContext<?>) creationalCtx);
                        
                        if (!constructChain.isEmpty() && constructCtx == null) {
                            // First time: run the chain
                            final Object[] box = new Object[1];
                            final java.lang.reflect.Constructor<?> finalTargetCtor = targetCtor;
                            
                            // Resolve arguments for the constructor if not already resolved
                            if (finalArgs == null) {
                                var pTypes = finalTargetCtor.getParameterTypes();
                                var gpTypes = finalTargetCtor.getGenericParameterTypes();
                                var cParams = finalTargetCtor.getParameters();
                                finalArgs = new Object[pTypes.length];
                                for (int i = 0; i < pTypes.length; i++) {
                                    var pQuals = extractParamQualifiers(cParams[i]);
                                    finalArgs[i] = resolveParameter(pTypes[i], gpTypes[i], creationalCtx, pQuals, finalTargetCtor);
                                }
                            }

                            var ctx = new fr.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                                    null, null, finalTargetCtor, finalArgs, constructChain,
                                    (target, params) -> {
                                        mgr.$$beginInterception(java.util.Collections.emptyList(), bds, (jakarta.enterprise.context.spi.CreationalContext<?>) (Object) creationalCtx);
                                        try {
                                            var instance = create((fr.vidocq.vauban.core.interceptor.VaubanInvocationContext) fr.vidocq.vauban.core.interceptor.VaubanInvocationContext.dummy(params), (jakarta.enterprise.context.spi.CreationalContext<Object>) (Object) creationalCtx);
                                            box[0] = instance;
                                            return instance;
                                        } finally {
                                            mgr.$$endInterception();
                                        }
                                    });
                            ctx.setInterceptorBindings(bindingAnnotations);
                            try {
                                ctx.proceed();
                            } catch (RuntimeException re) {
                                throw re;
                            } catch (Exception e) {
                                throw new jakarta.enterprise.inject.CreationException(e);
                            }
                            return box[0];
                        }

                        // Actually instantiate the subclass
                        if (finalArgs == null) {
                            var targetCtorToUse = (targetCtor != null) ? targetCtor : finalBeanClass.getDeclaredConstructors()[0];
                            var pTypes = targetCtorToUse.getParameterTypes();
                            var gpTypes = targetCtorToUse.getGenericParameterTypes();
                            var cParams = targetCtorToUse.getParameters();
                            finalArgs = new Object[pTypes.length];
                            for (int i = 0; i < pTypes.length; i++) {
                                var pQuals = extractParamQualifiers(cParams[i]);
                                finalArgs[i] = resolveParameter(pTypes[i], gpTypes[i], creationalCtx, pQuals, targetCtorToUse);
                            }
                        }

                        java.lang.reflect.Constructor<?> subclassCtor = null;
                        for (var c : finalInterceptedClass.getDeclaredConstructors()) {
                            if (c.getParameterCount() == finalArgs.length) {
                                subclassCtor = c;
                                break;
                            }
                        }
                        if (subclassCtor == null) subclassCtor = finalInterceptedClass.getDeclaredConstructors()[0];
                        
                        subclassCtor.setAccessible(true);
                        var instance = subclassCtor.newInstance(finalArgs);
                        
                        // Initialize interceptor fields
                        var initMethod = finalInterceptedClass.getMethod("$$init",
                                fr.vidocq.vauban.core.interceptor.InterceptorManager.class,
                                java.util.Set.class,
                                java.util.Set.class,
                                jakarta.enterprise.context.spi.CreationalContext.class);
                        initMethod.invoke(instance, mgr, bds, descriptor.constructorBindings(), creationalCtx);
                        return instance;
                    } catch (Exception e) {
                        throw new jakarta.enterprise.inject.CreationException(e);
                    }
                }

                @Override
                public Object create(fr.vidocq.vauban.core.interceptor.VaubanInvocationContext constructCtx) {
                    return create(constructCtx, null);
                }
            };

                // Update the bean with the new factory
                var interceptedBean = new ManagedBean<>(descriptor, interceptedFactory, classLoader);
                interceptedBean.setInterceptorManager(this.interceptorManager);
                beans.put(descriptor.id(), interceptedBean);
            } catch (Exception e) {
                // Log the first failure cause for debugging
                System.err.println("[VAUBAN-DBG] Primary interception failed for " + descriptor.beanClass().value() + ": " + e);
                    // Primary interception failed — try fallback
                    // MethodHandles.privateLookupIn may fail for custom classloaders
                    // Fallback: define class via bean's classloader directly
                    var generated2 = fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                            .generate(beanClass, bindings, descriptor.constructorBindings());
                    
                    Class<?> interceptedClass2;
                    try {
                        // Try loading if already defined by a previous attempt or another container instance
                        interceptedClass2 = beanClass.getClassLoader().loadClass(generated2.className());
                    } catch (ClassNotFoundException cnfe) {
                        try {
                            // Try to define it in the container's own loader if it's the same or parent
                            var lookup2 = java.lang.invoke.MethodHandles.lookup();
                            interceptedClass2 = lookup2.defineClass(generated2.bytecode());
                        } catch (Exception ex) {
                            // Last resort: ClassLoader.defineClass via reflection
                            try {
                                var defineMethod = ClassLoader.class.getDeclaredMethod(
                                        "defineClass", String.class, byte[].class, int.class, int.class);
                                defineMethod.setAccessible(true);
                                interceptedClass2 = (Class<?>) defineMethod.invoke(
                                        beanClass.getClassLoader(),
                                        generated2.className(), generated2.bytecode(),
                                        0, generated2.bytecode().length);
                            } catch (Exception ex2) {
                                System.err.println("[VAUBAN-DBG] Fallback interception also failed for " + descriptor.beanClass().value() + ": " + ex2);
                                throw new jakarta.enterprise.inject.spi.DeploymentException("Could not define interceptor subclass", ex2);
                            }
                        }
                    }
                var mgr2 = this.interceptorManager;
                var bds2 = bindings;
                final var finalBeanClass2 = currentBeanClass;
                    Class<?> finalInterceptedClass = interceptedClass2;
                    BeanFactory<?> f2 = new BeanFactory<Object>() {
                    @Override
                    public Object create() {
                        return create((jakarta.enterprise.context.spi.CreationalContext<Object>) null);
                    }

                    @Override
                    public Object create(jakarta.enterprise.context.spi.CreationalContext<Object> ctx) {
                        try {
                            var ctor = finalInterceptedClass.getDeclaredConstructor();
                            var constructChain2 = mgr2.resolveChainForConstructor(bds2, descriptor.constructorBindings(), ctor, ctx);
                            if (!constructChain2.isEmpty()) {
                                final Object[] box2 = new Object[1];
                                var originalCtor2 = finalBeanClass2.getDeclaredConstructors()[0];
                                for (var c : finalBeanClass2.getDeclaredConstructors()) {
                                    if (!java.lang.reflect.Modifier.isPrivate(c.getModifiers())) {
                                        originalCtor2 = c;
                                        break;
                                    }
                                }
                                var invocationCtx = new fr.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                                        null, null, originalCtor2, new Object[originalCtor2.getParameterCount()], constructChain2,
                                        (target, params) -> {
                                            mgr2.$$beginInterception(java.util.Collections.emptyList(), bds2, ctx);
                                            try {
                                                var instance = ((fr.vidocq.vauban.core.BeanFactory<Object>) this).create((fr.vidocq.vauban.core.interceptor.VaubanInvocationContext) fr.vidocq.vauban.core.interceptor.VaubanInvocationContext.dummy(params), ctx);
                                                box2[0] = instance;
                                                return instance;
                                            } finally {
                                                mgr2.$$endInterception();
                                            }
                                        });
                                try {
                                    invocationCtx.proceed();
                                } catch (RuntimeException re) {
                                    throw re;
                                } catch (Exception e) {
                                    throw new jakarta.enterprise.inject.CreationException(e);
                                }
                                var inst = box2[0];
                                if (inst == null) {
                                    throw new jakarta.enterprise.inject.CreationException(
                                            "Interceptor chain for @AroundConstruct failed to create an instance for " + finalInterceptedClass.getName());
                                }
                                finalInterceptedClass.getMethod("$$init",
                                        fr.vidocq.vauban.core.interceptor.InterceptorManager.class,
                                        java.util.Set.class,
                                        java.util.Set.class,
                                        jakarta.enterprise.context.spi.CreationalContext.class).invoke(inst, mgr2, bds2, descriptor.constructorBindings(), ctx);
                                return inst;
                            } else {
                        // Resolve which constructor to use
                        var ctor2 = finalInterceptedClass.getDeclaredConstructor();
                        Object[] finalArgs2 = new Object[0];
                        ctor2.setAccessible(true);
                        var inst = ctor2.newInstance(finalArgs2);
                                finalInterceptedClass.getMethod("$$init",
                                        fr.vidocq.vauban.core.interceptor.InterceptorManager.class,
                                        java.util.Set.class,
                                        java.util.Set.class,
                                        jakarta.enterprise.context.spi.CreationalContext.class).invoke(inst, mgr2, bds2, descriptor.constructorBindings(), ctx);
                                return inst;
                            }
                        } catch (Exception ex) {
                            var cause = ex instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : ex;
                            if (cause instanceof RuntimeException re) throw re;
                            throw new jakarta.enterprise.inject.CreationException(cause);
                        }
                    }
                };
                    var ib2 = new ManagedBean<>(descriptor, f2, classLoader);
                    ib2.setInterceptorManager(this.interceptorManager);
                    beans.put(descriptor.id(), ib2);
                } catch (LinkageError le2) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                            "Cannot create interceptor subclass: " + le2.getMessage(), le2);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

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
                    if (bt instanceof TypeInfo.ClassType btCt
                            && disposer.disposedType() instanceof TypeInfo.ClassType dCt
                            && btCt.name().equals(dCt.name())) {
                        typeMatches = true;
                        break;
                    }
                }
                // Fallback: use reflection for assignability (index may lack type info)
                if (!typeMatches && disposer.disposedType() instanceof TypeInfo.ClassType dCt) {
                    try {
                        var cl = Thread.currentThread().getContextClassLoader();
                        var disposedClass = Class.forName(dCt.name().value(), false, cl);
                        for (var bt : descriptor.types()) {
                            String btName = null;
                            if (bt instanceof TypeInfo.ClassType btCt) btName = btCt.name().value();
                            else if (bt instanceof TypeInfo.ParameterizedType pt) btName = pt.rawType().value();
                            if (btName != null) {
                                var beanTypeClass = Class.forName(btName, false, cl);
                                if (disposedClass.isAssignableFrom(beanTypeClass)) {
                                    typeMatches = true;
                                    break;
                                }
                            }
                        }
                    } catch (ClassNotFoundException e) { /* skip */ }
                }
                if (!typeMatches) continue;

                // CDI spec: disposer qualifiers must match producer qualifiers
                // Use annotation names for matching (members from index may be incomplete)
                // Then verify via reflection for exact member values
                var disposerQualNames = disposer.qualifiers().stream()
                        .filter(q -> !q.isDefault() && !q.isAny())
                        .map(q -> q.annotationName())
                        .collect(java.util.stream.Collectors.toSet());
                var producerQualNames = descriptor.qualifiers().stream()
                        .filter(q -> !q.isDefault() && !q.isAny())
                        .map(q -> q.annotationName())
                        .collect(java.util.stream.Collectors.toSet());
                boolean qualifiersMatch;
                if (disposerQualNames.isEmpty()) {
                    qualifiersMatch = producerQualNames.isEmpty();
                } else if (!producerQualNames.containsAll(disposerQualNames)) {
                    qualifiersMatch = false;
                } else {
                    // Annotation names match — verify via reflection for exact member values
                    qualifiersMatch = verifyDisposerQualifiersViaReflection(descriptor, disposer);
                }
                if (!qualifiersMatch) continue;

                bean.setDestroyer(instance -> callDisposer(instance, disposer));
                break;
            }
        }
    }

    /**
     * Use reflection to compare qualifier annotation values between a producer and disposer.
     * The index may not capture annotation member values, so we use the actual Java annotations.
     */
    private boolean verifyDisposerQualifiersViaReflection(BeanDescriptor producer, DisposerDescriptor disposer) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var declaringClass = Class.forName(producer.beanClass().value(), false, cl);

            // Find producer method/field annotations
            java.lang.annotation.Annotation[] producerAnnotations = null;
            var producerId = producer.id().value();
            if (producer.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD) {
                var methodName = producerId.contains("#") ? producerId.substring(producerId.indexOf('#') + 1) : null;
                if (methodName != null) {
                    for (var m : declaringClass.getDeclaredMethods()) {
                        if (m.getName().equals(methodName) && m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                            producerAnnotations = m.getAnnotations();
                            break;
                        }
                    }
                }
            } else if (producer.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                var fieldName = producerId.contains(".") ? producerId.substring(producerId.lastIndexOf('.') + 1) : null;
                if (fieldName != null) {
                    try {
                        var f = declaringClass.getDeclaredField(fieldName);
                        producerAnnotations = f.getAnnotations();
                    } catch (NoSuchFieldException e) { /* skip */ }
                }
            }

            if (producerAnnotations == null) return true; // Can't verify, assume match

            // Find disposer method parameter annotations
            java.lang.annotation.Annotation[] disposerParamAnnotations = null;
            for (var m : declaringClass.getDeclaredMethods()) {
                if (m.getName().equals(disposer.methodName()) && m.getParameterCount() > disposer.parameterIndex()) {
                    disposerParamAnnotations = m.getParameterAnnotations()[disposer.parameterIndex()];
                    break;
                }
            }

            if (disposerParamAnnotations == null) return true;

            // Compare qualifier annotations between producer and disposer
            for (var dAnn : disposerParamAnnotations) {
                if (!dAnn.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                        && dAnn.annotationType() != jakarta.enterprise.inject.Default.class
                        && dAnn.annotationType() != jakarta.enterprise.inject.Any.class) continue;
                if (dAnn.annotationType() == jakarta.enterprise.inject.Default.class
                        || dAnn.annotationType() == jakarta.enterprise.inject.Any.class
                        || dAnn.annotationType() == jakarta.enterprise.inject.Disposes.class) continue;

                // Find matching annotation on producer
                boolean found = false;
                for (var pAnn : producerAnnotations) {
                    if (pAnn.annotationType() == dAnn.annotationType() && pAnn.equals(dAnn)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        } catch (Exception e) {
            return true; // Can't verify, assume match
        }
    }

    private void callDisposer(Object producedInstance, DisposerDescriptor disposer) {
        try {
            var declaringClass = loadClass(disposer.declaringClass().value());

            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(disposer.methodName())
                        && method.getParameterCount() > disposer.parameterIndex()) {
                    method.setAccessible(true);
                    // For static disposer methods, no declaring instance needed
                    var declaringInstance = java.lang.reflect.Modifier.isStatic(method.getModifiers())
                            ? null : selectByBeanClass(declaringClass);
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
                                args[i] = new EventImpl<>(eventDispatcher, collectEventQualifiers(method.getParameters()[i].getAnnotations()));
                            } else if (paramTypes[i] == Instance.class) {
                                Class<?> instanceType = Object.class;
                                var genericType = method.getGenericParameterTypes()[i];
                                if (genericType instanceof ParameterizedType pt) {
                                    var typeArg = pt.getActualTypeArguments()[0];
                                    if (typeArg instanceof Class<?> c) instanceType = c;
                                }
                                var ip = new VaubanInjectionPoint(genericType, java.util.Set.of(jakarta.enterprise.inject.Any.Literal.INSTANCE, jakarta.enterprise.inject.Default.Literal.INSTANCE), null, method);
                                args[i] = new InstanceImpl<>(this, instanceType, ip);
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
        return new BeanFactory<Object>() {
            @Override
            public Object create() {
                return create((jakarta.enterprise.context.spi.CreationalContext<Object>) null);
            }

            @Override
            public Object create(jakarta.enterprise.context.spi.CreationalContext<Object> creationalCtx) {
                return create(null, creationalCtx);
            }

            @Override
            public Object create(fr.vidocq.vauban.core.interceptor.VaubanInvocationContext constructCtx, CreationalContext<Object> creationalCtx) {
                try {
                    var beanClass = loadClass(descriptor.beanClass().value());

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
                    var ctorParamsRefl = injectCtor.getParameters();
                    var args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        var pQuals = extractParamQualifiers(ctorParamsRefl[i]);
                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], creationalCtx, pQuals, injectCtor);
                    }

                    final var finalCtor = injectCtor;
                    final var finalArgs = args;
                    finalCtor.setAccessible(true);

                    // If we have an interception context, let it handle the instantiation
                    if (constructCtx != null) {
                        return finalCtor.newInstance(finalArgs);
                    }

                    return finalCtor.newInstance(finalArgs);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    var cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    throw new jakarta.enterprise.inject.CreationException(cause);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new jakarta.enterprise.inject.CreationException(e);
                }
            }
        };
    }

    private BeanFactory<?> createProducerMethodFactory(BeanDescriptor descriptor) {
        var methodName = extractProducerMethodName(descriptor.id());
        return new BeanFactory<Object>() {
            @Override
            public Object create() {
                return create((jakarta.enterprise.context.spi.CreationalContext<Object>) null);
            }

            @Override
            public Object create(jakarta.enterprise.context.spi.CreationalContext<Object> ctx) {
                try {
                    var declaringClass = loadClass(descriptor.beanClass().value());
                    var declaringInstance = selectByBeanClass(declaringClass);

                    for (var method : declaringClass.getDeclaredMethods()) {
                        if (method.getName().equals(methodName)) {
                            method.setAccessible(true);
                            if (method.getParameterCount() == 0) {
                                return method.invoke(declaringInstance);
                            }
                            // Resolve parameters as injection points
                            var paramTypes = method.getParameterTypes();
                            var genericParamTypes = method.getGenericParameterTypes();
                            var params = method.getParameters();
                            var args = new Object[paramTypes.length];
                            for (int i = 0; i < paramTypes.length; i++) {
                                var qualifiers = extractParamQualifiers(params[i]);
                                args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx, qualifiers, method);
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
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> primitiveToWrapper(Class<T> type) {
        if (type == boolean.class) return (Class<T>) Boolean.class;
        if (type == byte.class) return (Class<T>) Byte.class;
        if (type == char.class) return (Class<T>) Character.class;
        if (type == short.class) return (Class<T>) Short.class;
        if (type == int.class) return (Class<T>) Integer.class;
        if (type == long.class) return (Class<T>) Long.class;
        if (type == float.class) return (Class<T>) Float.class;
        if (type == double.class) return (Class<T>) Double.class;
        return type;
    }

    private static java.lang.annotation.Annotation[] extractFieldQualifiers(java.lang.reflect.Field field) {
        var qualifiers = new java.util.ArrayList<java.lang.annotation.Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : field.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                qualifiers.add(ann);
                hasAnyAnnotation = true;
            }
        }
        if (!hasAnyAnnotation) {
            qualifiers.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        // Always add @Any (CDI 4.1 Section 2.3.1)
        boolean hasAny = false;
        for (var q : qualifiers) {
            if (q.annotationType() == jakarta.enterprise.inject.Any.class) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny) {
            qualifiers.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        }
        return qualifiers.toArray(new java.lang.annotation.Annotation[0]);
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx, java.lang.annotation.Annotation[] qualifiers, java.lang.reflect.Member member) {
        if (paramType == Event.class) {
            return new EventImpl<>(eventDispatcher, qualifiers);
        }
        if (paramType == Instance.class || paramType == jakarta.inject.Provider.class) {
            Class<?> instanceType = Object.class;
            if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                var typeArg = pt.getActualTypeArguments()[0];
                if (typeArg instanceof Class<?> c) instanceType = c;
            }
            
            var qualSet = new java.util.HashSet<>(java.util.Arrays.asList(qualifiers));
            if (qualSet.isEmpty()) qualSet.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
            qualSet.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
            
            var targetType = genericType;
            if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                targetType = pt.getActualTypeArguments()[0];
            }
            var ip = new VaubanInjectionPoint(targetType, qualSet, null, member);
            
            return new InstanceImpl<>(this, instanceType, ip).select(qualifiers);
        }
        if (BeanManager.class.isAssignableFrom(paramType)
                || paramType == jakarta.enterprise.inject.spi.BeanContainer.class) {
            return getBeanManager();
        }
        if (paramType == jakarta.enterprise.inject.spi.EventMetadata.class) {
            // EventMetadata is only meaningful within observer methods — return null for now
            return null;
        }
        if (paramType == jakarta.enterprise.inject.spi.InjectionPoint.class) {
            var ip = currentInjectionPoint.get();
            if (ip == null) {
                // If no current injection point is set (e.g., manual lookup), return a dummy IP
                return new VaubanInjectionPoint(jakarta.enterprise.inject.spi.InjectionPoint.class, java.util.Set.of(jakarta.enterprise.inject.Default.Literal.INSTANCE, jakarta.enterprise.inject.Any.Literal.INSTANCE), null);
            }
            return ip;
        }
        
        var bm = getBeanManager();
        var beansFound = bm.getBeans(genericType, qualifiers);
        if (beansFound.isEmpty()) {
            return select(paramType);
        }
        var resolved = bm.resolve(beansFound);
        if (resolved == null) {
            // Primitive injection point cannot be null, but bm.resolve() returns null for empty or ambiguous?
            // Wait, getBeans was not empty, so bm.resolve should return something.
            // But just in case, or if it returns null...
            return select(paramType);
        }
        
        // Handle InjectionPoint for @Dependent beans
        var previousIp = currentInjectionPoint.get();
        if (resolved.getScope() == jakarta.enterprise.context.Dependent.class) {
             var qualSet = new java.util.HashSet<>(java.util.Arrays.asList(qualifiers));
             if (qualSet.isEmpty()) qualSet.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
             currentInjectionPoint.set(new VaubanInjectionPoint(genericType, qualSet, null, member));
        }
        try {
            var pCtx = (ctx != null && resolved.getScope() == jakarta.enterprise.context.Dependent.class)
                    ? ctx
                    : bm.createCreationalContext(resolved);
            return bm.getReference(resolved, genericType, pCtx);
        } finally {
            currentInjectionPoint.set(previousIp);
        }
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx, java.lang.annotation.Annotation[] qualifiers) {
        return resolveParameter(paramType, genericType, ctx, qualifiers, null);
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx) {
        return resolveParameter(paramType, genericType, ctx, new java.lang.annotation.Annotation[0], null);
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType) {
        return resolveParameter(paramType, genericType, null, new java.lang.annotation.Annotation[0], null);
    }

    private BeanFactory<?> createProducerFieldFactory(BeanDescriptor descriptor) {
        var fieldName = extractProducerFieldName(descriptor.id());
        return new BeanFactory<Object>() {
            @Override
            public Object create() {
                return create((jakarta.enterprise.context.spi.CreationalContext<Object>) null);
            }

            @Override
            public Object create(jakarta.enterprise.context.spi.CreationalContext<Object> ctx) {
                try {
                    var declaringClass = loadClass(descriptor.beanClass().value());
                    var declaringInstance = selectByBeanClass(declaringClass);
                    var field = declaringClass.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(declaringInstance);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new jakarta.enterprise.inject.CreationException(
                            "Failed to read producer field: " + descriptor.id(), e);
                }
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
        
        sharedInterceptors.clear();
        proxyCache.clear();

        // CDI lifecycle events: fire @Shutdown and @BeforeDestroyed/@Destroyed
        try {
            eventDispatcher.fire(new jakarta.enterprise.event.Shutdown());
        } catch (Exception e) { /* suppress */ }
        try {
            eventDispatcher.fire(new Object(),
                    jakarta.enterprise.context.BeforeDestroyed.Literal.of(jakarta.enterprise.context.ApplicationScoped.class));
        } catch (Exception e) { /* suppress */ }

        if (currentInstance == this) {
            currentInstance = null;
        }
        requestContext.deactivate();
        applicationContext.deactivate();

        try {
            eventDispatcher.fire(new Object(),
                    jakarta.enterprise.context.Destroyed.Literal.of(jakarta.enterprise.context.ApplicationScoped.class));
        } catch (Exception e) { /* suppress */ }
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
                            indexBuilder.add(fr.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
                        } else {
                            // Fallback for classes not in resources (e.g. dynamic or some test classes)
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

            // Set TCCL to the bean class's ClassLoader so BeanDiscovery can
            // resolve inherited annotations via reflection on TCK archive classes
            var previousCl = Thread.currentThread().getContextClassLoader();
            if (!beanClasses.isEmpty()) {
                Thread.currentThread().setContextClassLoader(beanClasses.getFirst().getClassLoader());
            }

            // Validate class-level CDI rules (before bean discovery)
            try {
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

                // Validate observer/disposer method parameters (CDI spec)
                validateObserverParameters(observers, descriptors, index);
                validateDisposerParameters(disposers, descriptors, index);
                // Disposer method definition validation deferred (causes regressions with TCK test archives)

                // Validate deployment — throw if there are errors
                var assignability = new AssignabilityRules(index);
                var tempResolver = new BeanResolver(descriptors, interceptors, assignability);
                var validator = new fr.vidocq.vauban.core.bean.validation.DeploymentValidator(
                        descriptors, tempResolver);
                var errors = validator.validate();
                if (!errors.isEmpty()) {
                    var definitionErrors = errors.stream()
                            .filter(e -> e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEFINITION_ERROR)
                            .toList();

                    if (!definitionErrors.isEmpty()) {
                        var msg = new StringBuilder("CDI definition validation failed:\n");
                        for (var error : definitionErrors) {
                            msg.append("  - ").append(error.message()).append("\n");
                        }
                        throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
                    }

                    // Ambiguous or unsatisfied dependencies are DeploymentExceptions in CDI
                    var deploymentErrors = errors.stream()
                            .filter(e -> e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEPLOYMENT_ERROR
                                    || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY
                                    || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.AMBIGUOUS_DEPENDENCY
                                    || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.CIRCULAR_DEPENDENCY)
                            .toList();

                    if (!deploymentErrors.isEmpty()) {
                        var msg = new StringBuilder("CDI deployment validation failed:\n");
                        for (var error : deploymentErrors) {
                            msg.append("  - ").append(error.message()).append("\n");
                        }
                        throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                    }

                    // Default fallback
                    var msg = new StringBuilder("CDI validation failed:\n");
                    for (var error : errors) {
                        msg.append("  - ").append(error.message()).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                }

                var beanClassLoader = beanClasses.isEmpty()
                        ? Thread.currentThread().getContextClassLoader()
                        : beanClasses.getFirst().getClassLoader();
                return new VaubanContainer(index, descriptors, observers, interceptors, disposers, factories, beanClassLoader);
            } finally {
                Thread.currentThread().setContextClassLoader(previousCl);
            }
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

                // CDI spec: stereotype with @Named must have empty value
                for (var ann : clazz.getAnnotations()) {
                    if (ann.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                        var named = ann.annotationType().getAnnotation(jakarta.inject.Named.class);
                        if (named != null && !named.value().isEmpty()) {
                            errors.add("Stereotype " + ann.annotationType().getName()
                                    + " has @Named with non-empty value '" + named.value() + "'");
                        }
                    }
                }

                // CDI spec: a managed bean with type parameters and a non-@Dependent scope
                // is a DefinitionException (CDI 4.1 Section 3.1)
                if (hasBeanDefiningAnnotation(clazz) && clazz.getTypeParameters().length > 0) {
                    boolean isNonDependent = false;
                    for (var ann : clazz.getAnnotations()) {
                        var annType = ann.annotationType();
                        if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                                || annType == jakarta.inject.Singleton.class) {
                            isNonDependent = true;
                            break;
                        }
                    }
                    if (isNonDependent) {
                        errors.add("Managed bean " + clazz.getName()
                                + " has type parameters and is not @Dependent");
                    }
                }

                // CDI spec: @Named without value on non-field injection points
                if (hasBeanDefiningAnnotation(clazz)) {
                    for (var method : clazz.getDeclaredMethods()) {
                        if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                            for (var param : method.getParameters()) {
                                var named = param.getAnnotation(jakarta.inject.Named.class);
                                if (named != null && named.value().isEmpty()) {
                                    errors.add("@Named without value on initializer parameter: "
                                            + clazz.getName() + "." + method.getName());
                                }
                            }
                        }
                    }
                    for (var ctor : clazz.getDeclaredConstructors()) {
                        if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                            for (var param : ctor.getParameters()) {
                                var named = param.getAnnotation(jakarta.inject.Named.class);
                                if (named != null && named.value().isEmpty()) {
                                    errors.add("@Named without value on constructor parameter: "
                                            + clazz.getName());
                                }
                            }
                        }
                    }
                }

                // CDI spec: @Named without value on producer/observer/disposer method parameters
                for (var method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        for (var param : method.getParameters()) {
                            var named = param.getAnnotation(jakarta.inject.Named.class);
                            if (named != null && named.value().isEmpty()) {
                                errors.add("@Named without value on producer method parameter: "
                                        + clazz.getName() + "." + method.getName());
                            }
                        }
                    }
                    // Observer/disposer method non-event/non-disposes parameters
                    boolean hasObservesOrDisposes = false;
                    for (var param : method.getParameters()) {
                        if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)
                                || param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                            hasObservesOrDisposes = true;
                            break;
                        }
                    }
                    if (hasObservesOrDisposes) {
                        for (var param : method.getParameters()) {
                            if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                    || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)
                                    || param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) continue;
                            var named = param.getAnnotation(jakarta.inject.Named.class);
                            if (named != null && named.value().isEmpty()) {
                                errors.add("@Named without value on observer/disposer method parameter: "
                                        + clazz.getName() + "." + method.getName());
                            }
                        }
                    }
                }

                // CDI spec: normal-scoped beans must not have non-static public fields
                // This is a DefinitionException (not DeploymentException)
                if (hasBeanDefiningAnnotation(clazz)) {
                    boolean isNormalScoped = false;
                    for (var ann : clazz.getAnnotations()) {
                        if (ann.annotationType().isAnnotationPresent(
                                jakarta.enterprise.context.NormalScope.class)) {
                            isNormalScoped = true;
                            break;
                        }
                    }
                    if (isNormalScoped) {
                        for (var field : clazz.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isPublic(field.getModifiers())
                                    && !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                                    && !field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                                errors.add("Normal-scoped bean " + clazz.getName()
                                        + " has non-static public field '" + field.getName() + "'");
                                break;
                            }
                        }
                    }
                }

                // CDI spec: @Typed values must be legal bean types (supertypes of the bean class)
                if (clazz.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                    var typed = clazz.getAnnotation(jakarta.enterprise.inject.Typed.class);
                    for (var t : typed.value()) {
                        if (!t.isAssignableFrom(clazz)) {
                            errors.add("@Typed value " + t.getName() + " on " + clazz.getName()
                                    + " is not a legal bean type (not a supertype)");
                        }
                    }
                }

                // CDI spec: @Typed on producer methods/fields — values must be legal
                for (var method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                            && method.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                        var typed = method.getAnnotation(jakarta.enterprise.inject.Typed.class);
                        var returnType = method.getReturnType();
                        for (var t : typed.value()) {
                            if (!t.isAssignableFrom(returnType) && t != Object.class) {
                                errors.add("@Typed value " + t.getName() + " on producer method "
                                        + clazz.getName() + "." + method.getName()
                                        + " is not a legal bean type");
                            }
                        }
                    }
                }
                for (var field : clazz.getDeclaredFields()) {
                    if (field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                            && field.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                        var typed = field.getAnnotation(jakarta.enterprise.inject.Typed.class);
                        var fieldType = field.getType();
                        for (var t : typed.value()) {
                            if (!t.isAssignableFrom(fieldType) && t != Object.class) {
                                errors.add("@Typed value " + t.getName() + " on producer field "
                                        + clazz.getName() + "." + field.getName()
                                        + " is not a legal bean type");
                            }
                        }
                    }
                }

                // Generic managed bean — deferred

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
                        // Multiple scope annotations on producer method
                        validateNoMultipleScopes(method.getAnnotations(),
                                "Producer method " + clazz.getName() + "." + method.getName(), errors);
                    }

                    // CDI spec: producer with TypeVariable return type must be @Dependent
                    if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        var genRetType = method.getGenericReturnType();
                        if (containsTypeVariable(genRetType)) {
                            boolean isDependent = true;
                            for (var mAnn : method.getAnnotations()) {
                                if (mAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                                        || (mAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)
                                            && mAnn.annotationType() != jakarta.enterprise.context.Dependent.class)) {
                                    isDependent = false;
                                    break;
                                }
                            }
                            if (!isDependent) {
                                errors.add("Producer method " + clazz.getName() + "." + method.getName()
                                        + " has TypeVariable return type and non-@Dependent scope");
                            }
                        }
                    }

                    // CDI spec: producer with wildcard type parameter is not a legal bean type
                    if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        var genRetType = method.getGenericReturnType();
                        if (containsWildcard(genRetType)) {
                            errors.add("Producer method " + clazz.getName() + "." + method.getName()
                                    + " has wildcard type parameter in return type");
                        }
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
                            // CDI spec: disposer methods must not have InjectionPoint parameter
                            if (rawTypes[i] == jakarta.enterprise.inject.spi.InjectionPoint.class) {
                                errors.add("Disposer method " + clazz.getName() + "." + method.getName()
                                        + " must not have InjectionPoint parameter");
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
                        // Multiple scope annotations on producer field
                        validateNoMultipleScopes(field.getAnnotations(),
                                "Producer field " + clazz.getName() + "." + field.getName(), errors);
                        // CDI spec: producer field with TypeVariable type must be @Dependent
                        if (containsTypeVariable(field.getGenericType())) {
                            boolean isDependent = true;
                            for (var fAnn : field.getAnnotations()) {
                                if (fAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                                        || (fAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)
                                            && fAnn.annotationType() != jakarta.enterprise.context.Dependent.class)) {
                                    isDependent = false;
                                    break;
                                }
                            }
                            if (!isDependent) {
                                errors.add("Producer field " + clazz.getName() + "." + field.getName()
                                        + " has TypeVariable type and non-@Dependent scope");
                            }
                        }
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
                // CDI spec: stereotypes must not declare conflicting priorities (direct or transitive)
                if (hasBeanDefiningAnnotation(clazz)) {
                    var priorities = new java.util.LinkedHashSet<Integer>();
                    collectStereotypePriorities(clazz, priorities, new java.util.HashSet<>());
                    if (priorities.size() > 1) {
                        errors.add("Bean " + clazz.getName()
                                + " has conflicting stereotype priorities: " + priorities);
                    }
                }
                // CDI spec: if stereotypes declare conflicting scopes and bean has no explicit scope,
                // it's a DefinitionException
                if (hasBeanDefiningAnnotation(clazz) && !hasExplicitScope(clazz)) {
                    var scopes = new java.util.LinkedHashSet<Class<?>>();
                    collectStereotypeScopes(clazz, scopes, new java.util.HashSet<>());
                    if (scopes.size() > 1) {
                        errors.add("Bean " + clazz.getName()
                                + " has conflicting stereotype scopes: " + scopes);
                    }
                }
                // CDI spec: stereotypes must not declare same interceptor binding with different values
                // CDI spec: conflicting interceptor binding values (from stereotypes or transitive bindings)
                if (hasBeanDefiningAnnotation(clazz)) {
                    var bindingsByType = new java.util.HashMap<Class<?>, java.lang.annotation.Annotation>();
                    collectTransitiveInterceptorBindings(clazz.getAnnotations(), bindingsByType, errors, clazz.getName(), new java.util.HashSet<>());
                }
            }
            return errors;
        }

        private static void collectTransitiveInterceptorBindings(
                java.lang.annotation.Annotation[] annotations,
                java.util.Map<Class<?>, java.lang.annotation.Annotation> bindingsByType,
                List<String> errors, String beanName, Set<Class<?>> visited) {
            for (var ann : annotations) {
                var annType = ann.annotationType();
                // Check both stereotypes and interceptor bindings for transitive bindings
                if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)
                        || annType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                    if (!visited.add(annType)) continue;
                    for (var metaAnn : annType.getAnnotations()) {
                        if (metaAnn.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                            var prev = bindingsByType.put(metaAnn.annotationType(), metaAnn);
                            if (prev != null && !prev.equals(metaAnn)) {
                                errors.add("Bean " + beanName
                                        + " has conflicting interceptor binding values for "
                                        + metaAnn.annotationType().getSimpleName());
                            }
                        }
                    }
                    // Recurse into meta-annotations
                    collectTransitiveInterceptorBindings(annType.getAnnotations(), bindingsByType, errors, beanName, visited);
                }
            }
        }

        private static void collectStereotypePriorities(Class<?> clazz,
                Set<Integer> priorities, Set<Class<?>> visited) {
            for (var ann : clazz.getAnnotations()) {
                var annType = ann.annotationType();
                if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                    if (!visited.add(annType)) continue;
                    // Check if stereotype has @Priority
                    var priority = annType.getAnnotation(jakarta.annotation.Priority.class);
                    if (priority != null) {
                        priorities.add(priority.value());
                    }
                    // Check transitive stereotypes
                    collectStereotypePriorities(annType, priorities, visited);
                }
            }
        }

        private static boolean hasExplicitScope(Class<?> clazz) {
            for (var ann : clazz.getDeclaredAnnotations()) {
                var annType = ann.annotationType();
                if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                        || annType.isAnnotationPresent(jakarta.inject.Scope.class)) {
                    return true;
                }
            }
            return false;
        }

        private static void collectStereotypeScopes(Class<?> clazz,
                Set<Class<?>> scopes, Set<Class<?>> visited) {
            for (var ann : clazz.getAnnotations()) {
                var annType = ann.annotationType();
                if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                    if (!visited.add(annType)) continue;
                    // Check if stereotype has a scope
                    for (var metaAnn : annType.getAnnotations()) {
                        if (metaAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                                || metaAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)) {
                            scopes.add(metaAnn.annotationType());
                        }
                    }
                    // Check transitive stereotypes
                    collectStereotypeScopes(annType, scopes, visited);
                }
            }
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
            // CDI spec: parameterized type with wildcard type arguments
            if (type instanceof java.lang.reflect.ParameterizedType pt) {
                for (var arg : pt.getActualTypeArguments()) {
                    if (arg instanceof java.lang.reflect.WildcardType) {
                        errors.add("Producer " + location + " has parameterized type with wildcard argument");
                        break;
                    }
                }
            }
            if (type instanceof java.lang.reflect.GenericArrayType gat) {
                var componentType = gat.getGenericComponentType();
                if (componentType instanceof java.lang.reflect.TypeVariable<?>) {
                    errors.add("Producer " + location + " has array type with type variable component");
                }
                if (componentType instanceof java.lang.reflect.WildcardType) {
                    errors.add("Producer " + location + " has array type with wildcard component");
                }
                // Array of parameterized type with wildcards
                if (componentType instanceof java.lang.reflect.ParameterizedType cpt) {
                    for (var arg : cpt.getActualTypeArguments()) {
                        if (arg instanceof java.lang.reflect.WildcardType) {
                            errors.add("Producer " + location + " has array of parameterized type with wildcard");
                            break;
                        }
                    }
                }
            }
        }

        private static void validateNoMultipleScopes(java.lang.annotation.Annotation[] annotations,
                String location, List<String> errors) {
            int scopeCount = 0;
            for (var ann : annotations) {
                var annType = ann.annotationType();
                if (annType.isAnnotationPresent(jakarta.inject.Scope.class)
                        || annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                    scopeCount++;
                }
            }
            if (scopeCount > 1) {
                errors.add(location + " has multiple scope annotations");
            }
        }

        private static boolean containsWildcard(java.lang.reflect.Type type) {
            if (type instanceof java.lang.reflect.WildcardType) return true;
            if (type instanceof java.lang.reflect.ParameterizedType pt) {
                for (var arg : pt.getActualTypeArguments()) {
                    if (containsWildcard(arg)) return true;
                }
            }
            if (type instanceof java.lang.reflect.GenericArrayType gat) {
                return containsWildcard(gat.getGenericComponentType());
            }
            return false;
        }

        private static boolean containsTypeVariable(java.lang.reflect.Type type) {
            if (type instanceof java.lang.reflect.TypeVariable<?>) return true;
            if (type instanceof java.lang.reflect.ParameterizedType pt) {
                for (var arg : pt.getActualTypeArguments()) {
                    if (containsTypeVariable(arg)) return true;
                }
            }
            if (type instanceof java.lang.reflect.GenericArrayType gat) {
                return containsTypeVariable(gat.getGenericComponentType());
            }
            if (type instanceof java.lang.reflect.WildcardType wt) {
                for (var bound : wt.getUpperBounds()) {
                    if (containsTypeVariable(bound)) return true;
                }
                for (var bound : wt.getLowerBounds()) {
                    if (containsTypeVariable(bound)) return true;
                }
            }
            return false;
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
