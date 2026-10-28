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
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.interceptor.InterceptorManager;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.interceptor.VaubanInvocationContext;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.CreationException;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

final class InterceptorBeanWrapper {

    private static final System.Logger LOG = System.getLogger(InterceptorBeanWrapper.class.getName());

    private static final ScopedValue<Boolean> IS_CREATING_INTERCEPTOR = ScopedValue.newInstance();

    /** What {@link #interceptorInstanceAsBean} answers when the interceptor is no bean it can resolve. */
    private static final Object NO_BEAN = new Object();

    private static final String CANNOT_DEFINE_SUBCLASS = "Could not define interceptor subclass";

    private final VaubanContainer container;
    private final VaubanLookup vaubanLookup;
    private final InterceptorManager interceptorManager;
    private final ClassLoader classLoader;
    private final BiFunction<String, byte[], Class<?>> classDefiner;
    private final java.util.Map<io.vidocq.vauban.core.bean.model.BeanId, Object> proxyCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Each intercepted bean, by id: whether its {@code $$Intercepted} subclass was pre-generated (Vauban APT or Maven
     * plugin) rather than generated and defined at boot. Read by {@link CodegenCoverage}; nothing dispatches on it.
     */
    private final java.util.Map<io.vidocq.vauban.core.bean.model.BeanId, Boolean> interceptedSubclasses =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The {@code $$Intercepted} subclasses Vauban generated and defined at boot, in this JVM, whichever container did
     * it. A class stays defined in its loader after its container closes, so a later boot sharing the loader finds it:
     * this set tells such a class apart from one built ahead of time. Weak, so it never holds a loader alive.
     */
    private static final java.util.Set<Class<?>> DEFINED_AT_RUNTIME =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** Whether the bean {@code id}'s intercepted subclass was pre-generated; {@code null} if it is not intercepted. */
    Boolean interceptedSubclassPreGenerated(io.vidocq.vauban.core.bean.model.BeanId id) {
        return interceptedSubclasses.get(id);
    }

    InterceptorBeanWrapper(VaubanContainer container, VaubanLookup vaubanLookup,
                           InterceptorManager interceptorManager, ClassLoader classLoader,
                           BiFunction<String, byte[], Class<?>> classDefiner) {
        this.container = container;
        this.vaubanLookup = vaubanLookup;
        this.interceptorManager = interceptorManager;
        this.classLoader = classLoader;
        this.classDefiner = classDefiner;
    }

