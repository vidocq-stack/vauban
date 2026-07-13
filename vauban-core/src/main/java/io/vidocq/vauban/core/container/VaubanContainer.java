/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.BeanFactory;
import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.DisposerDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.interceptor.InterceptorManager;
import io.vidocq.vauban.core.context.ApplicationContext;
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.context.DependentContext;
import io.vidocq.vauban.core.context.RequestContext;
import io.vidocq.vauban.core.event.EventDispatcher;
import io.vidocq.vauban.core.event.EventImpl;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
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

    private static volatile VaubanContainer currentInstance;

    /**
     * Returns the currently running container instance, or {@code null} if none.
     */
    public static VaubanContainer current() {
        return currentInstance;
    }

    static final ScopedValue<InjectionPoint> CURRENT_INJECTION_POINT = ScopedValue.newInstance();

    /**
     * Returns the current injection point (used by built-in InjectionPoint bean).
     */
    static InjectionPoint getCurrentInjectionPoint() {
        return CURRENT_INJECTION_POINT.isBound() ? CURRENT_INJECTION_POINT.get() : null;
    }

    /**
     * Runs an action with the given injection point bound.
     */
    static void withInjectionPoint(InjectionPoint ip, Runnable action) {
        ScopedValue.where(CURRENT_INJECTION_POINT, ip).run(action);
    }

    /**
     * Calls an action with the given injection point bound and returns its result.
     */
    @SuppressWarnings("unchecked")
    static <T> T callWithInjectionPoint(InjectionPoint ip, ScopedValue.CallableOp<T, Exception> action) throws Exception {
        try {
            return ScopedValue.where(CURRENT_INJECTION_POINT, ip).call(action);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    final Map<BeanId, ManagedBean<?>> beans = new LinkedHashMap<>();
    final Map<Class<? extends Annotation>, List<Context>> contexts = new ConcurrentHashMap<>();
    private static final ScopedValue<Set<String>> BEANS_BEING_CREATED = ScopedValue.newInstance();
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
    private final VaubanLookup vaubanLookup;
    private final ComponentProviders componentProviders;
    final BeanLifecycle beanLifecycle;
    final DisposerInvoker disposerInvoker;
    private final BeanInjector beanInjector;
    private InterceptorBeanWrapper interceptorWrapper;
    private volatile boolean running;

    public VaubanLookup getVaubanLookup() {
        return vaubanLookup;
    }

    DependentContext dependentContext() {
        return dependentContext;
    }

    BeanInjector beanInjector() {
        return beanInjector;
    }

    ComponentProviders componentProviders() {
        return componentProviders;
    }

    VaubanContainer(VaubanIndex index, List<BeanDescriptor> descriptors,
                            List<ObserverDescriptor> observers,
                            List<InterceptorDescriptor> interceptorDescriptors,
                            List<DisposerDescriptor> disposers,
                            Map<DotName, BeanFactory<?>> factories,
                            Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
                            ClassLoader classLoader,
                            java.util.function.BiFunction<String, byte[], Class<?>> classDefiner,
                            VaubanLookup vaubanLookup,
                            ComponentProviders componentProviders) {
        this.index = index;
        this.classLoader = classLoader;
        this.classDefiner = classDefiner;
        this.vaubanLookup = vaubanLookup;
        this.componentProviders = componentProviders == null
                ? new ComponentProviders(List.of()) : componentProviders;
        // Let reflective method invocation consult the in-module providers first (producers,
        // disposers, lifecycle, initializers go through VaubanLookup.invokeMethod).
        vaubanLookup.setComponentProviders(this.componentProviders);
        this.disposerInvoker = new DisposerInvoker(this, vaubanLookup);
        this.beanInjector = new BeanInjector(this, vaubanLookup);
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
                beans.put(descriptor.id(), new ManagedBean<>(descriptor, factory, classLoader, vaubanLookup));
            }
        }

        var assignability = new AssignabilityRules(index);
        this.resolver = new BeanResolver(descriptors, interceptorDescriptors, assignability);
        this.eventDispatcher = new EventDispatcher(observers, this);
        this.interceptorManager = new InterceptorManager(interceptorDescriptors);
        this.interceptorManager.setVaubanLookup(vaubanLookup);
        this.beanLifecycle = new BeanLifecycle(interceptorManager, vaubanLookup);
        this.interceptorWrapper = new InterceptorBeanWrapper(this, vaubanLookup, interceptorManager, classLoader, classDefiner);
        this.interceptorManager.setInstanceFactory((descriptor, ctx) -> interceptorWrapper.getOrCreateInterceptorInstance(descriptor, ctx));

        // Wrap intercepted beans with generated subclasses
        interceptorWrapper.wrapInterceptedBeans(descriptors, factories);


        // Wire up field injection on each bean (includes PostConstruct in injectFields)
        for (var bean : beans.values()) {
            bean.setInjector((instance, ctx) -> injectFields(instance, bean.descriptor(), ctx));
        }

        // Wire up disposer methods for producer beans
        disposerInvoker.wireDisposers(descriptors, disposers);

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


    @SuppressWarnings("unchecked")
    public <T> T select(Class<T> type) {
        // CDI spec: primitive types and their wrappers are considered identical
        var lookupType = type.isPrimitive() ? wrapPrimitive(type) : type;
        // Array classes must map to the index model's ArrayType — a flat
        // ClassType("[Ljava.lang.Class;") can never match a bean's array type.
        // Cf. VAU-BCE-004.
        var typeInfo = lookupType.isArray()
                ? io.vidocq.vauban.core.types.TypeInfoUtils.fromReflectType(lookupType)
                : new TypeInfo.ClassType(DotName.of(lookupType.getName()));
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

        // VAU-INJ-001: for normal-scoped beans, always return the client proxy — never call
        // context.get() eagerly here.  The context may be inactive at lookup time (e.g.
        // @TransactionScoped before any TX, @RequestScoped outside a request) and the
        // no-arg context.get() on such contexts throws ContextNotActiveException.
        // The proxy's delegate resolves the contextual instance lazily on first method call.
        if (finalBean.descriptor().scope().isNormal()) {
            return interceptorWrapper.getOrCreateProxy(finalBean);
        }

        var scopeClass = finalBean.getScope();
        var context = getFirstContext(scopeClass);
        if (context == null) {
            context = dependentContext;
        }

        // Return existing instance from context if already created
        var existing = context.get((Contextual<T>) finalBean);
        if (existing != null) return existing;

        CreationalContext<T> creationalCtx = new CreationalContextImpl<T>();
        return context.get((Contextual<T>) finalBean, creationalCtx);
    }

    @SuppressWarnings("unchecked")
    <T> T getOrCreateProxyForBean(ManagedBean<T> bean) {
        return interceptorWrapper.getOrCreateProxy(bean);
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
    static void validateObserverParameters(
            java.util.List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
            java.util.List<io.vidocq.vauban.core.bean.model.BeanDescriptor> descriptors,
            io.vidocq.vauban.indexer.VaubanIndex index) {
        var assignability = new io.vidocq.vauban.core.types.AssignabilityRules(index);
        var tempResolver = new io.vidocq.vauban.core.bean.resolution.BeanResolver(
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
                        var ip = new io.vidocq.vauban.core.bean.model.InjectionPointInfo(
                                new io.vidocq.vauban.indexer.model.TypeInfo.ClassType(
                                        io.vidocq.vauban.indexer.model.DotName.of(paramType.getName())),
                                java.util.Set.of(new io.vidocq.vauban.core.bean.model.QualifierInstance(
                                        io.vidocq.vauban.indexer.model.DotName.of("jakarta.enterprise.inject.Default"),
                                        java.util.Map.of()),
                                        new io.vidocq.vauban.core.bean.model.QualifierInstance(
                                        io.vidocq.vauban.indexer.model.DotName.of("jakarta.enterprise.inject.Any"),
                                        java.util.Map.of())),
                                io.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                                "observer parameter " + param.getName());
                        var result = tempResolver.resolveInjectionPoint(ip);
                        if (result.status() == io.vidocq.vauban.core.bean.resolution.BeanResolver.ResolutionResult.Status.UNSATISFIED) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException(
                                    "Observer method " + observer.declaringClass().simpleName() + "." + observer.methodName()
                                            + "(): unsatisfied dependency for parameter " + param.getName()
                                            + " of type " + paramType.getName());
                        } else if (result.status() == io.vidocq.vauban.core.bean.resolution.BeanResolver.ResolutionResult.Status.AMBIGUOUS) {
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
        var creating = BEANS_BEING_CREATED.isBound() ? BEANS_BEING_CREATED.get() : null;
        if (creating != null && creating.contains(className)) {
            return null;
        }
        // Ensure a mutable set is bound for cycle detection
        if (creating == null) {
            creating = new java.util.HashSet<>();
        }
        creating.add(className);
        final var currentCreating = creating;
        ScopedValue.CallableOp<Object, RuntimeException> lookup = () -> {
            var dotName = DotName.of(className);
            try {
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
                currentCreating.remove(className);
            }
        };
        if (!BEANS_BEING_CREATED.isBound()) {
            return ScopedValue.where(BEANS_BEING_CREATED, currentCreating).call(lookup);
        }
        return lookup.call();
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

    Context getFirstContext(Class<? extends Annotation> scopeType) {
        var list = contexts.get(scopeType);
        return (list != null && !list.isEmpty()) ? list.getFirst() : null;
    }

    public InterceptorManager interceptorManager() {
        return interceptorManager;
    }

    void injectFields(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        // 1. + 2. @Inject fields and initializer methods, in JSR-330 supertype-before-subtype
        // order with override resolution (fields of a class before its methods).
        beanInjector.performInjection(instance, descriptor, parentCtx);

        // 3. Call @PostConstruct
        callPostConstruct(instance, descriptor, parentCtx);

        // 4. CDI spec 9.3: eagerly create all interceptor instances for this bean
        interceptorWrapper.eagerCreateInterceptors(instance, descriptor, parentCtx);
    }

    boolean hasMethodOrClassInterceptors(ManagedBean<?> mb) {
        return interceptorWrapper.hasMethodOrClassInterceptors(mb);
    }

    /**
     * Find the ManagedBean corresponding to the given instance's class.
     */
    ManagedBean<?> findBeanForInstance(Object instance) {
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

    void callPostConstruct(Object instance, BeanDescriptor descriptor, CreationalContext<?> ctx) {
        beanLifecycle.callPostConstruct(instance, descriptor, ctx);
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
            public Object create(io.vidocq.vauban.core.interceptor.VaubanInvocationContext constructCtx, CreationalContext<Object> creationalCtx) {
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
                    var transientCtxs = new java.util.ArrayList<io.vidocq.vauban.core.context.CreationalContextImpl<?>>();
                    for (int i = 0; i < paramTypes.length; i++) {
                        var pQuals = QualifierHelper.extractParamQualifiers(ctorParamsRefl[i]);
                        if (ctorParamsRefl[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)) {
                            var transientCtx = new io.vidocq.vauban.core.context.CreationalContextImpl<>();
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], transientCtx, pQuals, injectCtor, ctorOwnerBean, ctorParamsRefl[i], i);
                            transientCtxs.add(transientCtx);
                        } else {
                            args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], creationalCtx, pQuals, injectCtor, ctorOwnerBean, ctorParamsRefl[i], i);
                        }
                    }

                    final var finalCtor = injectCtor;
                    final var finalArgs = args;

                    // Provider-first: let the owning module run `new X(args…)` in-module (no
                    // opens); fall back to reflective construction when no provider owns it.
                    Object result = componentProviders.create(descriptor.beanClass().value(), finalArgs);
                    if (result == null) {
                        result = vaubanLookup.newInstance(finalCtor, finalArgs);
                    }
                    for (var tc : transientCtxs) {
                        tc.release();
                    }
                    return result;
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
                    // For @Dependent declaring beans, track the CreationalContext so dependents
                    // (like @Inject fields) are properly destroyed when the declaring bean is destroyed
                    var declCtx = new io.vidocq.vauban.core.context.CreationalContextImpl<>();
                    Object declaringInstance;
                    if (isDependent && declBean != null) {
                        @SuppressWarnings("unchecked")
                        var castBean = (ManagedBean<Object>) (ManagedBean<?>) declBean;
                        declaringInstance = castBean.create(declCtx);
                    } else {
                        declaringInstance = selectByBeanClass(declaringClass);
                    }

                    for (var method : declaringClass.getDeclaredMethods()) {
                        if (method.getName().equals(methodName)) {
                            var transientCtx = new io.vidocq.vauban.core.context.CreationalContextImpl<>();
                            try {
                                if (method.getParameterCount() == 0) {
                                    return vaubanLookup.invokeMethod(declaringInstance, method);
                                }
                                var paramTypes = method.getParameterTypes();
                                var genericParamTypes = method.getGenericParameterTypes();
                                var params = method.getParameters();
                                var args = new Object[paramTypes.length];
                                for (int i = 0; i < paramTypes.length; i++) {
                                    var qualifiers = QualifierHelper.extractParamQualifiers(params[i]);
                                    if (params[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)) {
                                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], transientCtx, qualifiers, method);
                                    } else {
                                        args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], ctx != null ? ctx : transientCtx, qualifiers, method);
                                    }
                                }
                                return vaubanLookup.invokeMethod(declaringInstance, method, args);
                            } finally {
                                transientCtx.release();
                                // CDI spec: destroy @Dependent declaring bean after producer method completes
                                if (isDependent && declaringInstance != null) {
                                    @SuppressWarnings("unchecked")
                                    var castBean = (ManagedBean<Object>) (ManagedBean<?>) declBean;
                                    castBean.destroy(declaringInstance, declCtx);
                                }
                            }
                        }
                    }
                    throw new RuntimeException("Producer method not found: " + methodName + " in " + descriptor.beanClass());
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


    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx,
            java.lang.annotation.Annotation[] qualifiers, java.lang.reflect.Member member,
            jakarta.enterprise.inject.spi.Bean<?> ownerBean, java.lang.reflect.Parameter param, int paramPosition) {
        if (paramType == Event.class) {
            VaubanInjectionPoint eventIp;
            if (param != null && member instanceof java.lang.reflect.Executable exec) {
                eventIp = new VaubanInjectionPoint(param, paramPosition, exec, genericType,
                        QualifierHelper.collectQualifierSet(qualifiers), ownerBean);
            } else if (member != null) {
                eventIp = new VaubanInjectionPoint(genericType, QualifierHelper.collectQualifierSet(qualifiers), ownerBean, member);
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
            return getCurrentInjectionPoint();
        }

        var bm = getBeanManager();
        var beansFound = bm.getBeans(genericType, qualifiers);
        if (beansFound.isEmpty()) {
            return select(paramType);
        }
        var resolved = bm.resolve(beansFound);
        if (resolved == null) {
            return select(paramType);
        }

        // Handle InjectionPoint for @Dependent beans
        if (resolved.getScope() == jakarta.enterprise.context.Dependent.class) {
             var qualSet = new java.util.HashSet<>(java.util.Arrays.asList(qualifiers));
             if (qualSet.isEmpty()) qualSet.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
             VaubanInjectionPoint depIp;
             if (param != null && member instanceof java.lang.reflect.Executable exec) {
                 depIp = new VaubanInjectionPoint(param, paramPosition, exec, genericType, qualSet, ownerBean);
             } else {
                 depIp = new VaubanInjectionPoint(genericType, qualSet, ownerBean, member);
             }
             try {
                 return callWithInjectionPoint(depIp, () -> {
                     var pCtx = (ctx != null) ? ctx : bm.createCreationalContext(resolved);
                     var value = bm.getReference(resolved, genericType, pCtx);
                     if (value == null && paramType.isPrimitive()) {
                         return primitiveDefault(paramType);
                     }
                     return value;
                 });
             } catch (RuntimeException e) {
                 throw e;
             } catch (Exception e) {
                 throw new RuntimeException(e);
             }
        }
        var pCtx = bm.createCreationalContext(resolved);
        var value = bm.getReference(resolved, genericType, pCtx);
        if (value == null && paramType.isPrimitive()) {
            return primitiveDefault(paramType);
        }
        return value;
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
                        return vaubanLookup.getField(declaringInstance, field);
                    } finally {
                        // CDI spec: @Dependent declaring bean must be destroyed after producer field access
                        if (isDependent && declaringInstance != null) {
                            @SuppressWarnings("unchecked")
                            var castBean = (ManagedBean<Object>) (ManagedBean<?>) declBean;
                            castBean.destroy(declaringInstance,
                                    new io.vidocq.vauban.core.context.CreationalContextImpl<>());
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
        
        interceptorWrapper.clearProxyCache();

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
    public static VaubanContainerBuilder builder() {
        return new VaubanContainerBuilder();
    }

}
