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
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
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

    /** The running container, set when one boots and cleared, only by that container, when it closes or fails to boot. */
    private static final java.util.concurrent.atomic.AtomicReference<VaubanContainer> CURRENT =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Returns the currently running container instance, or {@code null} if none.
     */
    public static VaubanContainer current() {
        return CURRENT.get();
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
            throw new ContainerOperationException(t);
        }
    }

    final Map<BeanId, ManagedBean<?>> beans = new LinkedHashMap<>();
    final Map<Class<? extends Annotation>, List<Context>> contexts = new ConcurrentHashMap<>();
    private static final ScopedValue<Set<String>> BEANS_BEING_CREATED = ScopedValue.newInstance();
    private final ApplicationContext applicationContext;
    private final RequestContext requestContext;
    private final DependentContext dependentContext;
    private final BeanResolver resolver;
    private final EventDispatcher eventDispatcher;
    private final InterceptorManager interceptorManager;
    private final VaubanBeanManager beanManager;
    private final ClassLoader classLoader;
    private final io.vidocq.vauban.core.bean.resolution.QualifierMatcher qualifierMatcher;
    private final Map<DotName, BeanDescriptor> managedDescriptors;
    private final VaubanLookup vaubanLookup;
    private final ComponentProviders componentProviders;
    final BeanLifecycle beanLifecycle;
    final DisposerInvoker disposerInvoker;
    private final BeanInjector beanInjector;
    private InterceptorBeanWrapper interceptorWrapper;
    private volatile boolean running;
    private io.vidocq.vauban.core.access.ModuleLookups.Registration moduleLookups;

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

    /** The deployment's ModuleLookups registration, withdrawn when this container closes. */
    void withdrawOnClose(io.vidocq.vauban.core.access.ModuleLookups.Registration registration) {
        this.moduleLookups = registration;
    }

    /**
     * Which code generator covers what each bean, observer and interceptor needs, and what falls back to reflection —
     * for diagnostics such as the Vidocq dev console. Asks every provider for its coverage once; creates no bean.
     */
    public CodegenCoverage codegenCoverage() {
        return new CodegenCoverage(this);
    }

    /** Whether the bean {@code id}'s intercepted subclass was pre-generated; {@code null} if it is not intercepted. */
    Boolean interceptedSubclassPreGenerated(BeanId id) {
        return interceptorWrapper == null ? null : interceptorWrapper.interceptedSubclassPreGenerated(id);
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
                            ComponentProviders componentProviders,
                            io.vidocq.vauban.core.bean.resolution.QualifierMatcher qualifierMatcher) {
        this.classLoader = classLoader;
        this.qualifierMatcher = qualifierMatcher;
        this.managedDescriptors = descriptors.stream()
                .filter(d -> d.kind() == BeanDescriptor.BeanKind.MANAGED)
                .collect(java.util.stream.Collectors.toMap(BeanDescriptor::beanClass, d -> d,
                        (first, second) -> first));
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
            var factory = createFactory(descriptor, factories);
            if (factory != null) {
                beans.put(descriptor.id(), new ManagedBean<>(descriptor, factory, classLoader, vaubanLookup,
                        qualifierMatcher.types()));
            }
        }

        var assignability = new AssignabilityRules(index);
        this.resolver = new BeanResolver(descriptors, interceptorDescriptors, assignability, qualifierMatcher);
        this.eventDispatcher = new EventDispatcher(observers, this);
        this.interceptorManager = new InterceptorManager(interceptorDescriptors);
        this.interceptorManager.setVaubanLookup(vaubanLookup);
        this.interceptorManager.setAnnotationTypes(qualifierMatcher.types());
        this.beanLifecycle = new BeanLifecycle(interceptorManager, vaubanLookup);
        this.interceptorWrapper = new InterceptorBeanWrapper(this, vaubanLookup, interceptorManager, classLoader, classDefiner);
        this.interceptorManager.setInstanceFactory((descriptor, ctx) -> interceptorWrapper.getOrCreateInterceptorInstance(descriptor, ctx));

        // Wrap intercepted beans with generated subclasses
        interceptorWrapper.wrapInterceptedBeans(descriptors);


        // Wire up field injection on each bean (includes PostConstruct in injectFields)
        for (var bean : beans.values()) {
            bean.setInjector((instance, ctx) -> injectFields(instance, bean.descriptor(), ctx));
        }

        // Wire up disposer methods for producer beans
        disposerInvoker.wireDisposers(descriptors, disposers);

        wireSyntheticDisposers(syntheticDisposers);

        this.beanManager = new VaubanBeanManager(this, contexts, beans.values(), eventDispatcher, interceptorManager);
        requestContext.lifecycleEvents(requestContextEvents(beanManager));
        this.running = true;
        CURRENT.set(this);

        fireStartupEvents();
    }

    /** The factory of a bean of the descriptor's kind, or {@code null} when there is none to register. */
    private BeanFactory<?> createFactory(BeanDescriptor descriptor, Map<DotName, BeanFactory<?>> factories) {
        return switch (descriptor.kind()) {
            case MANAGED -> createManagedBeanFactory(descriptor, factories);
            case PRODUCER_METHOD -> createProducerMethodFactory(descriptor);
            case PRODUCER_FIELD -> createProducerFieldFactory(descriptor);
            // Synthetic beans use their unique ID as factory key
            case SYNTHETIC -> factories.get(DotName.of(descriptor.id().value()));
            case null, default -> null;
        };
    }

    /** Gives each synthetic bean the disposer its build compatible extension registered. */
    private void wireSyntheticDisposers(
            Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers) {
        for (var entry : syntheticDisposers.entrySet()) {
            var syntheticId = new BeanId(entry.getKey().value());
            var destroyer = entry.getValue();
            for (var beanEntry : beans.entrySet()) {
                if (beanEntry.getKey().equals(syntheticId)) {
                    beanEntry.getValue().setDestroyer(destroyer);
                }
            }
        }
    }

    /**
     * CDI lifecycle events: fires {@code @Initialized(ApplicationScoped.class)} and {@code @Startup}. An observer
     * that throws fails the boot, and no half-built container is left behind (vauban#97).
     */
    private void fireStartupEvents() {
        String event = "@Initialized(ApplicationScoped.class)";
        boolean started = false;
        try {
            eventDispatcher.fireLifecycle(new Object(),
                    jakarta.enterprise.context.Initialized.Literal.of(jakarta.enterprise.context.ApplicationScoped.class));
            event = "Startup";
            eventDispatcher.fireLifecycle(new jakarta.enterprise.event.Startup());
            started = true;
        } catch (io.vidocq.vauban.core.event.EventDispatcher.LifecycleObserverFailure failure) {
            logObserverFailure(failure, event, "the container does not start");
            throw new jakarta.enterprise.inject.spi.DeploymentException("Observer " + failure.observer() + " of "
                    + event + " threw " + failure.getCause().getClass().getName() + "; the container does not start",
                    failure.getCause());
        } finally {
            if (!started) {
                abortBoot();
            }
        }
    }

    private static final System.Logger LOG = System.getLogger(VaubanContainer.class.getName());

    /**
     * One WARNING per exception a lifecycle observer threw, naming the event and the observer, with the stack
     * trace (vauban#97). CDI 4.1 §9.5: the observers after it were not notified.
     */
    private static void logObserverFailure(io.vidocq.vauban.core.event.EventDispatcher.LifecycleObserverFailure failure,
                                           String event, String consequence) {
        LOG.log(System.Logger.Level.WARNING, "The lifecycle observer " + failure.observer() + " of " + event
                + " threw " + failure.getCause().getClass().getName() + ": the remaining observers of that event"
                + " were not notified, and " + consequence + ".", failure.getCause());
    }

    /** Undoes what a boot that failed in a lifecycle observer had set up, so that no container is left current. */
    private void abortBoot() {
        running = false;
        CURRENT.compareAndSet(this, null);
        try {
            requestContext.deactivate();
            applicationContext.deactivate();
        } catch (RuntimeException _) {
            // Best effort: the boot already failed, and its own exception is the one to report.
        }
        if (moduleLookups != null) {
            moduleLookups.close();
        }
    }

    /** Fires a lifecycle event at shutdown: an observer that throws is logged, and shutdown goes on (vauban#97). */
    private void fireAtShutdown(String event, Object payload, java.lang.annotation.Annotation... qualifiers) {
        try {
            eventDispatcher.fireLifecycle(payload, qualifiers);
        } catch (io.vidocq.vauban.core.event.EventDispatcher.LifecycleObserverFailure failure) {
            logObserverFailure(failure, event, "shutdown goes on");
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Firing " + event + " failed in a lifecycle observer ("
                    + e.getClass().getName() + "); shutdown goes on.", e);
        }
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
        var existing = context.get(finalBean);
        if (existing != null) return existing;

        CreationalContext<T> creationalCtx = new CreationalContextImpl<>();
        return context.get(finalBean, creationalCtx);
    }

    @SuppressWarnings("unchecked")
    <T> T getOrCreateProxyForBean(ManagedBean<T> bean) {
        return interceptorWrapper.getOrCreateProxy(bean);
    }

    /**
     * The qualifiers the bean's descriptor records for parameter {@code index} of {@code member},
     * or {@code null} when nothing describes it. Internal: it lets a caller outside this package
     * resolve a parameter without reading its annotations back (vauban#70).
     */
    public java.lang.annotation.Annotation[] describedParameterQualifiers(
            BeanDescriptor descriptor, java.lang.reflect.Executable member, int index) {
        return descriptor == null ? null : QualifierHelper.parameterQualifiers(
                descriptor.injectionPoints(), member, index, qualifierMatcher().types());
    }

    /** The managed-bean descriptor declared by {@code beanClass}, or {@code null}. Internal. */
    public BeanDescriptor managedDescriptor(DotName beanClass) {
        return managedDescriptors.get(beanClass);
    }

    /**
     * The matcher this container resolves with: it holds the annotation type metadata of this
     * deployment — the index, the class loaders its classes come from, and the members its extensions
     * made non-binding — and caches the key of every qualifier it has seen.
     */
    public io.vidocq.vauban.core.bean.resolution.QualifierMatcher qualifierMatcher() {
        return qualifierMatcher;
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
            io.vidocq.vauban.indexer.VaubanIndex index,
            io.vidocq.vauban.core.bean.resolution.QualifierMatcher qualifierMatcher) {
        var assignability = new io.vidocq.vauban.core.types.AssignabilityRules(index);
        var tempResolver = new io.vidocq.vauban.core.bean.resolution.BeanResolver(
                descriptors, java.util.List.of(), assignability, qualifierMatcher);

        for (var observer : observers) {
            if (!observer.isSynthetic()) {
                validateObserverParameters(observer, tempResolver);
            }
        }
    }

    /**
     * The types of an observer parameter the container provides itself, or that an observer method receives
     * without resolution: they need no bean.
     */
    private static final Set<Class<?>> PROVIDED_OBSERVER_PARAMETER_TYPES = Set.of(
            jakarta.enterprise.inject.spi.EventMetadata.class,
            jakarta.enterprise.inject.spi.BeanManager.class,
            jakarta.enterprise.inject.spi.BeanContainer.class,
            jakarta.enterprise.inject.spi.InjectionPoint.class,
            jakarta.enterprise.inject.spi.Bean.class,
            jakarta.enterprise.inject.Instance.class,
            jakarta.inject.Provider.class,
            jakarta.enterprise.event.Event.class);

    private static void validateObserverParameters(io.vidocq.vauban.core.bean.model.ObserverDescriptor observer,
                                                   io.vidocq.vauban.core.bean.resolution.BeanResolver resolver) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = Class.forName(observer.declaringClass().value(), false, cl);
            var method = observerMethodNamed(clazz, observer.methodName());
            if (method == null) return;
            for (var param : method.getParameters()) {
                if (needsResolution(param)) {
                    checkResolvable(observer, param, resolver);
                }
            }
        } catch (jakarta.enterprise.inject.spi.DeploymentException e) {
            throw e; // re-throw deployment exceptions
        } catch (Exception _) {
            // Skip validation for classes that can't be loaded
        }
    }

    /**
     * The method {@code name} of {@code clazz} that observes an event, or {@code null}: an overload of the same name
     * that observes nothing is not the observer (VAU-OBS-004), and {@code getDeclaredMethods()} has no fixed order.
     */
    private static java.lang.reflect.Method observerMethodNamed(Class<?> clazz, String name) {
        for (var method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && hasParameterAnnotated(method,
                    jakarta.enterprise.event.Observes.class, jakarta.enterprise.event.ObservesAsync.class)) {
                return method;
            }
        }
        return null;
    }

    @SafeVarargs
    static boolean hasParameterAnnotated(java.lang.reflect.Method method,
                                         Class<? extends java.lang.annotation.Annotation>... annotations) {
        for (var param : method.getParameters()) {
            for (var annotation : annotations) {
                if (param.isAnnotationPresent(annotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an observer parameter must be resolvable as an injection point: neither the event nor a type the
     * container provides, and with no custom qualifier, which this check cannot reliably resolve.
     */
    private static boolean needsResolution(java.lang.reflect.Parameter param) {
        return !param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                && !param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)
                && !PROVIDED_OBSERVER_PARAMETER_TYPES.contains(param.getType())
                && !hasCustomQualifier(param);
    }

    private static boolean hasCustomQualifier(java.lang.reflect.Parameter param) {
        for (var ann : param.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    && ann.annotationType() != jakarta.enterprise.inject.Default.class
                    && ann.annotationType() != jakarta.enterprise.inject.Any.class
                    && ann.annotationType() != jakarta.inject.Named.class) {
                return true;
            }
        }
        return false;
    }

    /** Fails the deployment when no bean, or more than one, matches the type of an observer parameter. */
    private static void checkResolvable(io.vidocq.vauban.core.bean.model.ObserverDescriptor observer,
                                        java.lang.reflect.Parameter param,
                                        io.vidocq.vauban.core.bean.resolution.BeanResolver resolver) {
        var paramType = param.getType();
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
        var result = resolver.resolveInjectionPoint(ip);
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

    /**
     * Fires the request context's {@code @Initialized}, {@code @BeforeDestroyed} and
     * {@code @Destroyed(RequestScoped.class)} events (CDI 4.1 §6.7.1). Whether an event has
     * observers is resolved once, on its first firing, so a request costs nothing when none
     * listens, which is the common case for a REST endpoint.
     */
    private static java.util.function.Consumer<java.lang.annotation.Annotation> requestContextEvents(
            VaubanBeanManager beanManager) {
        var observed = new java.util.concurrent.ConcurrentHashMap<java.lang.annotation.Annotation, Boolean>();
        var payload = new Object();
        return qualifier -> {
            if (Boolean.TRUE.equals(observed.computeIfAbsent(qualifier,
                    q -> !beanManager.resolveObserverMethods(payload, q).isEmpty()))) {
                beanManager.getEvent().select(qualifier).fire(payload);
            }
        };
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
        return context.get(bean, new CreationalContextImpl<>());
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
        return new InjectionConstructorFactory(descriptor, ctorParams.size());
    }

    /**
     * Creates a managed bean through its {@code @Inject} constructor, or, failing one, the constructor taking as
     * many parameters as the descriptor records constructor injection points, each argument resolved from the
     * injection point the descriptor records.
     */
    private final class InjectionConstructorFactory implements BeanFactory<Object> {

        private final BeanDescriptor descriptor;
        private final int ctorParamCount;

        InjectionConstructorFactory(BeanDescriptor descriptor, int ctorParamCount) {
            this.descriptor = descriptor;
            this.ctorParamCount = ctorParamCount;
        }

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
                return construct(creationalCtx);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new jakarta.enterprise.inject.CreationException(e);
            }
        }

        private Object construct(CreationalContext<Object> creationalCtx) throws ClassNotFoundException {
            var beanClass = loadClass(descriptor.beanClass().value());

            var injectCtor = injectConstructor(beanClass, ctorParamCount);
            if (injectCtor == null) {
                throw new ContainerOperationException("No @Inject constructor found for " + descriptor.beanClass());
            }

            var transientCtxs = new java.util.ArrayList<io.vidocq.vauban.core.context.CreationalContextImpl<?>>();
            var args = constructorArgs(injectCtor, creationalCtx, transientCtxs);

            // Provider-first: let the owning module run `new X(args…)` in-module (no
            // opens); fall back to reflective construction when no provider owns it.
            Object result = componentProviders.create(descriptor.beanClass().value(), args);
            if (result == null) {
                result = vaubanLookup.newInstance(injectCtor, args);
            }
            for (var tc : transientCtxs) {
                tc.release();
            }
            return result;
        }

        /** The constructor {@code @Inject} marks, else the first one taking {@code parameterCount} parameters, or {@code null}. */
        private static java.lang.reflect.Constructor<?> injectConstructor(Class<?> beanClass, int parameterCount) {
            for (var ctor : beanClass.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    return ctor;
                }
            }
            for (var ctor : beanClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == parameterCount) {
                    return ctor;
                }
            }
            return null;
        }

        /**
         * The arguments of the injection constructor. A {@code @TransientReference} parameter gets a creational
         * context of its own, added to {@code transientCtxs} for the caller to release.
         */
        private Object[] constructorArgs(java.lang.reflect.Constructor<?> injectCtor,
                                         CreationalContext<Object> creationalCtx,
                                         List<io.vidocq.vauban.core.context.CreationalContextImpl<?>> transientCtxs) {
            var paramTypes = injectCtor.getParameterTypes();
            var genericParamTypes = injectCtor.getGenericParameterTypes();
            var ctorParamsRefl = injectCtor.getParameters();
            var ctorOwnerBean = beans.get(descriptor.id());
            var args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                // The descriptor describes every constructor parameter; reading the
                // parameter back is only for one it does not (vauban#70).
                var described = QualifierHelper.parameterQualifiers(
                        descriptor.injectionPoints(), injectCtor, i, qualifierMatcher().types());
                var pQuals = described != null
                        ? described : QualifierHelper.extractParamQualifiers(ctorParamsRefl[i]);
                if (ctorParamsRefl[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)) {
                    var transientCtx = new io.vidocq.vauban.core.context.CreationalContextImpl<>();
                    args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], transientCtx, pQuals, injectCtor, ctorOwnerBean, ctorParamsRefl[i], i);
                    transientCtxs.add(transientCtx);
                } else {
                    args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], creationalCtx, pQuals, injectCtor, ctorOwnerBean, ctorParamsRefl[i], i);
                }
            }
            return args;
        }
    }

    private BeanFactory<?> createProducerMethodFactory(BeanDescriptor descriptor) {
        return new ProducerMethodFactory(descriptor, extractProducerMethodName(descriptor.id()));
    }

    /**
     * Calls a producer method on an instance of its declaring bean. CDI spec: a {@code @Dependent} declaring bean
     * is created for the call and destroyed after it.
     */
    private final class ProducerMethodFactory implements BeanFactory<Object> {

        private final BeanDescriptor descriptor;
        private final String methodName;

        ProducerMethodFactory(BeanDescriptor descriptor, String methodName) {
            this.descriptor = descriptor;
            this.methodName = methodName;
        }

        @Override
        public Object create() {
            return create((jakarta.enterprise.context.spi.CreationalContext<Object>) null);
        }

        @Override
        public Object create(jakarta.enterprise.context.spi.CreationalContext<Object> ctx) {
            try {
                return produce(ctx);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new jakarta.enterprise.inject.CreationException(
                        "Failed to invoke producer method: " + descriptor.id(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private Object produce(CreationalContext<Object> ctx) throws ClassNotFoundException {
            var declaringClass = loadClass(descriptor.beanClass().value());
            ManagedBean<?> declBean = findManagedBeanByClass(descriptor.beanClass());
            boolean isDependent = declBean != null
                    && declBean.getScope() == jakarta.enterprise.context.Dependent.class;
            // For @Dependent declaring beans, track the CreationalContext so dependents
            // (like @Inject fields) are properly destroyed when the declaring bean is destroyed
            io.vidocq.vauban.core.context.CreationalContextImpl<Object> declCtx =
                    new io.vidocq.vauban.core.context.CreationalContextImpl<>();
            Object declaringInstance = isDependent
                    ? ((ManagedBean<Object>) declBean).create(declCtx)
                    : selectByBeanClass(declaringClass);

            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return invoke(method, declaringInstance, ctx, isDependent ? declBean : null, declCtx);
                }
            }
            throw new ContainerOperationException("Producer method not found: " + methodName + " in " + descriptor.beanClass());
        }

        /**
         * Invokes the producer method on {@code declaringInstance}, then destroys that instance when it belongs to
         * a {@code @Dependent} declaring bean ({@code dependentDeclaringBean} not {@code null}).
         */
        @SuppressWarnings("unchecked")
        private Object invoke(java.lang.reflect.Method method, Object declaringInstance,
                              CreationalContext<Object> ctx, ManagedBean<?> dependentDeclaringBean,
                              CreationalContext<Object> declCtx) {
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
                    var described = QualifierHelper.parameterQualifiers(
                            descriptor.injectionPoints(), method, i, qualifierMatcher().types());
                    var qualifiers = described != null
                            ? described : QualifierHelper.extractParamQualifiers(params[i]);
                    // A @TransientReference parameter, or any without a creational context, is resolved in the
                    // transient context released after the call.
                    CreationalContext<?> paramCtx =
                            ctx == null || params[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)
                                    ? transientCtx : ctx;
                    args[i] = resolveParameter(paramTypes[i], genericParamTypes[i], paramCtx, qualifiers, method);
                }
                return vaubanLookup.invokeMethod(declaringInstance, method, args);
            } finally {
                transientCtx.release();
                // CDI spec: destroy @Dependent declaring bean after producer method completes
                if (dependentDeclaringBean != null && declaringInstance != null) {
                    ((ManagedBean<Object>) dependentDeclaringBean).destroy(declaringInstance, declCtx);
                }
            }
        }
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

    public Object resolveParameter(Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx,
            java.lang.annotation.Annotation[] qualifiers, java.lang.reflect.Member member,
            jakarta.enterprise.inject.spi.Bean<?> ownerBean, java.lang.reflect.Parameter param, int paramPosition) {
        if (paramType == Event.class) {
            var eventIp = member == null ? null : parameterInjectionPoint(genericType,
                    QualifierHelper.collectQualifierSet(qualifiers), ownerBean, member, param, paramPosition);
            return new EventImpl<>(eventDispatcher, qualifiers, eventIp);
        }
        if (paramType == Instance.class || paramType == jakarta.inject.Provider.class) {
            return instanceParameter(genericType, qualifiers, ownerBean, member, param, paramPosition);
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
            var depIp = parameterInjectionPoint(genericType, defaultedQualifiers(qualifiers), ownerBean, member,
                    param, paramPosition);
            return dependentReference(depIp, resolved, paramType, genericType, ctx);
        }
        return referenceOrDefault(bm, resolved, paramType, genericType, bm.createCreationalContext(resolved));
    }

    /**
     * The injection point of a parameter: the parameter of an executable when both are known, otherwise the bare
     * type on {@code member}.
     */
    private static VaubanInjectionPoint parameterInjectionPoint(java.lang.reflect.Type type, Set<Annotation> qualifiers,
            jakarta.enterprise.inject.spi.Bean<?> ownerBean, java.lang.reflect.Member member,
            java.lang.reflect.Parameter param, int paramPosition) {
        if (param != null && member instanceof java.lang.reflect.Executable exec) {
            return new VaubanInjectionPoint(param, paramPosition, exec, type, qualifiers, ownerBean);
        }
        return new VaubanInjectionPoint(type, qualifiers, ownerBean, member);
    }

    /** The qualifiers as a set, {@code @Default} when there are none. */
    private static Set<Annotation> defaultedQualifiers(Annotation[] qualifiers) {
        var qualSet = new java.util.HashSet<>(java.util.Arrays.asList(qualifiers));
        if (qualSet.isEmpty()) qualSet.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        return qualSet;
    }

    /** An {@code Instance} or {@code Provider} parameter: an {@link InstanceImpl} of its type argument. */
    private Object instanceParameter(java.lang.reflect.Type genericType, Annotation[] qualifiers,
            jakarta.enterprise.inject.spi.Bean<?> ownerBean, java.lang.reflect.Member member,
            java.lang.reflect.Parameter param, int paramPosition) {
        Class<?> instanceType = Object.class;
        var targetType = genericType;
        if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
            var typeArg = pt.getActualTypeArguments()[0];
            if (typeArg instanceof Class<?> c) instanceType = c;
            targetType = typeArg;
        }
        var ip = parameterInjectionPoint(targetType, defaultedQualifiers(qualifiers), ownerBean, member, param,
                paramPosition);
        return new InstanceImpl<>(this, instanceType, ip).select(qualifiers);
    }

    /** The reference to a {@code @Dependent} bean, created while its injection point is the current one. */
    private Object dependentReference(VaubanInjectionPoint depIp, jakarta.enterprise.inject.spi.Bean<?> resolved,
            Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> ctx) {
        var bm = getBeanManager();
        try {
            return callWithInjectionPoint(depIp, () -> {
                var pCtx = (ctx != null) ? ctx : bm.createCreationalContext(resolved);
                return referenceOrDefault(bm, resolved, paramType, genericType, pCtx);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ContainerOperationException(e);
        }
    }

    /** The reference to {@code resolved}, or the default value of a primitive parameter when it is {@code null}. */
    private static Object referenceOrDefault(BeanManager bm, jakarta.enterprise.inject.spi.Bean<?> resolved,
            Class<?> paramType, java.lang.reflect.Type genericType, CreationalContext<?> pCtx) {
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

    static String extractProducerMethodName(BeanId id) {
        var value = id.value();
        int hash = value.lastIndexOf('#');
        return hash >= 0 ? value.substring(hash + 1) : value;
    }

    static String extractProducerFieldName(BeanId id) {
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
        fireAtShutdown("Shutdown", new jakarta.enterprise.event.Shutdown());
        fireAtShutdown("@BeforeDestroyed(ApplicationScoped.class)", new Object(),
                jakarta.enterprise.context.BeforeDestroyed.Literal.of(jakarta.enterprise.context.ApplicationScoped.class));

        CURRENT.compareAndSet(this, null);
        if (moduleLookups != null) {
            moduleLookups.close();
        }
        requestContext.deactivate();
        applicationContext.deactivate();

        fireAtShutdown("@Destroyed(ApplicationScoped.class)", new Object(),
                jakarta.enterprise.context.Destroyed.Literal.of(jakarta.enterprise.context.ApplicationScoped.class));
    }

    /**
     * Builder for creating a VaubanContainer.
     */
    public static VaubanContainerBuilder builder() {
        return new VaubanContainerBuilder();
    }

}
