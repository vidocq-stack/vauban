package fr.vidocq.vauban.core.interceptor;

import fr.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import fr.vidocq.vauban.indexer.model.DotName;

import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Manages interceptor resolution and instance lifecycle.
 */
@SuppressWarnings("java:S100") // $$ methods are CDI container conventions for generated code
public final class InterceptorManager {

    private final List<InterceptorDescriptor> interceptors;
    private final Map<DotName, Object> interceptorInstances = new LinkedHashMap<>();
    private BiFunction<InterceptorDescriptor, CreationalContext<?>, Object> instanceFactory;

    private static final ThreadLocal<Boolean> IS_INTERCEPTING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<List<VaubanInvocationContext.InterceptorInvocation>> CURRENT_CHAIN = new ThreadLocal<>();
    private static final ThreadLocal<Set<DotName>> CURRENT_BINDINGS = new ThreadLocal<>();
    private static final ThreadLocal<CreationalContext<?>> CURRENT_CONTEXT = new ThreadLocal<>();

    // Enhanced interceptor binding annotations added via BCE Enhancement (not present in bytecode)
    private final Map<String, List<java.lang.annotation.Annotation>> enhancedBindings = new LinkedHashMap<>();

    public void registerEnhancedBindings(String beanClassName, List<java.lang.annotation.Annotation> annotations) {
        if (annotations != null && !annotations.isEmpty()) {
            enhancedBindings.computeIfAbsent(beanClassName, k -> new ArrayList<>()).addAll(annotations);
        }
    }

    public List<java.lang.annotation.Annotation> getEnhancedBindings(String beanClassName) {
        return enhancedBindings.getOrDefault(beanClassName, List.of());
    }

    // Singleton reference for VaubanInvocationContext to access enhanced bindings
    // Volatile reference: assigned once at container init, read-only after
    @SuppressWarnings("java:S3077")
    private static volatile InterceptorManager currentInstance;

    public static InterceptorManager currentInstance() {
        return currentInstance;
    }

    {
        currentInstance = this;
    }

    public static boolean $$isIntercepting() {
        return IS_INTERCEPTING.get();
    }

    public static List<VaubanInvocationContext.InterceptorInvocation> $$getAroundConstructChain() {
        return CURRENT_CHAIN.get();
    }

    public static Set<DotName> $$getAroundConstructBindings() {
        return CURRENT_BINDINGS.get();
    }

    public static CreationalContext<?> $$getAroundConstructContext() {
        return CURRENT_CONTEXT.get();
    }

    public static void $$beginInterception(List<VaubanInvocationContext.InterceptorInvocation> chain, Set<DotName> bindings) {
        $$beginInterception(chain, bindings, null);
    }

    public static void $$beginInterception(List<VaubanInvocationContext.InterceptorInvocation> chain, Set<DotName> bindings, CreationalContext<?> ctx) {
        IS_INTERCEPTING.set(true);
        CURRENT_CHAIN.set(chain);
        CURRENT_BINDINGS.set(bindings);
        CURRENT_CONTEXT.set(ctx);
    }

    public static void $$endInterception() {
        IS_INTERCEPTING.remove();
        CURRENT_CHAIN.remove();
        CURRENT_BINDINGS.remove();
        CURRENT_CONTEXT.remove();
    }

    public InterceptorManager(List<InterceptorDescriptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
    }

    public void setInstanceFactory(BiFunction<InterceptorDescriptor, CreationalContext<?>, Object> factory) {
        this.instanceFactory = factory;
    }

