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
    private final Map<Class<? extends Annotation>, List<Context>> contexts = new ConcurrentHashMap<>();
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
    private final java.util.function.BiFunction<String, byte[], Class<?>> classDefiner;
    private volatile boolean running;

    private VaubanContainer(VaubanIndex index, List<BeanDescriptor> descriptors,
                            List<ObserverDescriptor> observers,
                            List<InterceptorDescriptor> interceptorDescriptors,
                            List<DisposerDescriptor> disposers,
                            Map<DotName, BeanFactory<?>> factories,
                            Map<DotName, java.util.function.Consumer<Object>> syntheticDisposers,
                            ClassLoader classLoader,
                            java.util.function.BiFunction<String, byte[], Class<?>> classDefiner) {
        this.index = index;
        this.classLoader = classLoader;
        this.classDefiner = classDefiner;
        this.applicationContext = new ApplicationContext();
        this.requestContext = new RequestContext();
        this.dependentContext = new DependentContext();

        contexts.computeIfAbsent(jakarta.enterprise.context.ApplicationScoped.class, k -> new java.util.ArrayList<>()).add(applicationContext);
        contexts.computeIfAbsent(jakarta.enterprise.context.RequestScoped.class, k -> new java.util.ArrayList<>()).add(requestContext);
        contexts.computeIfAbsent(jakarta.enterprise.context.Dependent.class, k -> new java.util.ArrayList<>()).add(dependentContext);
        contexts.computeIfAbsent(jakarta.inject.Singleton.class, k -> new java.util.ArrayList<>()).add(applicationContext);

        for (var descriptor : descriptors) {
            BeanFactory<?> factory;
            if (descriptor.kind() == BeanDescriptor.BeanKind.MANAGED) {
                factory = createManagedBeanFactory(descriptor, factories);
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD) {
                factory = createProducerMethodFactory(descriptor);
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                factory = createProducerFieldFactory(descriptor);
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.SYNTHETIC) {
                // Synthetic beans use their unique ID as factory key
                factory = factories.get(DotName.of(descriptor.id().value()));
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

        // Wire up synthetic bean disposers
        for (var entry : syntheticDisposers.entrySet()) {
            var syntheticKey = entry.getKey();
            var destroyer = entry.getValue();
            for (var beanEntry : beans.entrySet()) {
                if (beanEntry.getKey().equals(new BeanId(syntheticKey.value()))) {
                    beanEntry.getValue().setDestroyer(destroyer);
                }
            }
        }

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


    private Object getOrCreateInterceptorInstance(InterceptorDescriptor descriptor, CreationalContext<?> ctx) {
        String className = descriptor.interceptorClass().value();

        if (ctx instanceof CreationalContextImpl<?> vCtx) {
            // Check current context cache
            Object cached = vCtx.getInterceptorInstance(className);
            if (cached != null) {
                return cached;
            }
            
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
                injectFieldsByReflection(instance, null, ctx);
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

            // Dependency injection on the interceptor instance
            injectFields(instance, null, (CreationalContext<Object>) ctx);

            // Call @PostConstruct on the interceptor itself
            callPostConstruct(instance, null, ctx);

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
        var context = getFirstContext(scopeClass);
        if (context == null) {
            context = dependentContext;
        }
        
        // Return existing instance from context if available
        var existing = context.get((Contextual<T>) finalBean);
        if (existing != null) return existing;

        // For normal-scoped beans, return a client proxy
        if (finalBean.descriptor().scope().isNormal()) {
            return getOrCreateProxy(finalBean);
        }
        
        CreationalContext<T> creationalCtx = new CreationalContextImpl<T>();
        return context.get((Contextual<T>) finalBean, creationalCtx);
    }

    private final Map<fr.vidocq.vauban.core.bean.model.BeanId, Object> proxyCache = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private <T> T getOrCreateProxy(ManagedBean<T> bean) {
        return (T) proxyCache.computeIfAbsent(bean.descriptor().id(), id -> {
            var beanClass = resolveProxyTargetClass(bean);

            // CDI Unproxyable bean checks
            if (java.lang.reflect.Modifier.isFinal(beanClass.getModifiers())) {
                throw new jakarta.enterprise.inject.UnproxyableResolutionException("Normal scoped bean " + beanClass.getName() + " is final");
            }
            for (var method : beanClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isFinal(method.getModifiers())
                        && !java.lang.reflect.Modifier.isPrivate(method.getModifiers())
                        && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    throw new jakarta.enterprise.inject.UnproxyableResolutionException("Normal scoped bean " + beanClass.getName() + " has final method " + method.getName());
                }
            }
            boolean hasNoArgCtor = false;
            boolean hasAnyCtor = false;
            for (var ctor : beanClass.getDeclaredConstructors()) {
                hasAnyCtor = true;
                if (ctor.getParameterCount() == 0 && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                    hasNoArgCtor = true;
                    break;
                }
            }
            if (hasAnyCtor && !hasNoArgCtor) {
                throw new jakarta.enterprise.inject.UnproxyableResolutionException("Normal scoped bean " + beanClass.getName() + " has no non-private no-arg constructor");
            }

            try {
                var generated = fr.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator.generate(beanClass);

                // Load the proxy class (check if already defined)
                Class<?> proxyClass;
                try {
                    proxyClass = loadOrDefineClassRobustly(beanClass, generated.className(), generated.bytecode());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to define proxy class for " + beanClass, e);
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
                    var ctx = getFirstContext(scopeClass);
                    if (ctx == null) ctx = dependentContext;
                    return ctx.get((Contextual<Object>) (Contextual<?>) currentBean,
                            new CreationalContextImpl<Object>());
                };
                setDelegate.invoke(proxy, delegate);

                return proxy;
            } catch (Exception e) {
                // Fallback: return direct instance (no proxy)
                var scopeClass = bean.getScope();
                var ctx = getFirstContext(scopeClass);
                if (ctx == null) ctx = dependentContext;
                return ctx.get((Contextual<Object>) (Contextual<?>) bean,
                        new CreationalContextImpl<>());
            }
        });
    }

    private Class<?> resolveProxyTargetClass(ManagedBean<?> bean) {
        if (bean.descriptor().kind() == fr.vidocq.vauban.core.bean.model.BeanDescriptor.BeanKind.PRODUCER_METHOD
                || bean.descriptor().kind() == fr.vidocq.vauban.core.bean.model.BeanDescriptor.BeanKind.PRODUCER_FIELD) {
            for (var type : bean.getTypes()) {
                if (type instanceof Class<?> c && c != Object.class && !c.isInterface()) {
                    return c;
                }
            }
        }
        return bean.getBeanClass();
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
            if (observer.isSynthetic()) continue;
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
                                || paramType == jakarta.enterprise.inject.spi.Bean.class
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
                    for (int pi = 0; pi < method.getParameterCount(); pi++) {
                        var param = method.getParameters()[pi];
                        if (param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) continue;
                        var paramType = param.getType();
                        var paramTypeName = paramType.getName();
                        // CDI spec: Bean<X> in disposer must use wildcard (Bean<?>), not concrete type
                        if (paramTypeName.equals("jakarta.enterprise.inject.spi.Bean")) {
                            var genericType = method.getGenericParameterTypes()[pi];
                            if (genericType instanceof java.lang.reflect.ParameterizedType pt
                                    && pt.getActualTypeArguments().length > 0
                                    && !(pt.getActualTypeArguments()[0] instanceof java.lang.reflect.WildcardType)) {
                                throw new jakarta.enterprise.inject.spi.DefinitionException(
                                    "Disposer method " + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                                    + "(): Bean parameter must use wildcard type (Bean<?>), not concrete type");
                            }
                            continue;
                        }
                        if (paramTypeName.equals("jakarta.enterprise.inject.spi.BeanManager")
                                || paramTypeName.equals("jakarta.enterprise.inject.spi.BeanContainer")
                                || paramTypeName.equals("jakarta.enterprise.inject.spi.InjectionPoint")
                                || paramTypeName.equals("jakarta.enterprise.inject.Instance")
                                || paramTypeName.equals("jakarta.inject.Provider")
                                || paramTypeName.equals("jakarta.enterprise.event.Event")) continue;
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
            } catch (jakarta.enterprise.inject.spi.DeploymentException | jakarta.enterprise.inject.spi.DefinitionException e) {
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

    Map<Class<? extends Annotation>, List<Context>> contexts() {
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
        var context = getFirstContext(scopeClass);
        if (context == null) {
            context = dependentContext;
        }
        return context.get((jakarta.enterprise.context.spi.Contextual<T>) bean, new CreationalContextImpl<>());
    }

    private Context getFirstContext(Class<? extends Annotation> scopeType) {
        var list = contexts.get(scopeType);
        return (list != null && !list.isEmpty()) ? list.getFirst() : null;
    }

    public InterceptorManager interceptorManager() {
        return interceptorManager;
    }

    private void injectFields(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        injectFieldsByReflection(instance, descriptor, parentCtx);

        // 2. Call @Inject initializer methods
        callInitializerMethods(instance, parentCtx);

        // 3. Call @PostConstruct
        callPostConstruct(instance, descriptor, parentCtx);

        // 4. CDI spec 9.3: eagerly create all interceptor instances for this bean
        eagerCreateInterceptors(instance, descriptor, parentCtx);
    }

    private boolean hasMethodOrClassInterceptors(ManagedBean<?> mb) {
        var classBindings = mb.descriptor().interceptorBindings();
        if (classBindings != null && !classBindings.isEmpty()
                && !interceptorManager.resolveInterceptorDescriptors(classBindings).isEmpty()) {
            return true;
        }
        try {
            var beanClass = loadClass(mb.descriptor().beanClass().value());
            for (var m : beanClass.getDeclaredMethods()) {
                for (var ann : m.getAnnotations()) {
                    if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                        return true;
                    }
                }
            }
        } catch (ClassNotFoundException e) { /* skip */ }
        return false;
    }

    private void eagerCreateInterceptors(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        if (interceptorManager == null || !interceptorManager.hasInterceptors()) return;
        if (instance.getClass().isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;

        var allBindings = new java.util.LinkedHashSet<DotName>();
        var beanBindings = (descriptor != null) ? descriptor.interceptorBindings() : findInterceptorBindings(instance);
        if (beanBindings == null || beanBindings.isEmpty()) {
            beanBindings = findInterceptorBindings(instance);
        }
        if (beanBindings != null) {
            allBindings.addAll(beanBindings);
        }
        var beanClass = instance.getClass();
        if (beanClass.getName().contains("$$Intercepted") || beanClass.getName().contains("$$Proxy")) {
            beanClass = beanClass.getSuperclass();
        }
        for (var m : beanClass.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                    allBindings.add(DotName.of(ann.annotationType().getName()));
                }
            }
        }
        if (allBindings.isEmpty()) return;
        interceptorManager.setClassLoader(instance.getClass().getClassLoader());
        var matchingDescriptors = interceptorManager.resolveInterceptorDescriptors(allBindings);
        for (var desc : matchingDescriptors) {
            interceptorManager.getOrCreateInstance(desc, parentCtx);
        }
    }

    private void injectFieldsByReflection(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        var beanClass = instance.getClass();
        var typeMapping = ManagedBean.buildTypeVariableMapping(beanClass);
        var clazz = beanClass;
        while (clazz != null && clazz != Object.class) {
            for (var field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
                field.setAccessible(true);
                try {

                // Handle InjectionPoint injection — the dependent bean receives the
                // InjectionPoint that describes WHERE it was injected (set by the caller).
                // CDI spec: null if not being injected (programmatic lookup).
                if (field.getType() == InjectionPoint.class) {
                    field.set(instance, currentInjectionPoint.get());
                    continue;
                }

                // Handle Instance<T> and Provider<T> injection
                if (field.getType() == Instance.class
                        || field.getType() == jakarta.inject.Provider.class) {
                    Class<?> instanceType = Object.class;
                    var genericType = ManagedBean.resolveType(field.getGenericType(), typeMapping);
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
                    var fieldQuals = extractFieldQualifiersWithEnhancement(field, descriptor);
                    Object value;
                    var bm = getBeanManager();
                    // Use generic type to preserve parameterized type info, resolving type variables
                    var fieldType = ManagedBean.resolveType(field.getGenericType(), typeMapping);
                    var resolvedBeans = bm.getBeans(fieldType, fieldQuals);
                    if (resolvedBeans.isEmpty()) {
                        value = select(field.getType());
                    } else {
                        var resolved = bm.resolve(resolvedBeans);
                        boolean needsFreshCtx = resolved instanceof ManagedBean<?> mb
                                && resolved.getScope() == jakarta.enterprise.context.Dependent.class
                                && mb.descriptor().kind() == BeanDescriptor.BeanKind.MANAGED
                                && interceptorManager != null && interceptorManager.hasInterceptors()
                                && hasMethodOrClassInterceptors(mb);
                        var ctx = (parentCtx != null
                                && resolved.getScope() == jakarta.enterprise.context.Dependent.class
                                && !needsFreshCtx)
                                ? parentCtx
                                : bm.createCreationalContext(resolved);
                        value = bm.getReference(resolved, fieldType, ctx);
                        if (needsFreshCtx
                                && parentCtx instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> parentVCtx
                                && value != null) {
                            parentVCtx.addDependentInstance(resolved, value, ctx);
                        }
                    }
                    // CDI spec: don't set null on primitive fields
                    if (value != null || !field.getType().isPrimitive()) {
                        field.set(instance, value);
                    }
                } finally {
                    currentInjectionPoint.set(previousIp);
                }
            } catch (jakarta.enterprise.inject.IllegalProductException | jakarta.enterprise.inject.UnproxyableResolutionException e) {
                throw e;
            } catch (Exception e) {
                if (e.getCause() instanceof jakarta.enterprise.inject.IllegalProductException ipe) throw ipe;
                // Skip fields that can't be resolved (may not be CDI beans)
                System.err.println("INJECTION FAILED FOR " + field.getName() + " ON " + instance.getClass() + " : " + e.getMessage());
                e.printStackTrace();
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
        // Check superclass for intercepted/proxied subclasses
        var superClass = instanceClass.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            for (var bean : beans.values()) {
                if (bean.getBeanClass() == superClass) {
                    return bean;
                }
            }
        }
        return null;
    }

    private ManagedBean<?> findManagedBeanByClass(DotName beanClassName) {
        for (var bean : beans.values()) {
            if (bean.descriptor().beanClass().equals(beanClassName)
                    && bean.descriptor().kind() == BeanDescriptor.BeanKind.MANAGED) {
                return bean;
            }
        }
        return null;
    }

    /** Returns the MANAGED bean for the exact class (no subtype matching), or null. */
    public ManagedBean<?> findManagedBeanByExactClass(Class<?> beanClass) {
        return findManagedBeanByClass(DotName.of(beanClass.getName()));
    }

    private void callInitializerMethods(Object instance, CreationalContext<?> ctx) {
        var clazz = instance.getClass();
        // Skip intercepted subclass — look at the actual bean class
        if (clazz.getName().contains("$$Intercepted")) {
            clazz = clazz.getSuperclass();
        }
        var ownerBean = findBeanForInstance(instance);
        var typeMapping = ManagedBean.buildTypeVariableMapping(clazz);
        // Walk hierarchy to find all @Inject initializer methods
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                method.setAccessible(true);
                try {
                    var paramTypes = method.getParameterTypes();
                    var rawGenericParamTypes = method.getGenericParameterTypes();
                    var genericParamTypes = new java.lang.reflect.Type[rawGenericParamTypes.length];
                    for (int i = 0; i < rawGenericParamTypes.length; i++) {
                        genericParamTypes[i] = ManagedBean.resolveType(rawGenericParamTypes[i], typeMapping);
                    }
                    var params = method.getParameters();
                    var args = new Object[paramTypes.length];
                    var transientContexts = new java.util.ArrayList<CreationalContextImpl<?>>();
                    for (int i = 0; i < paramTypes.length; i++) {
                        var paramQuals = extractParamQualifiers(params[i]);
                        if (params[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)) {
                            var transientCtx = new CreationalContextImpl<>();
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], transientCtx, paramQuals, method, ownerBean, params[i], i);
                            transientContexts.add(transientCtx);
                        } else {
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx, paramQuals, method, ownerBean, params[i], i);
                        }
                    }
                    method.invoke(instance, args);
                    for (var tc : transientContexts) {
                        tc.release();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to call initializer method: " + method.getName(), e);
                }
                }
            }
            current = current.getSuperclass();
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
        // CDI 4.1 Section 2.3.5: @Default if no qualifier declared.
        // @Any is NOT added — getBeans() handles implicit @Any matching.
        if (!hasAnyAnnotation) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return quals.toArray(new java.lang.annotation.Annotation[0]);
    }

    private static Set<java.lang.annotation.Annotation> collectQualifierSet(java.lang.annotation.Annotation[] annotations) {
        var quals = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        for (var ann : annotations) {
            if (ann.annotationType() == jakarta.inject.Inject.class) continue;
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class) {
                quals.add(ann);
            }
        }
        // CDI 4.1 Section 2.3.5: @Default added only if no qualifier declared.
        // @Any is NOT added — it's a bean-side concept, not an IP qualifier.
        if (quals.isEmpty()) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return quals;
    }

    private static java.lang.annotation.Annotation[] collectEventQualifiers(java.lang.annotation.Annotation[] annotations) {
        var quals = new java.util.ArrayList<java.lang.annotation.Annotation>();
        boolean hasExplicitQualifier = false;
        for (var ann : annotations) {
            if (ann.annotationType() == jakarta.inject.Inject.class) continue;
            if (ann.annotationType() == jakarta.enterprise.inject.Default.class) {
                quals.add(ann);
                continue;
            }
            if (ann.annotationType() == jakarta.enterprise.inject.Any.class) {
                quals.add(ann);
                hasExplicitQualifier = true;
                continue;
            }
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                quals.add(ann);
                hasExplicitQualifier = true;
            }
        }
        // CDI spec: if no explicit qualifier on the Event injection point, add @Default
        if (!hasExplicitQualifier) {
            boolean hasDefault = quals.stream().anyMatch(q -> q.annotationType() == jakarta.enterprise.inject.Default.class);
            if (!hasDefault) {
                quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
            }
        }
        // CDI spec: Event always has @Any
        boolean hasAny = quals.stream().anyMatch(q -> q.annotationType() == jakarta.enterprise.inject.Any.class);
        if (!hasAny) {
            quals.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        }
        return quals.toArray(new java.lang.annotation.Annotation[0]);
    }

    private void callPostConstruct(Object instance, BeanDescriptor descriptor, CreationalContext<?> ctx) {
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

        // Find all @PostConstruct methods in the bean hierarchy (respecting override rules)
        var postConstructMethods = collectLifecycleMethodsInHierarchy(instance.getClass(), jakarta.annotation.PostConstruct.class);

        // Check for lifecycle interceptors on the bean
        java.util.Set<fr.vidocq.vauban.indexer.model.DotName> beanBindings = (descriptor != null) ? descriptor.interceptorBindings() : findInterceptorBindings(instance);
        // Fallback: check via reflection if descriptor bindings are empty
        if ((beanBindings == null || beanBindings.isEmpty()) && interceptorManager.hasInterceptors()) {
            beanBindings = findInterceptorBindings(instance);
        }
        if (beanBindings != null && !beanBindings.isEmpty() && interceptorManager.hasInterceptors()) {
            interceptorManager.setClassLoader(instance.getClass().getClassLoader());
            var bindingAnns = (descriptor != null && !descriptor.interceptorBindingAnnotations().isEmpty())
                    ? new java.util.ArrayList<java.lang.annotation.Annotation>(descriptor.interceptorBindingAnnotations())
                    : new java.util.ArrayList<java.lang.annotation.Annotation>(collectBindingAnnotations(instance));
            var lifecycleChain = interceptorManager.resolveLifecycleChain(
                    beanBindings, jakarta.annotation.PostConstruct.class, bindingAnns, ctx);
            if (!lifecycleChain.isEmpty()) {
                var bindingAnnotations = new java.util.LinkedHashSet<java.lang.annotation.Annotation>(bindingAnns);
                final var pcMethods = postConstructMethods;
                var invocationCtx = new fr.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                        instance, null, new Object[0], lifecycleChain,
                        (target, params) -> {
                            for (var m : pcMethods) {
                                m.setAccessible(true);
                                m.invoke(target);
                            }
                            return null;
                        });
                invocationCtx.setInterceptorBindings(bindingAnnotations);
                try {
                    invocationCtx.proceed();
                } catch (Throwable e) {
                    if (e instanceof RuntimeException re) throw re;
                    if (e instanceof Error err) throw err;
                    throw new RuntimeException("Lifecycle interceptor failed", e);
                }
                return;
            }
        }

        // No interceptors — call @PostConstruct directly
        for (var pcMethod : postConstructMethods) {
            try {
                pcMethod.setAccessible(true);
                pcMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException("@PostConstruct failed: " + pcMethod, e);
            }
        }
    }

    /**
     * Collect all lifecycle methods in a class hierarchy, respecting override rules.
     * Superclass methods come first. If a subclass overrides a method WITHOUT the
     * lifecycle annotation, the callback is disabled.
     */
    private static java.util.List<java.lang.reflect.Method> collectLifecycleMethodsInHierarchy(
            Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotation) {
        // Skip intercepted subclass
        if (clazz.getName().contains("$$Intercepted") || clazz.getName().contains("$$Proxy")) {
            clazz = clazz.getSuperclass();
        }
        var result = new java.util.ArrayList<java.lang.reflect.Method>();
        collectLifecycleMethodsRecursive(clazz, annotation, result);
        return result;
    }

    private static void collectLifecycleMethodsRecursive(Class<?> clazz,
            Class<? extends java.lang.annotation.Annotation> annotation,
            java.util.List<java.lang.reflect.Method> result) {
        if (clazz == null || clazz == Object.class) return;
        // Process superclass first (CDI spec: superclass methods called first)
        collectLifecycleMethodsRecursive(clazz.getSuperclass(), annotation, result);
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                // Remove any superclass method with the same signature (override)
                result.removeIf(m -> m.getName().equals(method.getName())
                        && java.util.Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
                result.add(method);
            } else {
                // If subclass overrides WITHOUT annotation, disable the callback
                result.removeIf(m -> m.getName().equals(method.getName())
                        && java.util.Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
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
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted")) {
            clazz = clazz.getSuperclass();
        }
        var annotations = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                bindings.add(DotName.of(ann.annotationType().getName()));
                annotations.add(ann);
            }
        }
        // Collect transitive bindings
        collectTransitiveBindings(annotations, bindings);
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

                // Merge bindings added by Enhancement (not present on the class bytecode)
                classBindings.addAll(bindings);

                // Register ONLY enhancement-added binding annotations (not already on the class)
                if (!descriptor.interceptorBindingAnnotations().isEmpty()) {
                    var classAnnotationTypes = new java.util.HashSet<String>();
                    for (var ann : beanClass.getAnnotations()) {
                        classAnnotationTypes.add(ann.annotationType().getName());
                    }
                    var enhancedOnly = descriptor.interceptorBindingAnnotations().stream()
                            .filter(ann -> !classAnnotationTypes.contains(ann.annotationType().getName()))
                            .toList();
                    if (!enhancedOnly.isEmpty()) {
                        interceptorManager.registerEnhancedBindings(
                                descriptor.beanClass().value(), enhancedOnly);
                    }
                }

                // Check if there are matching interceptors (class, method, or constructor level)
                var matches = interceptorManager.resolveInterceptorDescriptors(classBindings);
                if (matches.isEmpty()) {
                    boolean hasInterceptors = false;
                    // Check method-level bindings (walk hierarchy for inherited methods)
                    var checkClass = beanClass;
                    while (checkClass != null && checkClass != Object.class && !hasInterceptors) {
                        for (var m : checkClass.getDeclaredMethods()) {
                            if (!interceptorManager.resolveInterceptorDescriptorsForMethod(classBindings, m).isEmpty()) {
                                hasInterceptors = true;
                                break;
                            }
                        }
                        checkClass = checkClass.getSuperclass();
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
                    // Check for target-class @AroundInvoke methods
                    if (!hasInterceptors) {
                        var cur = beanClass;
                        while (cur != null && cur != Object.class) {
                            for (var m : cur.getDeclaredMethods()) {
                                if (m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                                    hasInterceptors = true;
                                    break;
                                }
                            }
                            if (hasInterceptors) break;
                            cur = cur.getSuperclass();
                        }
                    }
                    if (!hasInterceptors) continue;
                }
                
                // Generate the intercepted subclass
                var generated = fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                        .generate(beanClass, classBindings, descriptor.constructorBindings());

                try {
                    Class<?> interceptedClass;
                    if (classDefiner != null) {
                        // Use the provided class definer (e.g., ByteArrayClassLoader)
                        interceptedClass = classDefiner.apply(generated.className(), generated.bytecode());
                    } else {
                        // Default: use privateLookupIn to define in the bean's classloader
                        try {
                            interceptedClass = loadOrDefineClassRobustly(beanClass, generated.className(), generated.bytecode());
                        } catch (Exception e) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException("Could not define interceptor subclass", e);
                        }
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
                            } catch (jakarta.enterprise.inject.spi.DeploymentException | jakarta.enterprise.inject.CreationException e) {
                                // Wrap deployment/creation exceptions
                                throw e;
                            } catch (Exception e) {
                                // CDI spec: exceptions from @AroundConstruct interceptors should propagate directly
                                // This includes application exceptions thrown by interceptors
                                if (e instanceof RuntimeException re) {
                                    throw re;
                                }
                                // For checked exceptions, wrap in CreationException as they can't be thrown directly
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
                    } catch (RuntimeException e) {
                        throw e;
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
                var originalBean = (ManagedBean<?>) beans.get(descriptor.id());
                var interceptedBean = new ManagedBean<>(descriptor, interceptedFactory, classLoader);
                if (originalBean != null) {
                    interceptedBean.setInjector((java.util.function.BiConsumer) originalBean.getInjector());
                    interceptedBean.setDestroyer((java.util.function.Consumer) originalBean.getDestroyer());
                }
                interceptedBean.setInterceptorManager(this.interceptorManager);
                beans.put(descriptor.id(), interceptedBean);
            } catch (Exception e) {
                // CDI spec: deployment/definition errors must propagate
                if (e instanceof jakarta.enterprise.inject.spi.DeploymentException de) throw de;
                if (e instanceof jakarta.enterprise.inject.spi.DefinitionException de) throw de;
                    // Primary interception failed — try fallback
                    // MethodHandles.privateLookupIn may fail for custom classloaders
                    // Fallback: define class via bean's classloader directly
                    var generated2 = fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                            .generate(beanClass, bindings, descriptor.constructorBindings());
                    
                    Class<?> interceptedClass2;
                    try {
                        if (classDefiner != null) {
                            interceptedClass2 = classDefiner.apply(generated2.className(), generated2.bytecode());
                        } else {
                            interceptedClass2 = loadOrDefineClassRobustly(beanClass, generated2.className(), generated2.bytecode());
                        }
                    } catch (Exception ex2) {
                        System.err.println("[VAUBAN-DBG] Fallback interception also failed for " + descriptor.beanClass().value() + ": " + ex2);
                        throw new jakarta.enterprise.inject.spi.DeploymentException("Could not define interceptor subclass", ex2);
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
                    var originalBean2 = (ManagedBean<?>) beans.get(descriptor.id());
                    if (originalBean2 != null) {
                        ib2.setInjector((java.util.function.BiConsumer) originalBean2.getInjector());
                        ib2.setDestroyer((java.util.function.Consumer) originalBean2.getDestroyer());
                    }
                    ib2.setInterceptorManager(this.interceptorManager);
                    beans.put(descriptor.id(), ib2);
                } catch (LinkageError le2) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                            "Cannot create interceptor subclass: " + le2.getMessage(), le2);
                }
            } catch (Exception e) {
                if (e instanceof jakarta.enterprise.inject.spi.DeploymentException de) throw de;
                if (e instanceof jakarta.enterprise.inject.spi.DefinitionException de) throw de;
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
                // Special case: @Any EXPLICITLY on disposer param matches ALL producers of same type
                boolean disposerHasExplicitAny = hasExplicitAnyOnDisposerParam(disposer);
                boolean qualifiersMatch;
                if (disposerHasExplicitAny) {
                    // @Any disposer matches any producer regardless of its qualifiers
                    qualifiersMatch = true;
                } else {
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
                    if (disposerQualNames.isEmpty()) {
                        qualifiersMatch = producerQualNames.isEmpty();
                    } else if (!producerQualNames.containsAll(disposerQualNames)) {
                        qualifiersMatch = false;
                    } else {
                        // Annotation names match — verify via reflection for exact member values
                        qualifiersMatch = verifyDisposerQualifiersViaReflection(descriptor, disposer);
                    }
                }
                if (!qualifiersMatch) continue;

                if (bean.getDestroyer() != null) {
                    // CDI spec: multiple disposer methods for same producer → DefinitionException
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                        "Multiple disposer methods for producer " + descriptor.id() + " in " + descriptor.beanClass());
                }
                bean.setDestroyer(instance -> callDisposer(instance, disposer));
            }
        }
    }

    private boolean matchesDisposerType(BeanDescriptor descriptor, DisposerDescriptor disposer) {
        for (var bt : descriptor.types()) {
            if (bt.equals(disposer.disposedType())) return true;
            if (bt instanceof TypeInfo.ClassType btCt
                    && disposer.disposedType() instanceof TypeInfo.ClassType dCt
                    && btCt.name().equals(dCt.name())) {
                return true;
            }
        }
        if (disposer.disposedType() instanceof TypeInfo.ClassType dCt) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var disposedClass = Class.forName(dCt.name().value(), false, cl);
                for (var bt : descriptor.types()) {
                    String btName = null;
                    if (bt instanceof TypeInfo.ClassType btCt2) btName = btCt2.name().value();
                    else if (bt instanceof TypeInfo.ParameterizedType pt) btName = pt.rawType().value();
                    if (btName != null) {
                        var beanTypeClass = Class.forName(btName, false, cl);
                        if (disposedClass.isAssignableFrom(beanTypeClass)) return true;
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }
        return false;
    }

    /** Returns true if @Any was EXPLICITLY declared on the disposer parameter (not just auto-added). */
    private boolean hasExplicitAnyOnDisposerParam(DisposerDescriptor disposer) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var declaringClass = Class.forName(disposer.declaringClass().value(), false, cl);
            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(disposer.methodName())
                        && method.getParameterCount() > disposer.parameterIndex()) {
                    for (var ann : method.getParameters()[disposer.parameterIndex()].getAnnotations()) {
                        if (ann.annotationType() == jakarta.enterprise.inject.Any.class) return true;
                    }
                    return false;
                }
            }
        } catch (Exception e) { /* ignore */ }
        return false;
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
                    var bm = getBeanManager();
                    var ctx = new fr.vidocq.vauban.core.context.CreationalContextImpl<>();
                    try {
                        var declBeans = bm.getBeans(declaringClass);
                        var declBean = declBeans.isEmpty() ? null : bm.resolve(declBeans);
                        var declaringInstance = java.lang.reflect.Modifier.isStatic(method.getModifiers())
                                ? null : (declBean != null
                                        ? bm.getReference(declBean, declaringClass, ctx)
                                        : selectByBeanClass(declaringClass));
                        var paramTypes = method.getParameterTypes();
                        var args = new Object[method.getParameterCount()];
                        args[disposer.parameterIndex()] = producedInstance;
                        for (int i = 0; i < paramTypes.length; i++) {
                            if (i == disposer.parameterIndex()) continue;
                            try {
                                if (paramTypes[i] == BeanManager.class) {
                                    args[i] = getBeanManager();
                                } else if (paramTypes[i] == Event.class) {
                                    var eventIp = new VaubanInjectionPoint(method.getGenericParameterTypes()[i],
                                            collectQualifierSet(method.getParameters()[i].getAnnotations()), null, method);
                                    args[i] = new EventImpl<>(eventDispatcher, collectEventQualifiers(method.getParameters()[i].getAnnotations()), eventIp);
                                } else if (paramTypes[i] == Instance.class) {
                                    Class<?> instanceType = Object.class;
                                    var genericType = method.getGenericParameterTypes()[i];
                                    if (genericType instanceof ParameterizedType pt) {
                                        var typeArg = pt.getActualTypeArguments()[0];
                                        if (typeArg instanceof Class<?> c) instanceType = c;
                                    }
                                    var ip = new VaubanInjectionPoint(genericType, java.util.Set.of(jakarta.enterprise.inject.Default.Literal.INSTANCE), null, method);
                                    args[i] = new InstanceImpl<>(this, instanceType, ip);
                                } else {
                                    var beans = bm.getBeans(paramTypes[i]);
                                    var bean = beans.isEmpty() ? null : bm.resolve(beans);
                                    args[i] = bean != null ? bm.getReference(bean, paramTypes[i], ctx) : select(paramTypes[i]);
                                }
                            } catch (Exception e) {
                                // Best effort for other params
                            }
                        }
                        method.invoke(declaringInstance, args);
                    } finally {
                        ctx.release();
                    }
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
                    var ctorOwnerBean = beans.get(descriptor.id());
                    var args = new Object[paramTypes.length];
                    var transientCtxs = new java.util.ArrayList<fr.vidocq.vauban.core.context.CreationalContextImpl<?>>();
                    for (int i = 0; i < paramTypes.length; i++) {
                        var pQuals = extractParamQualifiers(ctorParamsRefl[i]);
                        if (ctorParamsRefl[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)) {
                            var transientCtx = new fr.vidocq.vauban.core.context.CreationalContextImpl<>();
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], transientCtx, pQuals, injectCtor, ctorOwnerBean, ctorParamsRefl[i], i);
                            transientCtxs.add(transientCtx);
                        } else {
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], creationalCtx, pQuals, injectCtor, ctorOwnerBean, ctorParamsRefl[i], i);
                        }
                    }

                    final var finalCtor = injectCtor;
                    final var finalArgs = args;
                    finalCtor.setAccessible(true);

                    Object result;
                    if (constructCtx != null) {
                        result = finalCtor.newInstance(finalArgs);
                    } else {
                        result = finalCtor.newInstance(finalArgs);
                    }
                    for (var tc : transientCtxs) {
                        tc.release();
                    }
                    return result;
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
                    // CDI spec: @Dependent declaring bean must be destroyed after producer method invocation
                    ManagedBean<?> declBean = findManagedBeanByClass(descriptor.beanClass());
                    boolean isDependent = declBean != null
                            && declBean.getScope() == jakarta.enterprise.context.Dependent.class;
                    var declaringInstance = selectByBeanClass(declaringClass);

                    for (var method : declaringClass.getDeclaredMethods()) {
                        if (method.getName().equals(methodName)) {
                            method.setAccessible(true);
                            var transientCtx = new fr.vidocq.vauban.core.context.CreationalContextImpl<>();
                            try {
                                if (method.getParameterCount() == 0) {
                                    return method.invoke(declaringInstance);
                                }
                                var paramTypes = method.getParameterTypes();
                                var genericParamTypes = method.getGenericParameterTypes();
                                var params = method.getParameters();
                                var args = new Object[paramTypes.length];
                                for (int i = 0; i < paramTypes.length; i++) {
                                    var qualifiers = extractParamQualifiers(params[i]);
                                    if (params[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)) {
                                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], transientCtx, qualifiers, method);
                                    } else {
                                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx != null ? ctx : transientCtx, qualifiers, method);
                                    }
                                }
                                return method.invoke(declaringInstance, args);
                            } finally {
                                transientCtx.release();
                                // CDI spec: destroy @Dependent declaring bean after producer method completes
                                if (isDependent && declaringInstance != null) {
                                    @SuppressWarnings("unchecked")
                                    var castBean = (ManagedBean<Object>) (ManagedBean<?>) declBean;
                                    castBean.destroy(declaringInstance,
                                            new fr.vidocq.vauban.core.context.CreationalContextImpl<>());
                                }
                            }
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
    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        return null;
    }

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

    private static java.lang.annotation.Annotation[] extractFieldQualifiersWithEnhancement(
            java.lang.reflect.Field field, BeanDescriptor descriptor) {
        if (descriptor != null) {
            for (var ip : descriptor.injectionPoints()) {
                if (ip.kind() != fr.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.FIELD) continue;
                if (!ip.description().endsWith("." + field.getName())) continue;
                return qualifierInstancesToAnnotations(ip.qualifiers());
            }
        }
        return extractFieldQualifiers(field);
    }

    private static java.lang.annotation.Annotation[] qualifierInstancesToAnnotations(
            java.util.Set<fr.vidocq.vauban.core.bean.model.QualifierInstance> qualifierInstances) {
        var annotations = new java.util.ArrayList<java.lang.annotation.Annotation>();
        for (var qi : qualifierInstances) {
            var name = qi.annotationName().value();
            if (name.equals("jakarta.enterprise.inject.Default")) {
                annotations.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
            } else if (name.equals("jakarta.enterprise.inject.Any")) {
                annotations.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
            } else if (name.equals("jakarta.inject.Named")) {
                var nameValue = qi.members().get("value");
                var strValue = nameValue instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal sv
                        ? sv.value() : "";
                annotations.add(jakarta.enterprise.inject.literal.NamedLiteral.of(strValue));
            } else {
                try {
                    @SuppressWarnings("unchecked")
                    var annType = (Class<? extends java.lang.annotation.Annotation>)
                            Thread.currentThread().getContextClassLoader().loadClass(name);
                    annotations.add(createQualifierAnnotation(annType, qi.members()));
                } catch (ClassNotFoundException e) {
                    // Skip unloadable qualifier
                }
            }
        }
        if (annotations.isEmpty()) {
            annotations.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return annotations.toArray(new java.lang.annotation.Annotation[0]);
    }

    @SuppressWarnings("unchecked")
    private static <A extends java.lang.annotation.Annotation> A createQualifierAnnotation(
            Class<A> annType, java.util.Map<String, fr.vidocq.vauban.indexer.model.AnnotationValue> members) {
        return (A) java.lang.reflect.Proxy.newProxyInstance(
                annType.getClassLoader(),
                new Class<?>[]{annType},
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) return annType;
                    if ("toString".equals(method.getName())) return "@" + annType.getName();
                    if ("hashCode".equals(method.getName())) return 0;
                    if ("equals".equals(method.getName())) {
                        if (args[0] == null) return false;
                        if (!annType.isInstance(args[0])) return false;
                        // Compare all member values
                        for (var m : annType.getDeclaredMethods()) {
                            var expected = members.get(m.getName());
                            var actual = m.invoke(args[0]);
                            if (expected != null) {
                                var expectedVal = annotationValueToObject(expected);
                                if (!java.util.Objects.deepEquals(expectedVal, actual)) return false;
                            }
                        }
                        return true;
                    }
                    // Return member value if present
                    var memberVal = members.get(method.getName());
                    if (memberVal != null) {
                        return annotationValueToObject(memberVal);
                    }
                    // Return default value
                    return method.getDefaultValue();
                });
    }

    private static Object annotationValueToObject(fr.vidocq.vauban.indexer.model.AnnotationValue value) {
        return switch (value) {
            case fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal sv -> sv.value();
            case fr.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal bv -> bv.value();
            case fr.vidocq.vauban.indexer.model.AnnotationValue.IntVal iv -> iv.value();
            case fr.vidocq.vauban.indexer.model.AnnotationValue.LongVal lv -> lv.value();
            case fr.vidocq.vauban.indexer.model.AnnotationValue.DoubleVal dv -> dv.value();
            case fr.vidocq.vauban.indexer.model.AnnotationValue.FloatVal fv -> fv.value();
            default -> null;
        };
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
        // CDI 4.1 Section 2.3.5: @Default if no qualifier declared.
        // @Any is NOT added — getBeans() handles implicit @Any matching.
        if (!hasAnyAnnotation) {
            qualifiers.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return qualifiers.toArray(new java.lang.annotation.Annotation[0]);
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx,
            java.lang.annotation.Annotation[] qualifiers, java.lang.reflect.Member member,
            jakarta.enterprise.inject.spi.Bean<?> ownerBean, java.lang.reflect.Parameter param, int paramPosition) {
        if (paramType == Event.class) {
            VaubanInjectionPoint eventIp;
            if (param != null && member instanceof java.lang.reflect.Executable exec) {
                eventIp = new VaubanInjectionPoint(param, paramPosition, exec, genericType,
                        collectQualifierSet(qualifiers), ownerBean);
            } else if (member != null) {
                eventIp = new VaubanInjectionPoint(genericType, collectQualifierSet(qualifiers), ownerBean, member);
            } else {
                eventIp = null;
            }
            return new EventImpl<>(eventDispatcher, qualifiers, eventIp);
        }
        if (paramType == Instance.class || paramType == jakarta.inject.Provider.class) {
            Class<?> instanceType = Object.class;
            if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                var typeArg = pt.getActualTypeArguments()[0];
                if (typeArg instanceof Class<?> c) instanceType = c;
            }

            var qualSet = new java.util.HashSet<>(java.util.Arrays.asList(qualifiers));
            if (qualSet.isEmpty()) qualSet.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);

            var targetType = genericType;
            if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                targetType = pt.getActualTypeArguments()[0];
            }
            VaubanInjectionPoint ip;
            if (param != null && member instanceof java.lang.reflect.Executable exec) {
                ip = new VaubanInjectionPoint(param, paramPosition, exec, targetType, qualSet, ownerBean);
            } else {
                ip = new VaubanInjectionPoint(targetType, qualSet, ownerBean, member);
            }

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
            // CDI spec: InjectionPoint is null for beans not being injected (programmatic lookup)
            return currentInjectionPoint.get();
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
             VaubanInjectionPoint depIp;
             if (param != null && member instanceof java.lang.reflect.Executable exec) {
                 depIp = new VaubanInjectionPoint(param, paramPosition, exec, genericType, qualSet, ownerBean);
             } else {
                 depIp = new VaubanInjectionPoint(genericType, qualSet, ownerBean, member);
             }
             currentInjectionPoint.set(depIp);
        }
        try {
            var pCtx = (ctx != null && resolved.getScope() == jakarta.enterprise.context.Dependent.class)
                    ? ctx
                    : bm.createCreationalContext(resolved);
            var value = bm.getReference(resolved, genericType, pCtx);
            if (value == null && paramType.isPrimitive()) {
                return primitiveDefault(paramType);
            }
            return value;
        } finally {
            currentInjectionPoint.set(previousIp);
        }
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx, java.lang.annotation.Annotation[] qualifiers, java.lang.reflect.Member member) {
        return resolveParameter(paramType, genericType, ctx, qualifiers, member, null, null, -1);
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx, java.lang.annotation.Annotation[] qualifiers) {
        return resolveParameter(paramType, genericType, ctx, qualifiers, null, null, null, -1);
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx) {
        return resolveParameter(paramType, genericType, ctx, new java.lang.annotation.Annotation[0], null, null, null, -1);
    }

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType) {
        return resolveParameter(paramType, genericType, null, new java.lang.annotation.Annotation[0], null, null, null, -1);
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
                    // Find the managed bean for the exact declaring class
                    ManagedBean<?> declBean = findManagedBeanByClass(descriptor.beanClass());
                    boolean isDependent = declBean != null
                            && declBean.getScope() == jakarta.enterprise.context.Dependent.class;
                    // Use selectByBeanClass (has cycle guard) to avoid StackOverflowError
                    var declaringInstance = selectByBeanClass(declaringClass);
                    try {
                        var field = declaringClass.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        return field.get(declaringInstance);
                    } finally {
                        // CDI spec: @Dependent declaring bean must be destroyed after producer field access
                        if (isDependent && declaringInstance != null) {
                            @SuppressWarnings("unchecked")
                            var castBean = (ManagedBean<Object>) (ManagedBean<?>) declBean;
                            castBean.destroy(declaringInstance,
                                    new fr.vidocq.vauban.core.context.CreationalContextImpl<>());
                        }
                    }
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
        private java.util.function.BiFunction<String, byte[], Class<?>> classDefiner;
        private ClassLoader classLoader;
        private boolean isBeanArchive = true;

        public Builder beanArchive(boolean isBeanArchive) {
            this.isBeanArchive = isBeanArchive;
            return this;
        }

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
        /**
         * Set a custom class definer for dynamically generated classes (e.g., interceptor subclasses).
         * The function takes (className, bytecode) and returns the defined Class.
         */
        public Builder classDefiner(java.util.function.BiFunction<String, byte[], Class<?>> definer) {
            this.classDefiner = definer;
            return this;
        }

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

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

            // Set TCCL to the bean class's ClassLoader so BeanDiscovery can
            // resolve inherited annotations via reflection on TCK archive classes
            var previousCl = Thread.currentThread().getContextClassLoader();
            ClassLoader discoveryClassLoader = !beanClasses.isEmpty()
                    ? beanClasses.getFirst().getClassLoader()
                    : Thread.currentThread().getContextClassLoader();
            if (!beanClasses.isEmpty()) {
                Thread.currentThread().setContextClassLoader(discoveryClassLoader);
            }

            // --- @Discovery phase (BCE) — runs BEFORE bean discovery ---
            var bceClasses = beanClasses.stream()
                    .filter(c -> isBuildCompatibleExtension(c))
                    .toList();

            fr.vidocq.vauban.core.extensions.BceProcessor.DiscoveryResult discoveryResult = null;
            if (!bceClasses.isEmpty()) {
                var tempIndex = indexBuilder.build();
                var tempLookup = new fr.vidocq.vauban.core.langmodel.IndexLookup(tempIndex);
                discoveryResult = fr.vidocq.vauban.core.extensions.BceProcessor.processDiscovery(bceClasses, tempLookup);

                // Add scanned classes to the index
                for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
                    try {
                        var cls = Class.forName(className, false, discoveryClassLoader);
                        String resource = className.replace('.', '/') + ".class";
                        try (var is = discoveryClassLoader.getResourceAsStream(resource)) {
                            if (is != null) {
                                indexBuilder.add(fr.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
                            }
                        }
                        if (!factories.containsKey(DotName.of(className))) {
                            factories.put(DotName.of(className), () -> {
                                try {
                                    var ctor = cls.getDeclaredConstructor();
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
                    } catch (Exception e) {
                        // Class not found — skip
                    }
                }
            }

            var index = indexBuilder.build();

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
                // Exclude BCE classes from validation (they are not beans)
                var nonBceClasses = beanClasses.stream()
                        .filter(c -> !isBuildCompatibleExtension(c))
                        .toList();
                var reflectionErrors = validateWithReflection(nonBceClasses);
                if (!reflectionErrors.isEmpty()) {
                    var msg = new StringBuilder("CDI definition validation failed:\n");
                    for (var error : reflectionErrors) {
                        msg.append("  - ").append(error).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
                }

                var discovery = new BeanDiscovery(index);

                // Apply @Discovery results to BeanDiscovery
                if (discoveryResult != null) {
                    var meta = discoveryResult.metaAnnotations();
                    discovery.setCustomQualifiers(
                            meta.getCustomQualifiers().stream()
                                    .map(c -> DotName.of(c.getName()))
                                    .collect(java.util.stream.Collectors.toSet()));
                    discovery.setCustomInterceptorBindings(
                            meta.getCustomInterceptorBindings().stream()
                                    .map(c -> DotName.of(c.getName()))
                                    .collect(java.util.stream.Collectors.toSet()));
                    discovery.setCustomStereotypes(
                            meta.getCustomStereotypes().stream()
                                    .map(c -> DotName.of(c.getName()))
                                    .collect(java.util.stream.Collectors.toSet()));
                    var stereotypeAnns = new java.util.HashMap<DotName, java.util.Set<Class<? extends java.lang.annotation.Annotation>>>();
                    for (var entry : meta.getStereotypeAnnotations().entrySet()) {
                        stereotypeAnns.put(DotName.of(entry.getKey().getName()), entry.getValue());
                    }
                    discovery.setCustomStereotypeAnnotations(stereotypeAnns);
                    discovery.setCustomNonbindingMembers(meta.getNonbindingMembersPerQualifier());
                    fr.vidocq.vauban.core.bean.resolution.QualifierMatcher.setCustomNonbindingMembers(
                            meta.getNonbindingMembersPerQualifier());
                    VaubanBeanManager.setCustomQualifierTypes(meta.getCustomQualifiers());
                    VaubanBeanManager.setCustomInterceptorBindingTypes(meta.getCustomInterceptorBindings());
                    VaubanBeanManager.setCustomStereotypeTypes(meta.getCustomStereotypes());

                    // Classes added via ScannedClasses bypass annotation check
                    if (!discoveryResult.scannedClasses().getAddedClasses().isEmpty()) {
                        var scannedDotNames = discoveryResult.scannedClasses().getAddedClasses().stream()
                                .map(DotName::of)
                                .collect(java.util.stream.Collectors.toSet());
                        discovery.setForcedBeanClasses(scannedDotNames);
                        // When not a bean archive, also restrict discovery to scanned classes only
                        if (!isBeanArchive) {
                            discovery.setScannedClassesFilter(scannedDotNames);
                        }
                    }
                }
                var descriptors = new ArrayList<>(discovery.discoverBeans());
                var observers = new ArrayList<>(discovery.discoverObservers());
                var interceptors = discovery.discoverInterceptors();
                var disposers = discovery.discoverDisposerMethods();
                var syntheticDisposers = new LinkedHashMap<DotName, java.util.function.Consumer<Object>>();

                // --- Build Compatible Extensions (BCE) — remaining phases ---
                if (!bceClasses.isEmpty()) {
                    var bceResult = fr.vidocq.vauban.core.extensions.BceProcessor.process(
                            bceClasses, descriptors, observers, interceptors, index,
                            beanClasses.isEmpty() ? Thread.currentThread().getContextClassLoader()
                                    : beanClasses.getFirst().getClassLoader(),
                            discoveryResult != null ? discoveryResult.bceInstances() : null,
                            nonBceClasses);

                    // BCE definition errors → DefinitionException
                    if (!bceResult.definitionErrors().isEmpty()) {
                        var msg = new StringBuilder("CDI definition validation failed:\n");
                        for (var error : bceResult.definitionErrors()) {
                            msg.append("  - ").append(error).append("\n");
                        }
                        throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
                    }

                    // BCE deployment errors → DeploymentException
                    if (!bceResult.deploymentErrors().isEmpty()) {
                        var msg = new StringBuilder("CDI deployment validation failed:\n");
                        for (var error : bceResult.deploymentErrors()) {
                            msg.append("  - ").append(error).append("\n");
                        }
                        throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                    }

                    // Register synthetic beans
                    for (var synBean : bceResult.syntheticBeans()) {
                        registerSyntheticBean(synBean, descriptors, factories, syntheticDisposers);
                    }

                    // Register synthetic observers
                    for (var synObs : bceResult.syntheticObservers()) {
                        observers.add(buildSyntheticObserver(synObs));
                    }

                    // Apply enhancement modifications to bean descriptors
                    if (!bceResult.enhancementModifications().isEmpty()) {
                        var modified = fr.vidocq.vauban.core.extensions.BceProcessor.applyEnhancements(
                                descriptors, bceResult.enhancementModifications(), index);
                        descriptors.clear();
                        descriptors.addAll(modified);

                        // Apply enhancement modifications to interceptor descriptors (e.g. @Priority)
                        interceptors = new ArrayList<>(fr.vidocq.vauban.core.extensions.BceProcessor.applyInterceptorEnhancements(
                                interceptors, bceResult.enhancementModifications()));

                        // Apply enhancement modifications to observer descriptors (parameter qualifier changes)
                        var modifiedObservers = fr.vidocq.vauban.core.extensions.BceProcessor.applyObserverEnhancements(
                                observers, bceResult.enhancementModifications());
                        observers.clear();
                        observers.addAll(modifiedObservers);
                    }
                }

                // Validate observer/disposer method parameters (CDI spec)
                validateObserverParameters(observers, descriptors, index);
                validateDisposerParameters(disposers, descriptors, index);
                // Validate disposer method definitions (CDI 4.1 Section 3.5)
                validateDisposerDefinitions(disposers, descriptors);


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

                var beanClassLoader = this.classLoader != null 
                        ? this.classLoader
                        : (beanClasses.isEmpty()
                        ? Thread.currentThread().getContextClassLoader()
                        : beanClasses.getFirst().getClassLoader());
                var container = new VaubanContainer(index, descriptors, observers, interceptors, disposers, factories, syntheticDisposers, beanClassLoader, classDefiner);

                // Register custom contexts from Build Compatible Extensions
                if (discoveryResult != null) {
                    for (var reg : discoveryResult.metaAnnotations().getCustomContexts()) {
                        try {
                            var ctor = reg.contextClass().getDeclaredConstructor();
                            ctor.setAccessible(true);
                            var ctx = (jakarta.enterprise.context.spi.Context) ctor.newInstance();
                            container.contexts.computeIfAbsent(reg.scopeAnnotation(), k -> new java.util.ArrayList<>()).add(ctx);
                        } catch (Exception e) {
                            // Skip context if instantiation fails
                        }
                    }
                }

                return container;
            } finally {
                Thread.currentThread().setContextClassLoader(previousCl);
            }
        }

        /**
         * Validates disposer method definitions (CDI 4.1 Section 3.5).
         * - Each disposer must match at least one producer bean in the same declaring class
         * - Multiple disposers for the same producer in the same class are DefinitionException
         */
        private static void validateDisposerDefinitions(
                List<DisposerDescriptor> disposers,
                List<BeanDescriptor> descriptors) {
            var producerBeans = descriptors.stream()
                    .filter(d -> d.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                            || d.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD)
                    .toList();

            // CDI spec: Each disposer must have a matching producer in the same bean class
            for (var disposer : disposers) {
                boolean found = false;
                for (var producer : producerBeans) {
                    // Disposer must be in the same declaring class as the producer
                    if (!producer.beanClass().equals(disposer.declaringClass())
                            && !producerDeclaredIn(producer, disposer.declaringClass())) {
                        continue;
                    }
                    if (disposerMatchesProducerType(disposer, producer)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // Only throw if the disposer is in a bean class (not a non-bean utility class)
                    boolean isInBeanClass = descriptors.stream()
                            .anyMatch(d -> d.beanClass().equals(disposer.declaringClass())
                                    && d.kind() == BeanDescriptor.BeanKind.MANAGED);
                    if (isInBeanClass) {
                        throw new jakarta.enterprise.inject.spi.DefinitionException(
                                "Disposer method " + disposer.declaringClass().value() + "." + disposer.methodName()
                                        + "(): no matching producer found for disposed type " + disposer.disposedType());
                    }
                }
            }

        }

        private static boolean producerDeclaredIn(BeanDescriptor producer, DotName declaringClass) {
            // Check if the producer's ID references this declaring class
            return producer.id().value().startsWith(declaringClass.value());
        }

        private static boolean disposerMatchesProducerType(DisposerDescriptor disposer, BeanDescriptor producer) {
            for (var producerType : producer.types()) {
                if (producerType instanceof TypeInfo.ClassType ct
                        && disposer.disposedType() instanceof TypeInfo.ClassType dt
                        && ct.name().equals(dt.name())) {
                    return true;
                }
                if (producerType.equals(disposer.disposedType())) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static ObserverDescriptor buildSyntheticObserver(
                fr.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder<?> synObs) {
            var eventReflectType = synObs.getEventType();
            TypeInfo eventTypeInfo;
            if (eventReflectType instanceof Class<?> cls) {
                eventTypeInfo = new TypeInfo.ClassType(DotName.of(cls.getName()));
            } else if (eventReflectType instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getRawType() instanceof Class<?> rawCls) {
                var typeArgs = new java.util.ArrayList<TypeInfo>();
                for (var arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> argCls) {
                        typeArgs.add(new TypeInfo.ClassType(DotName.of(argCls.getName())));
                    } else {
                        typeArgs.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
                    }
                }
                eventTypeInfo = new TypeInfo.ParameterizedType(DotName.of(rawCls.getName()), typeArgs);
            } else {
                eventTypeInfo = new TypeInfo.ClassType(DotName.of("java.lang.Object"));
            }

            var qualifiers = new java.util.ArrayList<QualifierInstance>();
            for (var q : synObs.getQualifiers()) {
                var qName = DotName.of(q.annotationType().getName());
                qualifiers.add(new QualifierInstance(qName, java.util.Map.of()));
            }

            var observerClass = synObs.getObserverClass();
            var params = synObs.getParams();

            java.util.function.BiConsumer<Object, java.lang.annotation.Annotation[]> invoker = (event, eventQualifiers) -> {
                try {
                    var observer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver) observerClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(params);
                    var metadata = new jakarta.enterprise.inject.spi.EventMetadata() {
                        @Override public java.util.Set<java.lang.annotation.Annotation> getQualifiers() {
                            var qs = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
                            if (eventQualifiers != null) {
                                for (var q : eventQualifiers) qs.add(q);
                            }
                            qs.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
                            return java.util.Set.copyOf(qs);
                        }
                        @Override public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() { return null; }
                        @Override public java.lang.reflect.Type getType() { return event.getClass(); }
                    };
                    var eventContext = new jakarta.enterprise.inject.spi.EventContext() {
                        @Override public Object getEvent() { return event; }
                        @Override public jakarta.enterprise.inject.spi.EventMetadata getMetadata() { return metadata; }
                    };
                    observer.observe(eventContext, vaubanParams);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new jakarta.enterprise.event.ObserverException("Synthetic observer failed", e);
                }
            };

            return new ObserverDescriptor(
                    DotName.of(observerClass.getName()),
                    "observe",
                    eventTypeInfo,
                    qualifiers,
                    synObs.isAsync(),
                    synObs.getPriority(),
                    "ALWAYS",
                    "IN_PROGRESS",
                    invoker
            );
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void registerSyntheticBean(
                fr.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?> synBean,
                List<BeanDescriptor> descriptors,
                Map<DotName, BeanFactory<?>> factories,
                Map<DotName, java.util.function.Consumer<Object>> syntheticDisposers) {
            var beanClass = synBean.getBeanClass();
            var beanName = DotName.of(beanClass.getName());

            // Build bean types from the builder's types
            var beanTypes = new java.util.LinkedHashSet<TypeInfo>();
            for (var type : synBean.getTypes()) {
                if (type instanceof Class<?> cls) {
                    beanTypes.add(new TypeInfo.ClassType(DotName.of(cls.getName())));
                }
            }
            if (beanTypes.isEmpty()) {
                beanTypes.add(new TypeInfo.ClassType(beanName));
                beanTypes.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
            }

            // Determine scope
            var scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT;
            if (synBean.getScopeAnnotation() != null) {
                var scopeAnn = synBean.getScopeAnnotation();
                if (scopeAnn == jakarta.enterprise.context.ApplicationScoped.class) {
                    scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.APPLICATION;
                } else if (scopeAnn == jakarta.enterprise.context.RequestScoped.class) {
                    scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.REQUEST;
                } else if (scopeAnn == jakarta.inject.Singleton.class) {
                    scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.SINGLETON;
                }
            }

            // Build qualifiers from builder's qualifier set
            var qualifiers = new java.util.LinkedHashSet<QualifierInstance>();
            boolean hasExplicitQualifier = false;
            for (var q : synBean.getQualifiers()) {
                var qName = DotName.of(q.annotationType().getName());
                if (!qName.equals(QualifierInstance.DEFAULT_NAME) && !qName.equals(QualifierInstance.ANY_NAME)) {
                    hasExplicitQualifier = true;
                }
                qualifiers.add(new QualifierInstance(qName, java.util.Map.of()));
            }
            if (!hasExplicitQualifier) {
                qualifiers.add(QualifierInstance.DEFAULT);
            }
            qualifiers.add(QualifierInstance.ANY);

            // Use unique key to avoid collisions when multiple synthetic beans share the same type
            var syntheticKey = DotName.of(beanName.value() + "#synthetic#" + descriptors.size());
            var descriptor = new BeanDescriptor(
                    new BeanId(syntheticKey.value()),
                    beanName,
                    BeanDescriptor.BeanKind.SYNTHETIC,
                    beanTypes,
                    qualifiers,
                    scope,
                    synBean.isAlternative(),
                    synBean.getPriority(),
                    List.of(),
                    synBean.getName()
            );
            descriptors.add(descriptor);

            // Create factory using SyntheticBeanCreator
            var creatorClass = synBean.getCreatorClass();
            var creatorParams = synBean.getParams();
            var isDependent = scope.equals(fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT);
            factories.put(syntheticKey, new BeanFactory<Object>() {
                @Override
                public Object create() { return create((CreationalContext<Object>) null); }
                @Override
                public Object create(CreationalContext<Object> ctx) {
                    try {
                        @SuppressWarnings("unchecked")
                        var creator = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator<Object>)
                                creatorClass.getDeclaredConstructor().newInstance();
                        var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                        var container = VaubanContainer.current();
                        var previousIp = VaubanContainer.getCurrentInjectionPoint();
                        if (previousIp == null && isDependent) {
                            VaubanContainer.setInjectionPoint(VaubanInjectionPoint.EMPTY);
                        }
                        try {
                            var parentCtx = ctx instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> cci ? cci : null;
                            var lookup = new InstanceImpl<>(container, Object.class, new Annotation[0], null, parentCtx);
                            return creator.create(lookup, vaubanParams);
                        } finally {
                            if (previousIp == null && isDependent) {
                                VaubanContainer.setInjectionPoint(previousIp);
                            }
                        }
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new jakarta.enterprise.inject.CreationException(e);
                    }
                }
            });

            // Register synthetic disposer if present
            var disposerClass = synBean.getDisposerClass();
            if (disposerClass != null) {
                syntheticDisposers.put(syntheticKey, instance -> {
                    try {
                        @SuppressWarnings("unchecked")
                        var disposer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer<Object>)
                                disposerClass.getDeclaredConstructor().newInstance();
                        var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                        var container = VaubanContainer.current();
                        var lookup = new InstanceImpl<>(container, Object.class);
                        disposer.dispose(instance, lookup, vaubanParams);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        // CDI spec: exceptions in disposer methods are suppressed
                    }
                });
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

    /**
     * Check if a class implements BuildCompatibleExtension, using interface name
     * comparison to avoid ClassLoader issues.
     */
    private static boolean isBuildCompatibleExtension(Class<?> clazz) {
        return implementsInterface(clazz, "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension");
    }

    private static boolean implementsInterface(Class<?> clazz, String interfaceName) {
        if (clazz == null || clazz == Object.class) return false;
        for (var iface : clazz.getInterfaces()) {
            if (iface.getName().equals(interfaceName)) return true;
            if (implementsInterface(iface, interfaceName)) return true;
        }
        return implementsInterface(clazz.getSuperclass(), interfaceName);
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

    /**
     * Attempts to load a generated proxy or interceptor subclass. If it does not exist, defines it.
     * 
     * NOTE ON THE USE OF sun.misc.Unsafe:
     * This fallback mechanism is essential for passing the CDI TCK in Arquillian/Surefire environments.
     * When running the TCK (e.g., InterceptorLifeCycleTest), tests are loaded by the AppClassLoader,
     * but they are often wrapped in ShrinkWrap deployments. Using `MethodHandles.lookup().defineClass`
     * can fail with IllegalAccessException due to module boundary restrictions (missing "opens" directives
     * to the unnamed module or between modules) when trying to define the proxy in the exact same package 
     * and classloader as the target bean.
     * By using Unsafe (or the trusted MethodHandles.Lookup.IMPL_LOOKUP), we bypass these module 
     * restrictions, ensuring that the proxy class is defined correctly in the target classloader,
     * preventing DeploymentExceptions related to class definition in tests.
     */
    private Class<?> loadOrDefineClassRobustly(Class<?> targetClass, String className, byte[] bytecode) throws Exception {
        try {
            return targetClass.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException cnfe) {
            try {
                var lookup = java.lang.invoke.MethodHandles.privateLookupIn(targetClass, java.lang.invoke.MethodHandles.lookup());
                return lookup.defineClass(bytecode);
            } catch (IllegalAccessException e) {
                try {
                    // Unsafe fallback for modules that don't open packages to Vauban (like Arquillian/TestNG tests)
                    java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                    f.setAccessible(true);
                    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
                    
                    java.lang.reflect.Field implLookupField = java.lang.invoke.MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                    long offset = unsafe.staticFieldOffset(implLookupField);
                    java.lang.invoke.MethodHandles.Lookup trustedLookup = (java.lang.invoke.MethodHandles.Lookup) unsafe.getObject(java.lang.invoke.MethodHandles.Lookup.class, offset);
                    
                    return trustedLookup.in(targetClass).defineClass(bytecode);
                } catch (Exception unsafeEx) {
                    e.addSuppressed(unsafeEx);
                    throw e;
                }
            }
        }
    }
}
