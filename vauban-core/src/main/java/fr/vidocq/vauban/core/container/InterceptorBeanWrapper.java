package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.context.CreationalContextImpl;
import fr.vidocq.vauban.core.interceptor.InterceptorManager;
import fr.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import fr.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

final class InterceptorBeanWrapper {

    private static final ThreadLocal<Boolean> isCreatingInterceptor = ThreadLocal.withInitial(() -> false);

    private final VaubanContainer container;
    private final VaubanLookup vaubanLookup;
    private final InterceptorManager interceptorManager;
    private final ClassLoader classLoader;
    private final BiFunction<String, byte[], Class<?>> classDefiner;
    private final java.util.Map<fr.vidocq.vauban.core.bean.model.BeanId, Object> proxyCache = new java.util.concurrent.ConcurrentHashMap<>();

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

        if (isCreatingInterceptor.get()) {
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
        isCreatingInterceptor.set(true);
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
            container.injectFields(instance, null, (CreationalContext<Object>) ctx);
            container.callPostConstruct(instance, null, ctx);

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
            for (var type : bean.getTypes()) {
                if (type instanceof Class<?> c && c != Object.class && !c.isInterface()) {
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
                    if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
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

    @SuppressWarnings({"unchecked", "removal"})
    Class<?> loadOrDefineClassRobustly(Class<?> targetClass, String className, byte[] bytecode) throws Exception {
        try {
            return targetClass.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException cnfe) {
            try {
                var lookup = java.lang.invoke.MethodHandles.privateLookupIn(targetClass, java.lang.invoke.MethodHandles.lookup());
                return lookup.defineClass(bytecode);
            } catch (IllegalAccessException e) {
                try {
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
                        if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
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

                boolean hasAccessibleNoArgCtor = false;
                for (var ctor : beanClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 0
                            && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                        hasAccessibleNoArgCtor = true;
                        break;
                    }
                }
                if (!hasAccessibleNoArgCtor) {
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

                var generated = fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                        .generate(beanClass);

                try {
                    Class<?> interceptedClass;
                    if (classDefiner != null) {
                        interceptedClass = classDefiner.apply(generated.className(), generated.bytecode());
                    } else {
                        try {
                            interceptedClass = loadOrDefineClassRobustly(beanClass, generated.className(), generated.bytecode());
                        } catch (Exception e) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException("Could not define interceptor subclass", e);
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
                public Object create(fr.vidocq.vauban.core.interceptor.VaubanInvocationContext constructCtx, jakarta.enterprise.context.spi.CreationalContext<Object> creationalCtx) {
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

                            var ctx2 = new fr.vidocq.vauban.core.interceptor.VaubanInvocationContext(
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

                        var instance = vaubanLookup.newInstance(subclassCtor, finalArgs);

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
                        var generated2 = fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator
                                .generate(beanClass);

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
                                    } catch (Exception e2) {
                                        throw new jakarta.enterprise.inject.CreationException(e2);
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
                            var ctor2 = finalInterceptedClass.getDeclaredConstructor();
                            Object[] finalArgs2 = new Object[0];
                            var inst = vaubanLookup.newInstance(ctor2, finalArgs2);
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
