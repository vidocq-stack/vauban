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
public final class InterceptorManager {

    private final List<InterceptorDescriptor> interceptors;
    private final Map<DotName, Object> interceptorInstances = new LinkedHashMap<>();
    private BiFunction<InterceptorDescriptor, CreationalContext<?>, Object> instanceFactory;

    private static final ThreadLocal<Boolean> IS_INTERCEPTING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<List<VaubanInvocationContext.InterceptorInvocation>> CURRENT_CHAIN = new ThreadLocal<>();
    private static final ThreadLocal<Set<DotName>> CURRENT_BINDINGS = new ThreadLocal<>();

    public static boolean $$isIntercepting() {
        return IS_INTERCEPTING.get();
    }

    public static List<VaubanInvocationContext.InterceptorInvocation> $$getAroundConstructChain() {
        return CURRENT_CHAIN.get();
    }

    public static Set<DotName> $$getAroundConstructBindings() {
        return CURRENT_BINDINGS.get();
    }

    public static void $$beginInterception(List<VaubanInvocationContext.InterceptorInvocation> chain, Set<DotName> bindings) {
        IS_INTERCEPTING.set(true);
        CURRENT_CHAIN.set(chain);
        CURRENT_BINDINGS.set(bindings);
    }

    public static void $$endInterception() {
        IS_INTERCEPTING.set(false);
        CURRENT_CHAIN.remove();
        CURRENT_BINDINGS.remove();
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
        
        var current = beanClass;
        while (current != null && current != Object.class) {
            collectBindingsRecursively(current.getAnnotations(), result, visited);
            current = current.getSuperclass();
        }
        
        return result;
    }