    Object getOrCreateInterceptorInstance(InterceptorDescriptor descriptor, CreationalContext<?> ctx) {
        String className = descriptor.interceptorClass().value();

        if (ctx instanceof CreationalContextImpl<?> vCtx) {
            var known = knownInterceptorInstance(vCtx, className);
            if (known != null) {
                return known;
            }
        }

        var asBean = interceptorInstanceAsBean(className, ctx);
        if (asBean != NO_BEAN) {
            return asBean;
        }

        if (Boolean.TRUE.equals(IS_CREATING_INTERCEPTOR.orElse(false))) {
            try {
                return createNestedInterceptor(className, ctx);
            } catch (Exception e) {
                throw new CreationException(e);
            }
        }
        try {
            return ScopedValue.where(IS_CREATING_INTERCEPTOR, true).call(() -> createInterceptor(className, ctx));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR,
                    "Failed to create interceptor " + descriptor.interceptorClass(), e);
            if (e instanceof java.lang.reflect.InvocationTargetException ite) {
                LOG.log(System.Logger.Level.ERROR,
                        "Caused by: " + ite.getTargetException(), ite.getTargetException());
            }
            throw new CreationException("Failed to create interceptor: " + descriptor.interceptorClass(), e);
        }
    }

    /**
     * The instance of interceptor {@code className} this creational context already holds: cached, still under
     * construction, or a dependent instance. {@code null} when it holds none.
     */
    private static Object knownInterceptorInstance(CreationalContextImpl<?> vCtx, String className) {
        Object cached = vCtx.getInterceptorInstance(className);
        if (cached != null) {
            return cached;
        }
        for (Object inst : vCtx.getIncompleteInstances()) {
            if (isInstanceOf(inst, className)) {
                vCtx.addInterceptorInstance(className, inst);
                return inst;
            }
        }
        for (var dep : vCtx.getDependentInstances()) {
            if (isInstanceOf(dep.instance(), className)) {
                vCtx.addInterceptorInstance(className, dep.instance());
                return dep.instance();
            }
        }
        return null;
    }

    /** Whether {@code instance} is of class {@code className} or of a subclass Vauban generated for it. */
    private static boolean isInstanceOf(Object instance, String className) {
        var name = instance.getClass().getName();
        return name.equals(className) || name.startsWith(className + "$$");
    }

    /**
     * The reference to interceptor {@code className} resolved as a bean, which may be {@code null}, or
     * {@link #NO_BEAN} when no bean of that class resolves: the caller then creates the instance itself.
     */
    private Object interceptorInstanceAsBean(String className, CreationalContext<?> ctx) {
        try {
            var bm = container.getBeanManager();
            var clazz = container.loadClass(className);
            var interceptorBeans = bm.getBeans(clazz);
            if (interceptorBeans.isEmpty()) {
                return NO_BEAN;
            }
            var bean = bm.resolve(interceptorBeans);
            Object inst = bm.getReference(bean, clazz, ctx);
            if (inst != null && ctx instanceof CreationalContextImpl<?> vCtx) {
                vCtx.addInterceptorInstance(className, inst);
            }
            return inst;
        } catch (Exception _) {
            // fallback to manual creation
            return NO_BEAN;
        }
    }

    /**
     * The creational context an intercepted factory creates a bean with: the caller's, or one of the bean's own when
     * the caller passes none (VAU-INT-008). Its interceptor instances are dependent objects of the bean instance
     * (CDI 4.1 §6.4.1), one per interceptor class: without a context, the dependent context answers no reference to
     * them, and the chains of a same bean instance would not share them.
     */
    private static CreationalContext<Object> contextOfItsOwnIfNone(CreationalContext<Object> ctx) {
        return ctx != null ? ctx : new CreationalContextImpl<>();
    }

    /** An interceptor created while another one is being created: its fields injected and {@code @PostConstruct} called here. */
    private Object createNestedInterceptor(String className, CreationalContext<?> ctx) throws ReflectiveOperationException {
        var constructor = findConstructor(container.loadClass(className));
        var instance = instantiatePreferProvider(constructor, interceptorConstructorArgs(constructor, ctx));
        container.beanInjector().injectFieldsByReflection(instance, null, ctx);
        var pc = postConstructMethod(instance.getClass());
        if (pc != null) {
            vaubanLookup.invokeMethod(instance, pc);
        }
        if (ctx instanceof CreationalContextImpl<?> vCtx) {
            vCtx.addInterceptorInstance(className, instance);
            vCtx.pushInterceptor(instance);
        }
        return instance;
    }

    /** An interceptor created, injected and initialized as the container does for a bean. */
    @SuppressWarnings("unchecked")
    private Object createInterceptor(String className, CreationalContext<?> ctx) throws ReflectiveOperationException {
        var constructor = findConstructor(container.loadClass(className));
        var instance = instantiatePreferProvider(constructor, interceptorConstructorArgs(constructor, ctx));
        container.injectFields(instance, null, ctx);
        container.callPostConstruct(instance, null, ctx);

        if (ctx instanceof CreationalContextImpl<?> vCtx) {
            vCtx.addInterceptorInstance(className, instance);
            vCtx.pushInterceptor(instance);
        } else if (ctx != null) {
            ((CreationalContext<Object>) ctx).push(instance);
        }
        return instance;
    }

    /** The arguments of an interceptor's constructor, each resolved by its qualifiers when a bean has them. */
    private Object[] interceptorConstructorArgs(Constructor<?> constructor, CreationalContext<?> ctx) {
        var paramTypes = constructor.getParameterTypes();
        var genericParamTypes = constructor.getGenericParameterTypes();
        var params = constructor.getParameters();
        var args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = interceptorConstructorArg(paramTypes[i], genericParamTypes[i], params[i], ctx);
        }
        return args;
    }

    private Object interceptorConstructorArg(Class<?> paramType, java.lang.reflect.Type genericParamType,
                                             java.lang.reflect.Parameter param, CreationalContext<?> ctx) {
        var paramQuals = QualifierHelper.extractParamQualifiers(param);
        if (paramQuals.length > 0) {
            var bm = container.getBeanManager();
            var beans = bm.getBeans(paramType, paramQuals);
            if (!beans.isEmpty()) {
                var resolved = bm.resolve(beans);
                return bm.getReference(resolved, paramType, ctx);
            }
        }
        return container.resolveParameter(paramType, genericParamType, ctx);
    }

    /** The first method {@code type} declares with {@code @PostConstruct}, or {@code null}. */
    private static Method postConstructMethod(Class<?> type) {
        for (var m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(jakarta.annotation.PostConstruct.class)) {
                return m;
            }
        }
        return null;
    }

    private Constructor<?> findConstructor(Class<?> clazz) throws NoSuchMethodException {
        // The (ProxyLink) client-proxy entry constructor is never an instantiation
        // candidate (Vidocq/vauban#24) — real instances must run a business constructor.
        var candidates = java.util.Arrays.stream(clazz.getDeclaredConstructors())
                .filter(c -> !io.vidocq.vauban.core.proxy.ClientProxyShape.isProxyLinkConstructor(c))
                .toList();
        for (var c : candidates) {
            if (c.isAnnotationPresent(jakarta.inject.Inject.class)) {
                return c;
            }
        }
        try {
            return clazz.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            if (candidates.size() == 1) {
                return candidates.getFirst();
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    <T> T getOrCreateProxy(ManagedBean<T> bean) {
        return (T) proxyCache.computeIfAbsent(bean.descriptor().id(), id -> createProxy(bean));
    }

    @SuppressWarnings("unchecked")
    private <T> Object createProxy(ManagedBean<T> bean) {
        var beanClass = resolveProxyTargetClass(bean);

        // VAU-PROXY-INTERFACE: when the proxy target is an interface (e.g. a @RequestScoped
        // producer-method bean whose produced type is an interface like JsonWebToken),
        // RuntimeClientProxyGenerator cannot subclass an interface. Use java.lang.reflect.Proxy
        // instead — the proxy lazily resolves the contextual instance per-call.
        if (beanClass.isInterface()) {
            return interfaceProxy(bean, beanClass);
        }

        checkNormalScopedProxyable(beanClass);

        try {
            return subclassProxy(bean, beanClass);
        } catch (Exception e) {
            // VAU-INJ-001: never fall back to eager ctx.get() for normal-scoped beans.
            // The context may be inactive at proxy-creation time; doing ctx.get() here
            // would throw ContextNotActiveException and leave the injected field null.
            // For pseudo-scoped beans (Dependent, Singleton) the eager path is safe.
            if (bean.descriptor().scope().isNormal()) {
                throw new DeploymentException(
                        "Failed to create client proxy for normal-scoped bean "
                                + bean.getBeanClass().getName(), e);
            }
            var scopeClass = bean.getScope();
            var ctx = container.getFirstContext(scopeClass);
            if (ctx == null) ctx = container.dependentContext();
            return ctx.get((Contextual<Object>) (Contextual<?>) bean,
                    new CreationalContextImpl<>());
        }
    }

    /**
     * What a client proxy delegates to: the contextual instance of the bean, looked up again on every call, in the
     * context of its scope.
     */
    @SuppressWarnings("unchecked")
    private <T> java.util.function.Supplier<Object> contextualInstance(ManagedBean<T> bean) {
        var beanId = bean.descriptor().id();
        return () -> {
            var currentBean = container.beans.get(beanId);
            if (currentBean == null) currentBean = bean;
            var scopeClass = currentBean.getScope();
            var ctx = container.getFirstContext(scopeClass);
            if (ctx == null) {
                // A normal-scoped proxy call without a registered context must fail loudly
                // (CDI 4.1 §6.5.1) — the previous silent @Dependent fallback handed out a
                // fresh instance per proxy call, so state written through the proxy
                // vanished on the next call (VAU-CTX-001).
                throw new jakarta.enterprise.context.ContextNotActiveException(
                        "No context registered for scope " + scopeClass.getName()
                                + " (bean " + currentBean.getBeanClass().getName() + ")");
            }
            return ctx.get((Contextual<Object>) (Contextual<?>) currentBean,
                    new CreationalContextImpl<Object>());
        };
    }

    private <T> Object interfaceProxy(ManagedBean<T> bean, Class<?> beanClass) {
        var delegate = contextualInstance(bean);
        // Issue #42: prefer the build-time static interface proxy (a generated class
        // `implements <Iface>`, registered by the APT under the produced-type key). No
        // runtime java.lang.reflect.Proxy, no reflection. Falls through to reflect.Proxy on
        // the class path or when no provider owns the interface (non-APT producers).
        String ifaceProxyKey =
                io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator.proxyClassName(beanClass);
        Object providedIface = container.componentProviders().createClientProxy(ifaceProxyKey, delegate);
        if (providedIface != null) {
            return providedIface;
        }

        // Fallback: collect all interface types from the bean to implement via reflect.Proxy
        java.util.Set<Class<?>> ifaceSet = new java.util.LinkedHashSet<>();
        for (var t : bean.getTypes()) {
            if (t instanceof Class<?> c && c.isInterface() && c != Object.class) {
                ifaceSet.add(c);
            }
        }
        ifaceSet.add(beanClass);
        Class<?>[] ifaces = ifaceSet.toArray(new Class<?>[0]);
        return java.lang.reflect.Proxy.newProxyInstance(
                beanClass.getClassLoader(),
                ifaces,
                (proxy, method, args) -> {
                    Object target = delegate.get();
                    return method.invoke(target, args);
                });
    }

    /** A normal-scoped bean's class must be proxyable: not final, no final method, not only a private no-arg constructor. */
    private static void checkNormalScopedProxyable(Class<?> beanClass) {
        if (java.lang.reflect.Modifier.isFinal(beanClass.getModifiers())) {
            throw new jakarta.enterprise.inject.UnproxyableResolutionException("Normal scoped bean " + beanClass.getName() + " is final");
        }
        var finalMethod = firstFinalMethod(beanClass);
        if (finalMethod != null) {
            throw new jakarta.enterprise.inject.UnproxyableResolutionException("Normal scoped bean " + beanClass.getName() + " has final method " + finalMethod.getName());
        }
        // CDI 4.1: no-arg constructor is NOT required — proxy calls super with default values.
        // However, a bean with ONLY a private no-arg constructor is still unproxyable.
        boolean hasPrivateNoArgCtor = false;
        boolean hasNonPrivateNoArgCtor = false;
        for (var ctor : beanClass.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 0) {
                boolean isPrivate = java.lang.reflect.Modifier.isPrivate(ctor.getModifiers());
                hasPrivateNoArgCtor |= isPrivate;
                hasNonPrivateNoArgCtor |= !isPrivate;
            }
        }
        if (hasPrivateNoArgCtor && !hasNonPrivateNoArgCtor) {
            throw new jakarta.enterprise.inject.UnproxyableResolutionException(
                    "Normal scoped bean " + beanClass.getName()
                            + " has only a private no-arg constructor");
        }
    }

    /** The first method {@code beanClass} declares final, neither private nor static, or {@code null}. */
    private static Method firstFinalMethod(Class<?> beanClass) {
        for (var method : beanClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isFinal(method.getModifiers())
                    && !java.lang.reflect.Modifier.isPrivate(method.getModifiers())
                    && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    private <T> Object subclassProxy(ManagedBean<T> bean, Class<?> beanClass) throws ReflectiveOperationException {
        var delegate = contextualInstance(bean);

        // 1. In-module proxy creation via the component provider (zero-opens AND zero-export):
        //    the APT-generated provider does `new <Bean>_ClientProxy(delegate)` in-package, so the
        //    container never reflects into the bean's package. Falls through to
        //    reflective generation when no provider owns this proxy (class path, runtime-only proxy,
        //    producer-bean proxy).
        String proxyClassName =
                io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator.proxyClassName(beanClass);
        Object provided = container.componentProviders().createClientProxy(proxyClassName, delegate);
        if (provided != null) {
            return provided;
        }

        // 2. Fallback: runtime-generate the proxy + reflective instantiation through its only
        //    constructor, (Supplier), which wires the delegate once and for all.
        var generated = io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator.generate(beanClass);
        var proxyClass = defineProxyClass(beanClass, generated.className(), generated.bytecode());

        return proxyClass.getDeclaredConstructor(java.util.function.Supplier.class).newInstance(delegate);
    }

    private Class<?> defineProxyClass(Class<?> beanClass, String className, byte[] bytecode) {
        try {
            return loadOrDefineClassRobustly(beanClass, className, bytecode);
        } catch (Exception e) {
            throw new ContainerOperationException("Failed to define proxy class for " + beanClass, e);
        }
    }

    static Class<?> resolveProxyTargetClass(ManagedBean<?> bean) {
        if (bean.descriptor().kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                || bean.descriptor().kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
            // First pass: prefer a concrete (non-interface) type
            for (var type : bean.getTypes()) {
                if (type instanceof Class<?> c && c != Object.class && !c.isInterface()) {
                    return c;
                }
            }
            // VAU-PROXY-INTERFACE: no concrete type found — the produced type is an interface
            // (e.g. JsonWebToken). Return the interface so getOrCreateProxy() can detect it
            // and create a java.lang.reflect.Proxy instead of a subclass proxy.
            for (var type : bean.getTypes()) {
                if (type instanceof Class<?> c && c != Object.class) {
                    return c;
                }
            }
        }
        return bean.getBeanClass();
    }

    /** Whether {@code annotation} is an interceptor binding, declared as one or made one by an extension. */
    private static boolean isInterceptorBinding(Annotation annotation) {
        return annotation.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                || VaubanBeanManager.isCustomInterceptorBinding(annotation.annotationType());
    }

    boolean hasMethodOrClassInterceptors(ManagedBean<?> mb) {
        var classBindings = mb.descriptor().interceptorBindings();
        if (classBindings != null && !classBindings.isEmpty()
                && !interceptorManager.resolveInterceptorDescriptors(classBindings).isEmpty()) {
            return true;
        }
        try {
            var beanClass = container.loadClass(mb.descriptor().beanClass().value());
            for (var m : beanClass.getDeclaredMethods()) {
                for (var ann : m.getAnnotations()) {
                    if (isInterceptorBinding(ann)) {
                        return true;
                    }
                }
            }
        } catch (ClassNotFoundException _) {
            // A class that does not load has no method to intercept.
        }
        return false;
    }

    void eagerCreateInterceptors(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        if (interceptorManager == null || !interceptorManager.hasInterceptors()) return;
        if (instance.getClass().isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;

        var allBindings = new java.util.LinkedHashSet<DotName>();
        var beanBindings = (descriptor != null) ? descriptor.interceptorBindings() : container.beanLifecycle.findInterceptorBindings(instance);
        if (beanBindings == null || beanBindings.isEmpty()) {
            beanBindings = container.beanLifecycle.findInterceptorBindings(instance);
        }
        if (beanBindings != null) {
            allBindings.addAll(beanBindings);
        }
        var beanClass = instance.getClass();
        if (beanClass.getName().contains("$$Intercepted") || beanClass.getName().contains("$$Proxy")) {
            beanClass = beanClass.getSuperclass();
        }
        addMethodBindings(beanClass, allBindings);
        if (allBindings.isEmpty()) return;
        interceptorManager.setClassLoader(instance.getClass().getClassLoader());
        var matchingDescriptors = interceptorManager.resolveInterceptorDescriptors(allBindings);
        for (var desc : matchingDescriptors) {
            interceptorManager.getOrCreateInstance(desc, parentCtx);
        }
    }

    /** Adds the interceptor bindings the methods {@code beanClass} declares carry. */
    private static void addMethodBindings(Class<?> beanClass, Set<DotName> bindings) {
        for (var m : beanClass.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                if (isInterceptorBinding(ann)) {
                    bindings.add(DotName.of(ann.annotationType().getName()));
                }
            }
        }
    }

    /**
     * Calls the post-construction wiring ({@code $$init}) of a {@code $$Intercepted} instance.
     *
     * <p>Through a method handle found with the bean module's own lookup, when the generated
     * {@code _VaubanComponents} of the bean's package grants one ({@code ModuleLookups}): same
     * module, so the package needs neither {@code exports} nor {@code opens} to
     * {@code io.vidocq.vauban.core} (BUG-20261010-02). Reflection otherwise, as before, which needs
     * the package exported. A failure of {@code $$init} itself is wrapped in an
     * {@link java.lang.reflect.InvocationTargetException} on both paths.</p>
     */
    private static void initIntercepted(Class<?> interceptedClass, Object instance, InterceptorManager manager,
                                        Set<?> bindings, Set<?> constructorBindings,
                                        CreationalContext<?> creationalContext) throws ReflectiveOperationException {
        var moduleLookup = io.vidocq.vauban.core.access.ModuleLookups.lookupFor(interceptedClass.getSuperclass());
        if (moduleLookup.isPresent()) {
            java.lang.invoke.MethodHandle init = null;
            try {
                init = moduleLookup.get().findVirtual(interceptedClass,
                        io.vidocq.vauban.core.interceptor.InterceptedShape.INIT_METHOD, INIT_TYPE);
            } catch (NoSuchMethodException | IllegalAccessException _) {
                // Fall back to reflection below.
            }
            if (init != null) {
                try {
                    init.invoke(instance, manager, bindings, constructorBindings, creationalContext);
                } catch (Throwable t) {
                    throw new java.lang.reflect.InvocationTargetException(t);
                }
                return;
            }
        }
        interceptedClass.getMethod(io.vidocq.vauban.core.interceptor.InterceptedShape.INIT_METHOD,
                        INIT_TYPE.parameterArray())
                .invoke(instance, manager, bindings, constructorBindings, creationalContext);
    }

    /** {@code void $$init(InterceptorManager, Set, Set, CreationalContext)}. */
    private static final java.lang.invoke.MethodType INIT_TYPE = java.lang.invoke.MethodType.methodType(
            void.class, InterceptorManager.class, Set.class, Set.class, CreationalContext.class);

    /**
     * Instantiates a managed class — an intercepted {@code $$Intercepted} subclass OR an
     * {@code @Interceptor} bean — preferring zero reflection. Order:
     * <ol>
     *   <li><b>in-module provider</b> — when the Vauban APT emitted a {@code _VaubanComponents} for the
     *       class's module, its co-located provider runs {@code new <class>(args…)} directly
     *       (no reflection, no opens). This covers both pre-generated {@code $$Intercepted} subclasses
     *       and, since VAU-INT-004, the interceptor classes themselves;</li>
     *   <li><b>public Lookup</b> — a public constructor in an exported package, constructible via
     *       {@code publicLookup} with no {@code privateLookupIn} (hence no opens) — covers the
     *       runtime/plugin-generated case where no provider owns the name;</li>
     *   <li><b>private Lookup</b> — last resort for the class path (unrestricted) or a class in a
     *       non-exported package (which keeps its qualified {@code opens}).</li>
     * </ol>
     */
    private Object instantiatePreferProvider(Constructor<?> ctor, Object[] args) {
        var providers = container.componentProviders();
        if (providers != null) {
            var inModule = providers.create(ctor.getDeclaringClass().getName(), args);
            if (inModule != null) return inModule;
        }
        var viaPublic = vaubanLookup.newInstancePublic(ctor, args);
        if (viaPublic != null) return viaPublic;
        return vaubanLookup.newInstance(ctor, args);
    }

    Class<?> loadOrDefineClassRobustly(Class<?> targetClass, String className, byte[] bytecode)
            throws IllegalAccessException {
        try {
            return targetClass.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException _) {
            // Use the container's VaubanLookup which has the user-provided root Lookup
            // (no sun.misc.Unsafe needed — addReads + privateLookupIn handles Java Modules)
            var lookup = vaubanLookup.lookupFor(targetClass);
            return lookup.defineClass(bytecode);
        }
    }

    /**
     * Replaces the bean of every intercepted managed bean by one whose factory creates its {@code $$Intercepted}
     * subclass. A bean whose wrapping fails for another reason than a deployment or a definition problem runs
     * without its interceptors, and the failure is logged.
     */
    void wrapInterceptedBeans(List<BeanDescriptor> descriptors) {
        for (var descriptor : descriptors) {
            wrapIfIntercepted(descriptor);
        }
    }

    /** Wraps the bean of {@code descriptor} when it is an interception candidate that something may intercept. */
    private void wrapIfIntercepted(BeanDescriptor descriptor) {
        var beanClass = interceptionCandidate(descriptor);
        if (beanClass == null) return;
        var bindings = declaredBindings(descriptor, beanClass);
        if (bindings.isEmpty() && !interceptorManager.hasInterceptors() && !hasTargetAroundInvoke(beanClass)) {
            return;
        }
        try {
            wrapInterceptedBean(descriptor, beanClass, bindings);
        } catch (Exception e) {
            rethrowDeploymentProblem(e);
            LOG.log(System.Logger.Level.ERROR, "Could not wrap the intercepted bean "
                    + descriptor.beanClass().value() + "; it runs without its interceptors", e);
        }
    }

    /**
     * The class of a managed bean the container holds, unless it is an interceptor itself; {@code null} when the
     * bean cannot be intercepted, or its class does not load.
     */
    private Class<?> interceptionCandidate(BeanDescriptor descriptor) {
        Class<?> beanClass;
        try {
            beanClass = container.loadClass(descriptor.beanClass().value());
        } catch (ClassNotFoundException _) {
            return null;
        }
        if (descriptor.kind() != BeanDescriptor.BeanKind.MANAGED
                || beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)
                || container.beans.get(descriptor.id()) == null) {
            return null;
        }
        return beanClass;
    }

    /** The bindings the descriptor records, or, when it records none, the binding annotations of the bean class. */
    private static Set<DotName> declaredBindings(BeanDescriptor descriptor, Class<?> beanClass) {
        Set<DotName> bindings = new java.util.LinkedHashSet<>(descriptor.interceptorBindings());
        if (bindings.isEmpty()) {
            for (var ann : beanClass.getAnnotations()) {
                if (isInterceptorBinding(ann)) {
                    bindings.add(DotName.of(ann.annotationType().getName()));
                }
            }
        }
        return bindings;
    }

    /** Whether {@code beanClass} or one of its superclasses declares an {@code @AroundInvoke} method. */
    private static boolean hasTargetAroundInvoke(Class<?> beanClass) {
        for (var cur = beanClass; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (var m : cur.getDeclaredMethods()) {
                if (m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void rethrowDeploymentProblem(Exception e) {
        if (e instanceof DeploymentException de) throw de;
        if (e instanceof DefinitionException de) throw de;
    }

    private void wrapInterceptedBean(BeanDescriptor descriptor, Class<?> beanClass, Set<DotName> bindings) {
        interceptorManager.setClassLoader(beanClass.getClassLoader());

        var classBindings = interceptorManager.findBindingsOnClass(beanClass, interceptorManager::isInterceptorBinding);

        classBindings.addAll(bindings);

        registerEnhancedBindings(descriptor, beanClass);

        // Why the bean is intercepted, kept for the proxyability errors below: a bean bound
        // only through a method it inherits carries no binding in its own source. The walk is
        // the one vauban:generate pre-generates the subclass on (InterceptionTargets).
        var cause = interceptionCause(beanClass, classBindings);
        if (cause == null) return;

        // The bean is actually intercepted (class/method/constructor binding or a target
        // @AroundInvoke). Only NOW do the proxyability constraints apply: a non-intercepted
        // bean may legally be final, carry final methods, or expose only a private no-arg
        // constructor. Enforcing them earlier wrongly rejected beans that merely coexist with
        // interceptors elsewhere in the deployment — e.g. a generated, pseudo-scoped final
        // @Named DataSource holder. The proxyability constraints bind to intercepted beans only.
        // An unproxyable intercepted bean is a deployment problem (CDI 4.1 §3.10, §8.3).
        checkInterceptedProxyable(beanClass, interceptedBecause(cause, beanClass, classBindings));

        try {
            installInterceptedBean(descriptor, beanClass, classBindings);
        } catch (Exception e) {
            rethrowDeploymentProblem(e);
            installFallbackInterceptedBean(descriptor, beanClass, bindings);
        } catch (LinkageError le2) {
            throw new DefinitionException(
                    "Cannot create interceptor subclass: " + le2.getMessage(), le2);
        }
    }

    /** Registers the binding annotations an {@code @Enhancement} added, those the bean class does not carry. */
    private void registerEnhancedBindings(BeanDescriptor descriptor, Class<?> beanClass) {
        if (descriptor.interceptorBindingAnnotations().isEmpty()) return;
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

    private io.vidocq.vauban.core.interceptor.InterceptionTargets.Cause interceptionCause(
            Class<?> targetClass, Set<DotName> bindingsOfClass) {
        return io.vidocq.vauban.core.interceptor.InterceptionTargets.causeOf(targetClass,
                new io.vidocq.vauban.core.interceptor.InterceptionTargets.Bindings() {
                    @Override
                    public boolean onClass() {
                        return !interceptorManager.resolveInterceptorDescriptors(bindingsOfClass).isEmpty();
                    }

                    @Override
                    public boolean onMethod(Method method) {
                        return !interceptorManager.resolveInterceptorDescriptorsForMethod(
                                bindingsOfClass, targetClass, method).isEmpty();
                    }

                    @Override
                    public boolean onConstructor(Constructor<?> constructor) {
                        return !interceptorManager.resolveInterceptorDescriptorsAroundConstruct(
                                bindingsOfClass, constructor, targetClass, java.util.Collections.emptyList())
                                .isEmpty();
                    }
                });
    }

    private String interceptedBecause(io.vidocq.vauban.core.interceptor.InterceptionTargets.Cause cause,
                                      Class<?> beanClass, Set<DotName> classBindings) {
        return switch (cause) {
            case io.vidocq.vauban.core.interceptor.InterceptionTargets.ClassLevel _ ->
                    "its class-level interceptor bindings " + classBindings.stream()
                            .map(name -> "@" + name.value()).collect(java.util.stream.Collectors.joining(", "));
            case io.vidocq.vauban.core.interceptor.InterceptionTargets.BoundMethod(var m) ->
                    interceptorManager.describeMethodBindings(beanClass, m);
            case io.vidocq.vauban.core.interceptor.InterceptionTargets.BoundConstructor(var ctor) ->
                    "the interceptor binding of its constructor " + ctor;
            case io.vidocq.vauban.core.interceptor.InterceptionTargets.TargetAroundInvoke(var m) ->
                    "its @AroundInvoke method " + m.getDeclaringClass().getName() + "." + m.getName();
        };
    }

    private static void checkInterceptedProxyable(Class<?> beanClass, String interceptedBecause) {
        var unproxyable = " An intercepted bean must be proxyable (CDI 4.1 §3.10); "
                + beanClass.getName() + " is intercepted because of " + interceptedBecause + ".";
        if (java.lang.reflect.Modifier.isFinal(beanClass.getModifiers())) {
            throw new DeploymentException(
                    "Intercepted bean " + beanClass.getName() + " must not be final." + unproxyable);
        }
        var finalMethod = firstFinalMethod(beanClass);
        if (finalMethod != null) {
            throw new DeploymentException(
                    "Intercepted bean " + beanClass.getName() + " has final method " + finalMethod.getName()
                            + "." + unproxyable);
        }

        // CDI 4.1: no-arg constructor is NOT required for intercepted beans.
        // However, a bean with ONLY a private no-arg constructor is still unproxyable.
        for (var ctor : beanClass.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 0
                    && java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                throw new DeploymentException(
                        "Intercepted bean " + beanClass.getName()
                                + " has only private no-arg constructor (unproxyable)." + unproxyable);
            }
        }
    }

    private void installInterceptedBean(BeanDescriptor descriptor, Class<?> beanClass, Set<DotName> classBindings) {
        Class<?> interceptedClass;
        boolean preGenerated;
        var interceptedName = beanClass.getName() + io.vidocq.vauban.core.interceptor.InterceptedShape.SUBCLASS_SUFFIX;
        try {
            // Prefer a PRE-GENERATED subclass (Vauban APT or Maven plugin). On the strict
            // module path, defining the class at runtime would need deep access into the
            // bean's module (an `opens … to io.vidocq.vauban.core`); an already-compiled
            // sibling on the bean's own loader avoids that entirely.
            interceptedClass = Class.forName(interceptedName, false, beanClass.getClassLoader());
            // Found is not built ahead: an earlier boot sharing this loader may have defined it.
            preGenerated = !DEFINED_AT_RUNTIME.contains(interceptedClass);
        } catch (ClassNotFoundException _) {
            preGenerated = false;
            interceptedClass = defineInterceptedSubclass(beanClass);
        }
        installWrappedBean(descriptor, new InterceptedFactory(descriptor, beanClass, interceptedClass, classBindings),
                preGenerated);
    }

    /** Generates the {@code $$Intercepted} subclass of {@code beanClass} and defines it at boot. */
    private Class<?> defineInterceptedSubclass(Class<?> beanClass) {
        var generated = io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                .generate(beanClass);
        Class<?> interceptedClass;
        if (classDefiner != null) {
            interceptedClass = classDefiner.apply(generated.className(), generated.bytecode());
        } else {
            try {
                interceptedClass = loadOrDefineClassRobustly(beanClass, generated.className(), generated.bytecode());
            } catch (Exception e) {
                throw new DeploymentException(CANNOT_DEFINE_SUBCLASS, e);
            }
        }
        DEFINED_AT_RUNTIME.add(interceptedClass);
        return interceptedClass;
    }

    /**
     * The wrapping of last resort, when the regular one failed: a subclass generated at boot, created through its
     * no-arg constructor.
     */
    private void installFallbackInterceptedBean(BeanDescriptor descriptor, Class<?> beanClass, Set<DotName> bindings) {
        var generated2 = io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                .generate(beanClass);

        Class<?> interceptedClass2;
        try {
            if (classDefiner != null) {
                interceptedClass2 = classDefiner.apply(generated2.className(), generated2.bytecode());
            } else {
                interceptedClass2 = loadOrDefineClassRobustly(beanClass, generated2.className(), generated2.bytecode());
            }
        } catch (Exception ex2) {
            LOG.log(System.Logger.Level.ERROR,
                    "Fallback interception also failed for " + descriptor.beanClass().value(), ex2);
            throw new DeploymentException(CANNOT_DEFINE_SUBCLASS, ex2);
        }
        DEFINED_AT_RUNTIME.add(interceptedClass2);
        installWrappedBean(descriptor, new FallbackInterceptedFactory(descriptor, beanClass, interceptedClass2, bindings),
                false);
    }

    /** Puts a bean created by {@code factory} in place of the bean of {@code descriptor}, keeping its injector and destroyer. */
    private void installWrappedBean(BeanDescriptor descriptor, BeanFactory<?> factory, boolean preGenerated) {
        var interceptedBean = new ManagedBean<>(descriptor, factory, classLoader, vaubanLookup,
                container.qualifierMatcher().types());
        var originalBean = container.beans.get(descriptor.id());
        if (originalBean != null) {
            interceptedBean.setInjector(originalBean.getInjector());
            interceptedBean.setDestroyer(originalBean.getDestroyer());
        }
        interceptedBean.setInterceptorManager(this.interceptorManager);
        interceptedSubclasses.put(descriptor.id(), preGenerated);
        container.beans.put(descriptor.id(), interceptedBean);
    }

    /** Proceeds with an {@code @AroundConstruct} chain; a checked exception becomes a {@link CreationException}. */
    private static void proceedConstruction(VaubanInvocationContext invocation) {
        try {
            invocation.proceed();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CreationException(e);
        }
    }

    /**
     * Creates an intercepted bean: resolves the bean's constructor arguments, runs its {@code @AroundConstruct}
     * chain if any, then instantiates its {@code $$Intercepted} subclass and wires it.
     */
    private final class InterceptedFactory implements BeanFactory<Object> {

        private final BeanDescriptor descriptor;
        private final Class<?> beanClass;
        private final Class<?> interceptedClass;
        private final Set<DotName> bindings;

        InterceptedFactory(BeanDescriptor descriptor, Class<?> beanClass, Class<?> interceptedClass,
                           Set<DotName> bindings) {
            this.descriptor = descriptor;
            this.beanClass = beanClass;
            this.interceptedClass = interceptedClass;
            this.bindings = bindings;
        }

        /**
         * The qualifiers of parameter {@code i} of the bean's constructor: those the bean's
         * descriptor records, as the non-intercepted path takes them, read back from the
         * parameter only when nothing describes it — reading it back is what a module
         * compiled with the processor forbids (vauban#70, BUG-20261007-03).
         */
        private Annotation[] constructorParameterQualifiers(
                Constructor<?> ctor, java.lang.reflect.Parameter[] params, int i) {
            var described = container.describedParameterQualifiers(descriptor, ctor, i);
            return described != null ? described : QualifierHelper.extractParamQualifiers(params[i]);
        }

        @Override
        public Object create() {
            return create((CreationalContext<Object>) null);
        }

        @Override
        public Object create(CreationalContext<Object> ctx) {
            return create(null, ctx);
        }

        @Override
        public Object create(VaubanInvocationContext constructCtx, CreationalContext<Object> callerCtx) {
            var creationalCtx = contextOfItsOwnIfNone(callerCtx);
            try {
                Object[] finalArgs = constructCtx != null ? constructCtx.getParameters() : null;
                var targetCtor = targetConstructor(finalArgs);
                var bindingAnnotations = bindingAnnotations(targetCtor);

                var constructChain = interceptorManager.resolveAroundConstructChain(bindings, targetCtor, beanClass, creationalCtx);

                if (!constructChain.isEmpty() && constructCtx == null) {
                    return createThroughAroundConstruct(targetCtor, constructChain, bindingAnnotations, creationalCtx);
                }

                if (finalArgs == null) {
                    finalArgs = constructorArgs(targetCtor, creationalCtx);
                }

                var subclassCtor = constructorWithArity(interceptedClass, finalArgs.length);
                if (subclassCtor == null) subclassCtor = interceptedClass.getDeclaredConstructors()[0];

                var instance = instantiatePreferProvider(subclassCtor, finalArgs);

                initIntercepted(interceptedClass, instance, interceptorManager, bindings, descriptor.constructorBindings(), creationalCtx);
                return instance;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CreationException(e);
            }
        }

        @Override
        public Object create(VaubanInvocationContext constructCtx) {
            return create(constructCtx, null);
        }

        /** The first constructor of {@code type} with {@code parameterCount} parameters, or {@code null}. */
        private static Constructor<?> constructorWithArity(Class<?> type, int parameterCount) {
            for (var c : type.getDeclaredConstructors()) {
                if (c.getParameterCount() == parameterCount) {
                    return c;
                }
            }
            return null;
        }

        /**
         * The bean constructor to call: the one taking as many arguments as the {@code @AroundConstruct} chain
         * passes, else the {@code @Inject} one, else the one with as many parameters as the descriptor records
         * constructor injection points, else the first.
         */
        private Constructor<?> targetConstructor(Object[] args) {
            if (args != null) {
                var byArity = constructorWithArity(beanClass, args.length);
                if (byArity != null) return byArity;
            }
            var ctorParamCount = descriptor.injectionPoints().stream()
                    .filter(ip -> ip.kind() == io.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER)
                    .count();
            for (var c : beanClass.getDeclaredConstructors()) {
                if (c.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    return c;
                }
            }
            var byPoints = constructorWithArity(beanClass, Math.toIntExact(ctorParamCount));
            return byPoints != null ? byPoints : beanClass.getDeclaredConstructors()[0];
        }

        /**
         * The interceptor bindings of the bean class and of {@code targetCtor}, one per type, the constructor's
         * winning, with the bindings they carry transitively.
         */
        private Set<Annotation> bindingAnnotations(Constructor<?> targetCtor) {
            var bindingAnnotationsByType = new java.util.LinkedHashMap<Class<?>, Annotation>();
            for (var ann : beanClass.getAnnotations()) {
                if (isInterceptorBinding(ann)) {
                    bindingAnnotationsByType.put(ann.annotationType(), ann);
                }
            }
            for (var ann : targetCtor.getAnnotations()) {
                if (isInterceptorBinding(ann)) {
                    bindingAnnotationsByType.put(ann.annotationType(), ann);
                }
            }

            var bindingAnnotations = new java.util.LinkedHashSet<>(bindingAnnotationsByType.values());
            var fullBindings = new java.util.LinkedHashSet<DotName>();
            for (var ann : bindingAnnotations) {
                fullBindings.add(DotName.of(ann.annotationType().getName()));
            }
            BeanLifecycle.collectTransitiveBindings(bindingAnnotations, fullBindings);
            return bindingAnnotations;
        }

        /** The arguments of {@code ctor}, each resolved with the qualifiers of its parameter. */
        private Object[] constructorArgs(Constructor<?> ctor, CreationalContext<Object> creationalCtx) {
            var pTypes = ctor.getParameterTypes();
            var gpTypes = ctor.getGenericParameterTypes();
            var cParams = ctor.getParameters();
            var args = new Object[pTypes.length];
            for (int i = 0; i < pTypes.length; i++) {
                var pQuals = constructorParameterQualifiers(ctor, cParams, i);
                args[i] = container.resolveParameter(pTypes[i], gpTypes[i], creationalCtx, pQuals, ctor);
            }
            return args;
        }

        /** Runs the {@code @AroundConstruct} chain, whose end creates the instance through this factory. */
        private Object createThroughAroundConstruct(Constructor<?> targetCtor,
                                                    List<VaubanInvocationContext.InterceptorInvocation> constructChain,
                                                    Set<Annotation> bindingAnnotations,
                                                    CreationalContext<Object> creationalCtx) {
            final Object[] box = new Object[1];
            var args = constructorArgs(targetCtor, creationalCtx);
            var ctx2 = new VaubanInvocationContext(
                    null, null, targetCtor, args, constructChain,
                    (target, params) -> {
                        var result = new Object[1];
                        InterceptorManager.$$runIntercepted(java.util.Collections.emptyList(), bindings, creationalCtx, () -> {
                            var instance = create(VaubanInvocationContext.dummy(params), creationalCtx);
                            box[0] = instance;
                            result[0] = instance;
                        });
                        return result[0];
                    });
            ctx2.setInterceptorBindings(bindingAnnotations);
            proceedConstruction(ctx2);
            return box[0];
        }
    }

    /**
     * The factory of the fallback wrapping: creates the {@code $$Intercepted} subclass through its no-arg
     * constructor, after the {@code @AroundConstruct} chain when there is one.
     */
    private final class FallbackInterceptedFactory implements BeanFactory<Object> {

        private final BeanDescriptor descriptor;
        private final Class<?> beanClass;
        private final Class<?> interceptedClass;
        private final Set<DotName> bindings;

        FallbackInterceptedFactory(BeanDescriptor descriptor, Class<?> beanClass, Class<?> interceptedClass,
                                   Set<DotName> bindings) {
            this.descriptor = descriptor;
            this.beanClass = beanClass;
            this.interceptedClass = interceptedClass;
            this.bindings = bindings;
        }

        @Override
        public Object create() {
            return create((CreationalContext<Object>) null);
        }

        @Override
        public Object create(CreationalContext<Object> ctx) {
            return create(null, ctx);
        }

        /**
         * Without {@code constructCtx}, runs the {@code @AroundConstruct} chain when there is one, whose end calls
         * back here with its context; with it, or without a chain, instantiates and wires the subclass.
         */
        @Override
        public Object create(VaubanInvocationContext constructCtx, CreationalContext<Object> callerCtx) {
            var ctx = contextOfItsOwnIfNone(callerCtx);
            try {
                var ctor = interceptedClass.getDeclaredConstructor();
                if (constructCtx == null) {
                    var constructChain = interceptorManager.resolveChainForConstructor(bindings,
                            descriptor.constructorBindings(), ctor, ctx);
                    if (!constructChain.isEmpty()) {
                        return createThroughAroundConstruct(constructChain, ctx);
                    }
                }
                var inst = instantiatePreferProvider(ctor, new Object[0]);
                initIntercepted(interceptedClass, inst, interceptorManager, bindings, descriptor.constructorBindings(), ctx);
                return inst;
            } catch (Exception ex) {
                var cause = ex instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : ex;
                if (cause instanceof RuntimeException re) throw re;
                throw new CreationException(cause);
            }
        }

        @Override
        public Object create(VaubanInvocationContext constructCtx) {
            return create(constructCtx, null);
        }

        /**
         * Runs the {@code @AroundConstruct} chain, whose end creates and wires the instance through
         * {@link #create(VaubanInvocationContext, CreationalContext)} with the chain's context.
         */
        private Object createThroughAroundConstruct(List<VaubanInvocationContext.InterceptorInvocation> constructChain,
                                                    CreationalContext<Object> ctx) {
            final Object[] box2 = new Object[1];
            var ctors = beanClass.getDeclaredConstructors();
            var originalCtor2 = ctors[0];
            for (var c : ctors) {
                if (!java.lang.reflect.Modifier.isPrivate(c.getModifiers())) {
                    originalCtor2 = c;
                    break;
                }
            }
            var invocationCtx = new VaubanInvocationContext(
                    null, null, originalCtor2, new Object[originalCtor2.getParameterCount()], constructChain,
                    (target, params) -> {
                        var result2 = new Object[1];
                        InterceptorManager.$$runIntercepted(java.util.Collections.emptyList(), bindings, ctx, () -> {
                            var instance = create(VaubanInvocationContext.dummy(params), ctx);
                            box2[0] = instance;
                            result2[0] = instance;
                        });
                        return result2[0];
                    });
            proceedConstruction(invocationCtx);
            var inst = box2[0];
            if (inst == null) {
                throw new CreationException(
                        "Interceptor chain for @AroundConstruct failed to create an instance for " + interceptedClass.getName());
            }
            return inst;
        }
    }

    void clearProxyCache() {
        proxyCache.clear();
    }
}
