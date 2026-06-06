package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.BeanFactory;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.interceptor.InterceptorManager;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

final class InterceptorBeanWrapper {

    private static final System.Logger LOG = System.getLogger(InterceptorBeanWrapper.class.getName());

    private static final ScopedValue<Boolean> IS_CREATING_INTERCEPTOR = ScopedValue.newInstance();

    private final VaubanContainer container;
    private final VaubanLookup vaubanLookup;
    private final InterceptorManager interceptorManager;
    private final ClassLoader classLoader;
    private final BiFunction<String, byte[], Class<?>> classDefiner;
    private final java.util.Map<io.vidocq.vauban.core.bean.model.BeanId, Object> proxyCache = new java.util.concurrent.ConcurrentHashMap<>();

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
            Object cached = vCtx.getInterceptorInstance(className);
            if (cached != null) {
                return cached;
            }

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

        try {
            var bm = container.getBeanManager();
            var clazz = container.loadClass(className);
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

        if (IS_CREATING_INTERCEPTOR.orElse(false)) {
            try {
                var clazz = container.loadClass(className);

                java.lang.reflect.Constructor<?> constructor = findConstructor(clazz);

                var paramTypes = constructor.getParameterTypes();
                var genericParamTypes = constructor.getGenericParameterTypes();
                var params = constructor.getParameters();
                var args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    var paramQuals = QualifierHelper.extractParamQualifiers(params[i]);
                    if (paramQuals.length > 0) {
                        var bm = container.getBeanManager();
                        var beans2 = bm.getBeans(paramTypes[i], paramQuals);
                        if (!beans2.isEmpty()) {
                            var resolved = bm.resolve(beans2);
                            args[i] = bm.getReference(resolved, paramTypes[i], ctx);
                        } else {
                            args[i] = container.resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                        }
                    } else {
                        args[i] = container.resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                    }
                }

                var instance = vaubanLookup.newInstance(constructor, args);
                container.beanInjector().injectFieldsByReflection(instance, null, ctx);
                java.lang.reflect.Method pc = null;
                for (var m : instance.getClass().getDeclaredMethods()) {
                    if (m.isAnnotationPresent(jakarta.annotation.PostConstruct.class)) { pc = m; break; }
                }
                if (pc != null) { vaubanLookup.invokeMethod(instance, pc); }

                if (ctx instanceof CreationalContextImpl<?> vCtx) {
                    vCtx.addInterceptorInstance(className, instance);
                    vCtx.pushInterceptor(instance);
                }
                return instance;
            } catch (Exception e) {
                throw new jakarta.enterprise.inject.CreationException(e);
            }
        }
        try {
            return ScopedValue.where(IS_CREATING_INTERCEPTOR, true).call(() -> {
                var clazz = container.loadClass(className);

                java.lang.reflect.Constructor<?> constructor = findConstructor(clazz);

                var paramTypes = constructor.getParameterTypes();
                var genericParamTypes = constructor.getGenericParameterTypes();
                var params = constructor.getParameters();
                var args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    var paramQuals = QualifierHelper.extractParamQualifiers(params[i]);
                    if (paramQuals.length > 0) {
                        var bm = container.getBeanManager();
                        var beans2 = bm.getBeans(paramTypes[i], paramQuals);
                        if (!beans2.isEmpty()) {
                            var resolved = bm.resolve(beans2);
                            args[i] = bm.getReference(resolved, paramTypes[i], ctx);
                        } else {
                            args[i] = container.resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                        }
                    } else {
                        args[i] = container.resolveParameter(paramTypes[i], genericParamTypes[i], ctx);
                    }
                }

                var instance = vaubanLookup.newInstance(constructor, args);
                container.injectFields(instance, null, (CreationalContext<Object>) ctx);
                container.callPostConstruct(instance, null, ctx);

                if (ctx instanceof CreationalContextImpl<?> vCtx) {
                    vCtx.addInterceptorInstance(className, instance);
                    vCtx.pushInterceptor(instance);
                } else if (ctx != null) {
                    ((CreationalContext<Object>) ctx).push(instance);
                }

                return instance;
            });
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR,
                    "Failed to create interceptor " + descriptor.interceptorClass(), e);
            if (e instanceof java.lang.reflect.InvocationTargetException ite) {
                LOG.log(System.Logger.Level.ERROR,
                        "Caused by: " + ite.getTargetException(), ite.getTargetException());
            }
            throw new jakarta.enterprise.inject.CreationException("Failed to create interceptor: " + descriptor.interceptorClass(), e);
        }
    }

    private java.lang.reflect.Constructor<?> findConstructor(Class<?> clazz) throws NoSuchMethodException {
        for (var c : clazz.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(jakarta.inject.Inject.class)) {
                return c;
            }
        }
        try {
            return clazz.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            if (clazz.getDeclaredConstructors().length == 1) {
                return clazz.getDeclaredConstructors()[0];
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    <T> T getOrCreateProxy(ManagedBean<T> bean) {
        return (T) proxyCache.computeIfAbsent(bean.descriptor().id(), id -> {
            var beanClass = resolveProxyTargetClass(bean);

            // VAU-PROXY-INTERFACE: when the proxy target is an interface (e.g. a @RequestScoped
            // producer-method bean whose produced type is an interface like JsonWebToken),
            // RuntimeClientProxyGenerator cannot subclass an interface. Use java.lang.reflect.Proxy
            // instead — the proxy lazily resolves the contextual instance per-call.
            if (beanClass.isInterface()) {
                var beanId = bean.descriptor().id();
                java.util.function.Supplier<Object> delegate = () -> {
                    var currentBean = container.beans.get(beanId);
                    if (currentBean == null) currentBean = bean;
                    var scopeClass = currentBean.getScope();
                    var ctx = container.getFirstContext(scopeClass);
                    if (ctx == null) ctx = container.dependentContext();
                    return ctx.get((Contextual<Object>) (Contextual<?>) currentBean,
                            new CreationalContextImpl<Object>());
                };
                // Collect all interface types from the bean to implement
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
            // CDI 4.1: no-arg constructor is NOT required — proxy calls super with default values.
            // However, a bean with ONLY a private no-arg constructor is still unproxyable.
            boolean hasPrivateNoArgCtor = false;
            boolean hasNonPrivateNoArgCtor = false;
            for (var ctor : beanClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 0) {
                    if (java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                        hasPrivateNoArgCtor = true;
                    } else {
                        hasNonPrivateNoArgCtor = true;
                    }
                }
            }
            if (hasPrivateNoArgCtor && !hasNonPrivateNoArgCtor) {
                throw new jakarta.enterprise.inject.UnproxyableResolutionException(
                        "Normal scoped bean " + beanClass.getName()
                                + " has only a private no-arg constructor");
            }

            try {
                var generated = io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator.generate(beanClass);

                Class<?> proxyClass;
                try {
                    proxyClass = loadOrDefineClassRobustly(beanClass, generated.className(), generated.bytecode());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to define proxy class for " + beanClass, e);
                }

                var proxy = proxyClass.getDeclaredConstructor().newInstance();

                var setDelegate = proxyClass.getMethod("$$setDelegate",
                        java.util.function.Supplier.class);
                var beanId = bean.descriptor().id();
                java.util.function.Supplier<Object> delegate = () -> {
                    var currentBean = container.beans.get(beanId);
                    if (currentBean == null) currentBean = bean;
                    var scopeClass = currentBean.getScope();
                    var ctx = container.getFirstContext(scopeClass);
                    if (ctx == null) ctx = container.dependentContext();
                    return ctx.get((Contextual<Object>) (Contextual<?>) currentBean,
                            new CreationalContextImpl<Object>());
                };
                setDelegate.invoke(proxy, delegate);

                return proxy;
            } catch (Exception e) {
                // VAU-INJ-001: never fall back to eager ctx.get() for normal-scoped beans.
                // The context may be inactive at proxy-creation time; doing ctx.get() here
                // would throw ContextNotActiveException and leave the injected field null.
                // For pseudo-scoped beans (Dependent, Singleton) the eager path is safe.
                if (bean.descriptor().scope().isNormal()) {
                    throw new jakarta.enterprise.inject.spi.DeploymentException(
                            "Failed to create client proxy for normal-scoped bean "
                                    + bean.getBeanClass().getName(), e);
                }
                var scopeClass = bean.getScope();
                var ctx = container.getFirstContext(scopeClass);
                if (ctx == null) ctx = container.dependentContext();
                return ctx.get((Contextual<Object>) (Contextual<?>) bean,
                        new CreationalContextImpl<>());
            }
        });
    }

    Class<?> resolveProxyTargetClass(ManagedBean<?> bean) {
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
                    if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                            || VaubanBeanManager.isCustomInterceptorBinding(ann.annotationType())) {
                        return true;
                    }
                }
            }
        } catch (ClassNotFoundException e) { /* skip */ }
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
        for (var m : beanClass.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                        || VaubanBeanManager.isCustomInterceptorBinding(ann.annotationType())) {
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

    /**
     * Instantiates an intercepted subclass, preferring zero reflection. Order:
     * <ol>
     *   <li><b>in-module provider</b> — when the Vauban APT pre-generated {@code <bean>$$Intercepted},
     *       its co-located bytecode {@code _VaubanComponents} runs {@code new <bean>$$Intercepted(args…)}
     *       directly (no reflection, no opens);</li>
     *   <li><b>public Lookup</b> — the generated subclass is a public class with a public constructor
     *       in the bean's exported package, constructible via {@code publicLookup} with no
     *       {@code privateLookupIn} (hence no opens) — covers the runtime/plugin-generated case where
     *       no provider owns the name;</li>
     *   <li><b>private Lookup</b> — last resort for the class path (unrestricted) or a bean in a
     *       non-exported package (which keeps its qualified {@code opens}).</li>
     * </ol>
     */
    private Object instantiateIntercepted(java.lang.reflect.Constructor<?> ctor, Object[] args) {
        var providers = container.componentProviders();
        if (providers != null) {
            var inModule = providers.create(ctor.getDeclaringClass().getName(), args);
            if (inModule != null) return inModule;
        }
        var viaPublic = vaubanLookup.newInstancePublic(ctor, args);
        if (viaPublic != null) return viaPublic;
        return vaubanLookup.newInstance(ctor, args);
    }

    Class<?> loadOrDefineClassRobustly(Class<?> targetClass, String className, byte[] bytecode) throws Exception {
        try {
            return targetClass.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException cnfe) {
            // Use the container's VaubanLookup which has the user-provided root Lookup
            // (no sun.misc.Unsafe needed — addReads + privateLookupIn handles JPMS)
            var lookup = vaubanLookup.lookupFor(targetClass);
            return lookup.defineClass(bytecode);
        }
    }

    @SuppressWarnings("unchecked")
    void wrapInterceptedBeans(List<BeanDescriptor> descriptors,
                              Map<DotName, BeanFactory<?>> factories) {
        for (var descriptor : descriptors) {
            Class<?> currentBeanClassTemp;
            try {
                currentBeanClassTemp = container.loadClass(descriptor.beanClass().value());
            } catch (ClassNotFoundException e) {
                continue;
            }
            final var currentBeanClass = currentBeanClassTemp;
            if (descriptor.kind() != BeanDescriptor.BeanKind.MANAGED) continue;

            try {
                var cls = container.loadClass(descriptor.beanClass().value());
                if (cls.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
                    continue;
                }
            } catch (ClassNotFoundException e) { /* skip */ }

            var bean = container.beans.get(descriptor.id());
            if (bean == null) continue;

            Class<?> beanClass = null;
            Set<DotName> bindings = new java.util.LinkedHashSet<>(descriptor.interceptorBindings());

            if (bindings.isEmpty()) {
                try {
                    var cls = container.loadClass(descriptor.beanClass().value());
                    for (var ann : cls.getAnnotations()) {
                        if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                                || VaubanBeanManager.isCustomInterceptorBinding(ann.annotationType())) {
                            bindings.add(DotName.of(ann.annotationType().getName()));
                        }
                    }
                } catch (ClassNotFoundException ex) { /* skip */ }
                if (bindings.isEmpty() && !interceptorManager.hasInterceptors()) {
                    boolean hasTargetAroundInvoke = false;
                    try {
                        var cls2 = container.loadClass(descriptor.beanClass().value());
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
                beanClass = container.loadClass(descriptor.beanClass().value());

                if (java.lang.reflect.Modifier.isFinal(beanClass.getModifiers())) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                            "Bean class " + beanClass.getName() + " with interceptor bindings must not be final");
                }
                for (var m : beanClass.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isFinal(m.getModifiers())
                            && !java.lang.reflect.Modifier.isPrivate(m.getModifiers())
                            && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        throw new jakarta.enterprise.inject.spi.DeploymentException(
                                "Intercepted bean " + beanClass.getName() + " has final method " + m.getName());
                    }
                }

                // CDI 4.1: no-arg constructor is NOT required for intercepted beans.
                // However, a bean with ONLY a private no-arg constructor is still unproxyable.
                boolean hasPrivateNoArgCtor2 = false;
                for (var ctor : beanClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 0
                            && java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                        hasPrivateNoArgCtor2 = true;
                        break;
                    }
                }
                if (hasPrivateNoArgCtor2) {
                    throw new jakarta.enterprise.inject.spi.DeploymentException(
                            "Intercepted bean " + beanClass.getName()
                                    + " has only private no-arg constructor (unproxyable)");
                }

                interceptorManager.setClassLoader(beanClass.getClassLoader());

                var classBindings = interceptorManager.findBindingsOnClass(beanClass, interceptorManager::isInterceptorBinding);

                classBindings.addAll(bindings);

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

                var matches = interceptorManager.resolveInterceptorDescriptors(classBindings);
                if (matches.isEmpty()) {
                    boolean hasInterceptors = false;
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

                try {
                    Class<?> interceptedClass;
                    var interceptedName = beanClass.getName() + "$$Intercepted";
                    try {
                        // Prefer a PRE-GENERATED subclass (Vauban APT or Maven plugin). On the strict
                        // module path, defining the class at runtime would need deep access into the
                        // bean's module (an `opens … to io.vidocq.vauban.core`); an already-compiled
                        // sibling on the bean's own loader avoids that entirely.
                        interceptedClass = Class.forName(interceptedName, false, beanClass.getClassLoader());
                    } catch (ClassNotFoundException notPreGenerated) {
                        var generated = io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                                .generate(beanClass);
                        if (classDefiner != null) {
                            interceptedClass = classDefiner.apply(generated.className(), generated.bytecode());
                        } else {
                            try {
                                interceptedClass = loadOrDefineClassRobustly(beanClass, generated.className(), generated.bytecode());
                            } catch (Exception e) {
                                throw new jakarta.enterprise.inject.spi.DeploymentException("Could not define interceptor subclass", e);
                            }
                        }
                    }

                    var mgr = this.interceptorManager;
                    var bds = classBindings;
                    var ctorBds = descriptor.constructorBindings();
                    var originalFactory = container.beans.get(descriptor.id()).factory();

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
                public Object create(io.vidocq.vauban.core.interceptor.VaubanInvocationContext constructCtx, jakarta.enterprise.context.spi.CreationalContext<Object> creationalCtx) {
                    try {
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
                            var ctorParams = descriptor.injectionPoints().stream()
                                    .filter(ip -> ip.kind() == io.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER)
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

                        var bindingAnnotationsByType = new java.util.LinkedHashMap<Class<?>, java.lang.annotation.Annotation>();
                        for (var ann : finalBeanClass.getAnnotations()) {
                             if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                                     || VaubanBeanManager.isCustomInterceptorBinding(ann.annotationType())) {
                                 bindingAnnotationsByType.put(ann.annotationType(), ann);
                             }
                        }
                        for (var ann : targetCtor.getAnnotations()) {
                            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                                    || VaubanBeanManager.isCustomInterceptorBinding(ann.annotationType())) {
                                bindingAnnotationsByType.put(ann.annotationType(), ann);
                            }
                        }

                        var bindingAnnotations = new java.util.LinkedHashSet<java.lang.annotation.Annotation>(bindingAnnotationsByType.values());
                        var fullBindings = new java.util.LinkedHashSet<DotName>();
                        for (var ann : bindingAnnotations) {
                            fullBindings.add(DotName.of(ann.annotationType().getName()));
                        }
                        BeanLifecycle.collectTransitiveBindings(bindingAnnotations, fullBindings);

                        var constructChain = mgr.resolveAroundConstructChain(bds, targetCtor, finalBeanClass, (jakarta.enterprise.context.spi.CreationalContext<?>) creationalCtx);

                        if (!constructChain.isEmpty() && constructCtx == null) {
                            final Object[] box = new Object[1];
                            final java.lang.reflect.Constructor<?> finalTargetCtor = targetCtor;

                            if (finalArgs == null) {
                                var pTypes = finalTargetCtor.getParameterTypes();
                                var gpTypes = finalTargetCtor.getGenericParameterTypes();
                                var cParams = finalTargetCtor.getParameters();
                                finalArgs = new Object[pTypes.length];
                                for (int i = 0; i < pTypes.length; i++) {
                                    var pQuals = QualifierHelper.extractParamQualifiers(cParams[i]);
                                    finalArgs[i] = container.resolveParameter(pTypes[i], gpTypes[i], creationalCtx, pQuals, finalTargetCtor);
                                }
                            }

                            var ctx2 = new io.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                                    null, null, finalTargetCtor, finalArgs, constructChain,
                                    (target, params) -> {
                                        var result = new Object[1];
                                        InterceptorManager.$$runIntercepted(java.util.Collections.emptyList(), bds, (jakarta.enterprise.context.spi.CreationalContext<?>) (Object) creationalCtx, () -> {
                                            var instance = create((io.vidocq.vauban.core.interceptor.VaubanInvocationContext) io.vidocq.vauban.core.interceptor.VaubanInvocationContext.dummy(params), (jakarta.enterprise.context.spi.CreationalContext<Object>) (Object) creationalCtx);
                                            box[0] = instance;
                                            result[0] = instance;
                                        });
                                        return result[0];
                                    });
                            ctx2.setInterceptorBindings(bindingAnnotations);
                            try {
                                ctx2.proceed();
                            } catch (jakarta.enterprise.inject.spi.DeploymentException | jakarta.enterprise.inject.CreationException e) {
                                throw e;
                            } catch (Exception e) {
                                if (e instanceof RuntimeException re) {
                                    throw re;
                                }
                                throw new jakarta.enterprise.inject.CreationException(e);
                            }
                            return box[0];
                        }

                        if (finalArgs == null) {
                            var targetCtorToUse = (targetCtor != null) ? targetCtor : finalBeanClass.getDeclaredConstructors()[0];
                            var pTypes = targetCtorToUse.getParameterTypes();
                            var gpTypes = targetCtorToUse.getGenericParameterTypes();
                            var cParams = targetCtorToUse.getParameters();
                            finalArgs = new Object[pTypes.length];
                            for (int i = 0; i < pTypes.length; i++) {
                                var pQuals = QualifierHelper.extractParamQualifiers(cParams[i]);
                                finalArgs[i] = container.resolveParameter(pTypes[i], gpTypes[i], creationalCtx, pQuals, targetCtorToUse);
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

                        var instance = instantiateIntercepted(subclassCtor, finalArgs);

                        var initMethod = finalInterceptedClass.getMethod("$$init",
                                io.vidocq.vauban.core.interceptor.InterceptorManager.class,
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
                public Object create(io.vidocq.vauban.core.interceptor.VaubanInvocationContext constructCtx) {
                    return create(constructCtx, null);
                }
            };

                    var originalBean = (ManagedBean<?>) container.beans.get(descriptor.id());
                    var interceptedBean = new ManagedBean<>(descriptor, interceptedFactory, classLoader, vaubanLookup);
                    if (originalBean != null) {
                        interceptedBean.setInjector((java.util.function.BiConsumer) originalBean.getInjector());
                        interceptedBean.setDestroyer((java.util.function.BiConsumer) originalBean.getDestroyer());
                    }
                    interceptedBean.setInterceptorManager(this.interceptorManager);
                    container.beans.put(descriptor.id(), interceptedBean);
                } catch (Exception e) {
                    if (e instanceof jakarta.enterprise.inject.spi.DeploymentException de) throw de;
                    if (e instanceof jakarta.enterprise.inject.spi.DefinitionException de) throw de;
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
                                    var invocationCtx = new io.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                                            null, null, originalCtor2, new Object[originalCtor2.getParameterCount()], constructChain2,
                                            (target, params) -> {
                                                var result2 = new Object[1];
                                                InterceptorManager.$$runIntercepted(java.util.Collections.emptyList(), bds2, ctx, () -> {
                                                    var instance = ((io.vidocq.vauban.core.BeanFactory<Object>) this).create((io.vidocq.vauban.core.interceptor.VaubanInvocationContext) io.vidocq.vauban.core.interceptor.VaubanInvocationContext.dummy(params), ctx);
                                                    box2[0] = instance;
                                                    result2[0] = instance;
                                                });
                                                return result2[0];
                                            });
                                    try {
                                        invocationCtx.proceed();
                                    } catch (RuntimeException re) {
                                        throw re;
                                    } catch (Exception e2) {
                                        throw new jakarta.enterprise.inject.CreationException(e2);
                                    }
                                    var inst = box2[0];
                                    if (inst == null) {
                                        throw new jakarta.enterprise.inject.CreationException(
                                                "Interceptor chain for @AroundConstruct failed to create an instance for " + finalInterceptedClass.getName());
                                    }
                                    finalInterceptedClass.getMethod("$$init",
                                            io.vidocq.vauban.core.interceptor.InterceptorManager.class,
                                            java.util.Set.class,
                                            java.util.Set.class,
                                            jakarta.enterprise.context.spi.CreationalContext.class).invoke(inst, mgr2, bds2, descriptor.constructorBindings(), ctx);
                                    return inst;
                            } else {
                            var ctor2 = finalInterceptedClass.getDeclaredConstructor();
                            Object[] finalArgs2 = new Object[0];
                            var inst = instantiateIntercepted(ctor2, finalArgs2);
                                    finalInterceptedClass.getMethod("$$init",
                                            io.vidocq.vauban.core.interceptor.InterceptorManager.class,
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
                        var ib2 = new ManagedBean<>(descriptor, f2, classLoader, vaubanLookup);
                        var originalBean2 = (ManagedBean<?>) container.beans.get(descriptor.id());
                        if (originalBean2 != null) {
                            ib2.setInjector((java.util.function.BiConsumer) originalBean2.getInjector());
                            ib2.setDestroyer((java.util.function.BiConsumer) originalBean2.getDestroyer());
                        }
                        ib2.setInterceptorManager(this.interceptorManager);
                        container.beans.put(descriptor.id(), ib2);
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

    void clearProxyCache() {
        proxyCache.clear();
    }
}