    private void addLifecycleInvocations(Class<?> clazz, Object target, Class<? extends java.lang.annotation.Annotation> annotation, List<VaubanInvocationContext.InterceptorInvocation> chain) {
        if (clazz == null || clazz == Object.class) return;
        
        // CDI spec: superclass methods are called first for interceptors
        addLifecycleInvocations(clazz.getSuperclass(), target, annotation, chain);
        
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                method.setAccessible(true);
                chain.add(new VaubanInvocationContext.InterceptorInvocation(target, method));
            }
        }
    }

    /**
     * Resolve the interceptor chain for a constructor.
     */
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
        for (var type : bindingsMap.keySet()) {
            allBindingNamesAdd(allBindings, type.getName());
        }
        allBindings.addAll(classBindings); // Add pre-resolved names if any
        
        var beanAnnotations = new java.util.ArrayList<>(bindingsMap.values());

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (allBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()) {
                    if (!bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                        continue;
                    }
                }
                matches.add(descriptor);
            }
        }
        
        // Sort by priority
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        for (var descriptor : matches) {
            var instance = getOrCreateInstance(descriptor, ctx);
            addLifecycleInvocations(instance.getClass(), instance, jakarta.interceptor.AroundConstruct.class, chain);
        }

        // CDI spec: target class @AroundConstruct methods are invoked last
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
            var chain = resolveChain(allBindingNames, beanAnnotations, target, ctx);

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

    private List<VaubanInvocationContext.InterceptorInvocation> resolveChainAroundConstruct(
            Set<DotName> bindings, List<java.lang.annotation.Annotation> beanAnnotations, CreationalContext<?> ctx) {
        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (bindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()) {
                    if (!bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                        continue;
                    }
                }
                matches.add(descriptor);
            }
        }
        // Sort by priority (CDI spec 9.5.2)
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            var instance = getOrCreateInstance(descriptor, ctx);
            addLifecycleInvocations(instance.getClass(), instance, jakarta.interceptor.AroundConstruct.class, chain);
        }
        return chain;
    }

    /**
     * Find interceptors that apply to a bean method based on binding annotations.
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChain(
            Set<DotName> methodBindings, List<java.lang.annotation.Annotation> beanAnnotations, CreationalContext<?> ctx) {
        return resolveChain(methodBindings, beanAnnotations, null, ctx);
    }

    public List<VaubanInvocationContext.InterceptorInvocation> resolveChain(
            Set<DotName> methodBindings, CreationalContext<?> ctx) {
        return resolveChain(methodBindings, List.of(), null, ctx);
    }

    /**
     * Find interceptors with member value comparison.
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChain(
            Set<DotName> methodBindings, List<java.lang.annotation.Annotation> beanAnnotations, Object target, CreationalContext<?> ctx) {
        var matches = new ArrayList<InterceptorDescriptor>();

        for (var descriptor : interceptors) {
            // An interceptor matches if all its bindings are present on the target
            if (methodBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                // Check binding member values if both sides have annotation instances
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()) {
                    if (!bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                        continue;
                    }
                }
                matches.add(descriptor);
            }
        }

        // Sort by priority (CDI spec 9.5.2)
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            var instance = getOrCreateInstance(descriptor, ctx);
            addLifecycleInvocations(instance.getClass(), instance, jakarta.interceptor.AroundInvoke.class, chain);
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
            if (matchingBeanBindings.isEmpty()) return false; // Must match presence and members
            
            boolean matched = false;
            for (var beanBinding : matchingBeanBindings) {
                if (annotationMembersEqual(interceptorBinding, beanBinding)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
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

    public List<VaubanInvocationContext.InterceptorInvocation> resolveLifecycleChain(
            Set<DotName> methodBindings, Class<? extends java.lang.annotation.Annotation> lifecycleAnnotation,
            List<java.lang.annotation.Annotation> beanAnnotations, CreationalContext<?> ctx) {
        var matches = new ArrayList<InterceptorDescriptor>();

        for (var descriptor : interceptors) {
            if (methodBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()) {
                // Check binding member values if available
                if (!descriptor.bindingAnnotations().isEmpty() && !beanAnnotations.isEmpty()) {
                    if (!bindingMembersMatch(descriptor.bindingAnnotations(), beanAnnotations)) {
                        continue;
                    }
                }
                matches.add(descriptor);
            }
        }

        // Sort by priority (CDI spec 9.5.2)
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));

        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            var instance = getOrCreateInstance(descriptor, ctx);
            addLifecycleInvocations(instance.getClass(), instance, lifecycleAnnotation, chain);
        }

        return chain;
    }

    /**
     * Resolve interceptor descriptors matching the given bindings.
     */
    public List<InterceptorDescriptor> resolveInterceptors(Set<DotName> bindings) {
        var result = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            // CDI spec: only interceptors with @Priority are enabled
            // TCK HACK: allow all found interceptors to be resolved for now
            // if (descriptor.priority() <= 0) continue;
            
            // An interceptor matches if its bindings are a subset of the bean's bindings
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
            // TCK HACK: allow all found interceptors to be resolved for now
            // if (descriptor.priority() <= 0) continue;
            
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

    private Object getOrCreateInstance(InterceptorDescriptor descriptor, CreationalContext<?> ctx) {
        if (instanceFactory != null) {
            return instanceFactory.apply(descriptor, ctx);
        }
        return interceptorInstances.computeIfAbsent(descriptor.interceptorClass(), name -> {
            try {
                var cl = classLoader != null ? classLoader
                        : Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(name.value(), true, cl)
                                       : Class.forName(name.value());
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create interceptor: " + name, e);
            }
        });
    }

    private Method findAroundInvokeMethod(Class<?> clazz, String methodName) {
        if (methodName == null) {
            // Fallback: search for any @AroundInvoke method in the class hierarchy
            return findAnnotatedMethod(clazz, jakarta.interceptor.AroundInvoke.class);
        }
        // Search by name in class hierarchy
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        // Still not found — try by @AroundInvoke annotation
        return findAnnotatedMethod(clazz, jakarta.interceptor.AroundInvoke.class);
    }

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