    private void collectBindingsRecursively(java.lang.annotation.Annotation[] annotations, 
                                            Map<Class<? extends java.lang.annotation.Annotation>, java.lang.annotation.Annotation> result, 
                                            Set<Class<? extends java.lang.annotation.Annotation>> visited) {
        for (var ann : annotations) {
            var type = ann.annotationType();
            if (visited.add(type)) {
                if (type.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                    result.put(type, ann);
                }
                // Transitive bindings and Stereotypes
                if (type.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class) 
                    || type.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                    collectBindingsRecursively(type.getAnnotations(), result, visited);
                }
            }
        }
    }

    private Map<Class<? extends java.lang.annotation.Annotation>, java.lang.annotation.Annotation> collectAllBindings(Class<?> beanClass) {
        var result = new java.util.LinkedHashMap<Class<? extends java.lang.annotation.Annotation>, java.lang.annotation.Annotation>();
        var visited = new java.util.HashSet<Class<? extends java.lang.annotation.Annotation>>();

        // Collect from the bean class itself (all bindings)
        collectBindingsRecursively(beanClass.getAnnotations(), result, visited);

        // Collect from superclasses (only @Inherited bindings — CDI spec)
        var current = beanClass.getSuperclass();
        while (current != null && current != Object.class) {
            for (var ann : current.getAnnotations()) {
                if (ann.annotationType().isAnnotationPresent(java.lang.annotation.Inherited.class)
                        || isInterceptorBindingViaStereotype(ann)) {
                    collectBindingsRecursively(new java.lang.annotation.Annotation[]{ann}, result, visited);
                }
            }
            current = current.getSuperclass();
        }

        return result;
    }

    private boolean isInterceptorBindingViaStereotype(java.lang.annotation.Annotation ann) {
        // Check if annotation is a stereotype that carries interceptor bindings
        return ann.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class);
    }

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private void addLifecycleInvocations(Class<?> clazz, Object target, Class<? extends java.lang.annotation.Annotation> annotation, List<VaubanInvocationContext.InterceptorInvocation> chain) {
        if (clazz == null || clazz == Object.class) return;

        // Collect lifecycle methods from hierarchy, respecting override rules
        var methods = new java.util.ArrayList<java.lang.reflect.Method>();
        collectLifecycleMethods(clazz, annotation, methods);

        for (var method : methods) {
            method.setAccessible(true);
            chain.add(new VaubanInvocationContext.InterceptorInvocation(target, method));
        }
    }

    private void collectLifecycleMethods(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotation,
            java.util.List<java.lang.reflect.Method> result) {
        if (clazz == null || clazz == Object.class) return;

        // Process superclass first (CDI spec: superclass methods called first)
        collectLifecycleMethods(clazz.getSuperclass(), annotation, result);

        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                // Check if this method is already in the list from a superclass
                // If so, remove the superclass version (this one overrides it)
                result.removeIf(m -> m.getName().equals(method.getName())
                        && java.util.Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
                result.add(method);
            } else {
                // If subclass overrides a lifecycle method WITHOUT the annotation,
                // the lifecycle callback is disabled (CDI spec)
                result.removeIf(m -> m.getName().equals(method.getName())
                        && java.util.Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
            }
        }
    }

    /**
     * Resolve matching interceptor descriptors without creating instances.
     * Use this during boot phase to check if a bean needs interception.
     */
    public List<InterceptorDescriptor> resolveInterceptorDescriptors(Set<DotName> bindings) {
        if (bindings == null || bindings.isEmpty()) return List.of();
        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            if (bindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                matches.add(descriptor);
            }
        }
        return matches;
    }

    @SuppressWarnings("java:S135")
    public List<InterceptorDescriptor> resolveInterceptorDescriptorsForMethod(
            Set<DotName> classBindings, java.lang.reflect.Method method) {
        if (method == null) return List.of();
        
        Class<?> beanClass = method.getDeclaringClass();
        if (beanClass.getName().contains("$$Intercepted")) {
            beanClass = beanClass.getSuperclass();
        }

        var bindingsMap = collectAllBindings(beanClass);
        var methodName = method.getName();
        if (methodName.startsWith("$$super$")) {
            methodName = methodName.substring("$$super$".length());
        }
        
        var current = beanClass;
        while (current != null && current != Object.class) {
            try {
                var originalMethod = current.getDeclaredMethod(methodName, method.getParameterTypes());
                collectBindingsRecursively(originalMethod.getAnnotations(), bindingsMap, new java.util.HashSet<>());
                break;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        
        var allBindingNames = new java.util.LinkedHashSet<DotName>();
        for (var type : bindingsMap.keySet()) {
            allBindingNamesAdd(allBindingNames, type.getName());
        }
        allBindingNames.addAll(classBindings);

        var beanAnnotations = new java.util.ArrayList<>(bindingsMap.values());

        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            if (allBindingNames.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()
                        && !bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                    continue;
                }
                matches.add(descriptor);
            }
        }
        return matches;
    }

    @SuppressWarnings("java:S135")
    public List<InterceptorDescriptor> resolveInterceptorDescriptorsAroundConstruct(
            Set<DotName> classBindings, java.lang.reflect.Constructor<?> constructor, Class<?> beanClass,
            List<java.lang.annotation.Annotation> beanAnnotations) {
        if (beanClass.getName().contains("$$Intercepted")) {
            beanClass = beanClass.getSuperclass();
        }
        
        var bindingsMap = collectAllBindings(beanClass);
        if (constructor != null) {
            collectBindingsRecursively(constructor.getAnnotations(), bindingsMap, new java.util.HashSet<>());
        }
        
        var allBindings = new java.util.LinkedHashSet<DotName>();
        for (var type : bindingsMap.keySet()) {
            allBindingNamesAdd(allBindings, type.getName());
        }
        allBindings.addAll(classBindings);
        
        var currentBeanAnnotations = new java.util.ArrayList<>(bindingsMap.values());
        currentBeanAnnotations.addAll(beanAnnotations);

        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            if (allBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                if (!descriptor.bindingAnnotations().isEmpty() && !currentBeanAnnotations.isEmpty()
                        && !bindingMembersMatch(descriptor.bindingAnnotations(), currentBeanAnnotations)) {
                    continue;
                }
                matches.add(descriptor);
            }
        }
        return matches;
    }

    /**
     * Resolve the interceptor chain for a constructor.
     */
    @SuppressWarnings("java:S135")
    public List<VaubanInvocationContext.InterceptorInvocation> resolveAroundConstructChain(
            Set<DotName> classBindings, java.lang.reflect.Constructor<?> constructor, Class<?> beanClass, CreationalContext<?> ctx) {
        if (beanClass.getName().contains("$$Intercepted")) {
            beanClass = beanClass.getSuperclass();
        }
        
        var bindingsMap = collectAllBindings(beanClass);
        if (constructor != null) {
            collectBindingsRecursively(constructor.getAnnotations(), bindingsMap, new java.util.HashSet<>());
        }
        
        var allBindings = new java.util.LinkedHashSet<DotName>();
        if (classBindings != null) {
            allBindings.addAll(classBindings);
        }
        for (var type : bindingsMap.keySet()) {
            allBindingNamesAdd(allBindings, type.getName());
        }
        
        var beanAnnotations = new java.util.ArrayList<>(bindingsMap.values());

        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            if (allBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()
                        && !bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                    continue;
                }
                matches.add(descriptor);
            }
        }

        // Sort by priority
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            var instance = getOrCreateInstance(descriptor, ctx);
            addLifecycleInvocations(instance.getClass(), instance, jakarta.interceptor.AroundConstruct.class, chain);
        }
        
        // Target class @AroundConstruct methods are invoked last
        addLifecycleInvocations(beanClass, null, jakarta.interceptor.AroundConstruct.class, chain);

        return chain;
    }

    private void allBindingNamesAdd(Set<DotName> allBindings, String name) {
        allBindings.add(DotName.of(name));
    }

    public boolean isInterceptorBinding(DotName name) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(name.value(), false, cl)
                    : Class.forName(name.value());
            return clazz.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Find interceptors that apply to a bean method based on binding annotations.
     * Combines class-level and method-level bindings.
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChainForMethod(
            Set<DotName> classBindings, java.lang.reflect.Method method, CreationalContext<?> ctx) {
        return resolveChainForMethod(classBindings, method, null, ctx);
    }

    /**
     * Resolve the interceptor chain for a method, including target class @AroundInvoke methods.
     *
     * @param classBindings class-level interceptor bindings
     * @param method the target method (or $$super$ bridge)
     * @param target the bean instance (for target class @AroundInvoke); may be null
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChainForMethod(
            Set<DotName> classBindings, java.lang.reflect.Method method, Object target, CreationalContext<?> ctx) {
        Class<?> beanClass = null;
        if (method != null) {
            // Use the target class if available, as class-level bindings on the bean
            // apply to all its methods, including those inherited from superclasses.
            beanClass = target != null ? target.getClass() : method.getDeclaringClass();
            if (beanClass.getName().contains("$$Intercepted")) {
                beanClass = beanClass.getSuperclass();
            }

            var bindingsMap = collectAllBindings(beanClass);

            var methodName = method.getName();
            if (methodName.startsWith("$$super$")) {
                methodName = methodName.substring("$$super$".length());
            }
            var current = beanClass;
            while (current != null && current != Object.class) {
                try {
                    var originalMethod = current.getDeclaredMethod(methodName, method.getParameterTypes());
                    collectBindingsRecursively(originalMethod.getAnnotations(), bindingsMap, new java.util.HashSet<>());
                    break;
                } catch (NoSuchMethodException e) {
                    current = current.getSuperclass();
                }
            }
            
            var allBindingNames = new java.util.LinkedHashSet<DotName>();
            for (var type : bindingsMap.keySet()) {
                allBindingNames.add(DotName.of(type.getName()));
            }
            allBindingNames.addAll(classBindings);
            
            var beanAnnotations = new java.util.ArrayList<>(bindingsMap.values());
            var chain = resolveChain(allBindingNames, beanAnnotations, ctx);

            // CDI spec: target class @AroundInvoke methods are invoked last, after external interceptors
            if (target != null && beanClass != null) {
                addLifecycleInvocations(beanClass, target, jakarta.interceptor.AroundInvoke.class, chain);
            }

            return chain;
        }
        return resolveChain(classBindings, List.of(), ctx);
    }

    /**
     * Resolve the interceptor chain for a constructor, including target class @AroundConstruct methods.
     * CDI 4.1 Section 9.5.2.
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChainForConstructor(
            Set<DotName> classBindings, Set<DotName> constructorBindings, java.lang.reflect.Constructor<?> constructor, CreationalContext<?> ctx) {
        Class<?> beanClass = constructor.getDeclaringClass();
        if (beanClass.getName().contains("$$Intercepted")) {
            beanClass = beanClass.getSuperclass();
        }
        
        var bindingsMap = collectAllBindings(beanClass);
        collectBindingsRecursively(constructor.getAnnotations(), bindingsMap, new java.util.HashSet<>());
        
        var allBindingNames = new java.util.LinkedHashSet<DotName>();
        for (var type : bindingsMap.keySet()) {
            allBindingNames.add(DotName.of(type.getName()));
        }
        allBindingNames.addAll(classBindings);
        allBindingNames.addAll(constructorBindings);
        
        var beanAnnotations = new java.util.ArrayList<>(bindingsMap.values());
        var chain = resolveChainAroundConstruct(allBindingNames, beanAnnotations, ctx);

        // CDI spec: target class @AroundConstruct methods are invoked last
        addLifecycleInvocations(beanClass, null, jakarta.interceptor.AroundConstruct.class, chain);

        return chain;
    }

    @SuppressWarnings("java:S135")
    private List<VaubanInvocationContext.InterceptorInvocation> resolveChainAroundConstruct(
            Set<DotName> bindings, List<java.lang.annotation.Annotation> beanAnnotations, CreationalContext<?> ctx) {
        var effectiveCtx = ctx != null ? ctx : $$getAroundConstructContext();
        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            if (bindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()
                        && !bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                    continue;
                }
                matches.add(descriptor);
            }
        }
        // Sort by priority (CDI spec 9.5.2)
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            var instance = getOrCreateInstance(descriptor, effectiveCtx);
            addLifecycleInvocations(instance.getClass(), instance, jakarta.interceptor.AroundConstruct.class, chain);
        }
        return chain;
    }

    /**
     * Find interceptors that apply to a bean method based on binding annotations.
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChain(
            Set<DotName> methodBindings, CreationalContext<?> ctx) {
        return resolveChain(methodBindings, List.of(), ctx);
    }

    /**
     * Find interceptors with member value comparison.
     */
    @SuppressWarnings("java:S135")
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChain(
            Set<DotName> methodBindings, List<java.lang.annotation.Annotation> beanAnnotations, CreationalContext<?> ctx) {
        var effectiveCtx = ctx != null ? ctx : $$getAroundConstructContext();
        var matches = new ArrayList<InterceptorDescriptor>();

        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            // An interceptor matches if all its bindings are present on the target
            if (methodBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                // Check binding member values if both sides have annotation instances
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()
                        && !bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                    continue;
                }
                matches.add(descriptor);
            }
        }


        // Sort by priority (CDI spec 9.5.2)
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            if (effectiveCtx != null) {
                var instance = getOrCreateInstance(descriptor, effectiveCtx);
                addLifecycleInvocations(instance.getClass(), instance, jakarta.interceptor.AroundInvoke.class, chain);
            } else {
                chain.add(new VaubanInvocationContext.InterceptorInvocation(null, null));
            }
        }
        return chain;
    }

    /**
     * CDI spec: interceptor binding member values must match, except @Nonbinding members.
     */
    private boolean bindingMembersMatch(List<java.lang.annotation.Annotation> interceptorBindings,
            List<java.lang.annotation.Annotation> beanBindings) {
        if (interceptorBindings.isEmpty()) return true;
        for (var interceptorBinding : interceptorBindings) {
            var matchingBeanBindings = beanBindings.stream()
                    .filter(b -> b.annotationType() == interceptorBinding.annotationType())
                    .toList();
            if (matchingBeanBindings.isEmpty()) {
                return false; // Must match presence and members
            }
            
            boolean matched = false;
            for (var beanBinding : matchingBeanBindings) {
                if (annotationMembersEqual(interceptorBinding, beanBinding)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean annotationMembersEqual(java.lang.annotation.Annotation a, java.lang.annotation.Annotation b) {
        for (var method : a.annotationType().getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) continue;
            try {
                var va = method.invoke(a);
                var vb = method.invoke(b);
                if (!java.util.Objects.deepEquals(va, vb)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find interceptors for lifecycle callbacks (@PostConstruct/@PreDestroy).
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveLifecycleChain(
            Set<DotName> methodBindings, Class<? extends java.lang.annotation.Annotation> lifecycleAnnotation, CreationalContext<?> ctx) {
        return resolveLifecycleChain(methodBindings, lifecycleAnnotation, List.of(), ctx);
    }

    @SuppressWarnings("java:S135")
    public List<VaubanInvocationContext.InterceptorInvocation> resolveLifecycleChain(
            Set<DotName> methodBindings, Class<? extends java.lang.annotation.Annotation> lifecycleAnnotation,
            List<java.lang.annotation.Annotation> beanAnnotations, CreationalContext<?> ctx) {
        // Use AroundConstruct context if none provided (e.g. for PostConstruct called after constructor interception)
        var effectiveCtx = ctx != null ? ctx : $$getAroundConstructContext();
        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            boolean containsAll = methodBindings.containsAll(descriptor.bindings());

            if (containsAll && !descriptor.bindings().isEmpty()) {
                // Check binding member values if available
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()
                        && !bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                    continue;
                }
                matches.add(descriptor);
            }
        }

        // Sort by priority (CDI spec 9.5.2)
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            if (effectiveCtx != null) {
                var instance = getOrCreateInstance(descriptor, effectiveCtx);
                addLifecycleInvocations(instance.getClass(), instance, lifecycleAnnotation, chain);
            } else {
                chain.add(new VaubanInvocationContext.InterceptorInvocation(null, null));
            }
        }

        return chain;
    }

    /**
     * Share interceptor instances with the provided creational context.
     */
    public void shareInstances(CreationalContext<?> ctx) {
        if (ctx instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> vCtx) {
            for (var entry : interceptorInstances.entrySet()) {
                vCtx.addInterceptorInstance(entry.getKey().toString(), entry.getValue());
            }
        }
    }

    /**
     * Resolve interceptor descriptors matching the given bindings.
     */
    public List<InterceptorDescriptor> resolveInterceptors(Set<DotName> bindings) {
        var result = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;

            if (bindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                result.add(descriptor);
            }
        }
        return result;
    }

    public List<InterceptorDescriptor> resolveInterceptors(List<java.lang.annotation.Annotation> beanAnnotations) {
        var result = new ArrayList<InterceptorDescriptor>();
        var bindings = beanAnnotations.stream()
                .map(a -> DotName.of(a.annotationType().getName()))
                .collect(java.util.stream.Collectors.toSet());

        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;

            if (bindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                if (!descriptor.bindingAnnotations().isEmpty()) {
                    if (bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                        result.add(descriptor);
                    }
                } else {
                    result.add(descriptor);
                }
            }
        }
        return result;
    }

    /**
     * Find binding annotations on a bean class (annotations that are themselves @InterceptorBinding).
     */
    public Set<DotName> findBindingsOnClass(Class<?> beanClass, Predicate<DotName> isBinding) {
        var bindings = new LinkedHashSet<DotName>();
        var current = beanClass;
        while (current != null && current != Object.class) {
            for (var ann : current.getAnnotations()) {
                var annName = DotName.of(ann.annotationType().getName());
                if (isBinding.test(annName)) {
                    bindings.add(annName);
                }
            }
            current = current.getSuperclass();
        }
        return bindings;
    }

    /**
     * Set a ClassLoader to use for loading interceptor classes.
     * Required when beans are loaded by a custom ClassLoader (e.g., TCK).
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    private ClassLoader classLoader;

    public Object getOrCreateInstance(InterceptorDescriptor descriptor, CreationalContext<?> ctx) {
        if (instanceFactory != null) {
            return instanceFactory.apply(descriptor, ctx);
        }
        
        String className = descriptor.interceptorClass().value();
        if (ctx instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> vCtx) {
            Object instance = vCtx.getInterceptorInstance(className);
            if (instance != null) {
                return instance;
            }
        }

        try {
            var cl = classLoader != null ? classLoader
                    : Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(className, true, cl)
                                   : Class.forName(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            
            if (ctx instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> vCtx) {
                vCtx.addInterceptorInstance(className, instance);
                // Register for destruction - interceptors are destroyed with the bean
                vCtx.pushInterceptor(instance); 
            }
            
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create interceptor: " + className, e);
        }
    }

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private Method findAnnotatedMethod(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotation) {
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(annotation)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public boolean hasInterceptors() {
        return !interceptors.isEmpty();
    }

    public List<InterceptorDescriptor> getInterceptors() {
        return interceptors;
    }
}
