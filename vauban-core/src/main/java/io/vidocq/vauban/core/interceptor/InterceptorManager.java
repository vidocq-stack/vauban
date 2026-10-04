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
package io.vidocq.vauban.core.interceptor;

import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.indexer.model.DotName;

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
@SuppressWarnings({"java:S100", "java:S3776"}) // $$ methods are CDI container conventions for generated code
public final class InterceptorManager {

    private static final String INTERCEPTED_SUFFIX = InterceptedShape.SUBCLASS_SUFFIX;

    private final List<InterceptorDescriptor> interceptors;
    private final Map<DotName, Object> interceptorInstances = new LinkedHashMap<>();
    private BiFunction<InterceptorDescriptor, CreationalContext<?>, Object> instanceFactory;
    private io.vidocq.vauban.core.container.VaubanLookup vaubanLookup;
    private io.vidocq.vauban.core.annotation.AnnotationTypes annotationTypes;

    /**
     * Which interceptors apply to a method, worked out once. It depends on declarations alone — the
     * bean's and the method's bindings against each interceptor's — so it does not change between
     * invocations, while the generated subclass asks for it on every call. Only the instances the
     * chain holds are per-{@code CreationalContext}, and those are still built each time.
     */
    private final Map<MethodChain, List<InterceptorDescriptor>> chainCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** The bean class carries its own class-level bindings, so it and the method determine the rest. */
    private record MethodChain(Class<?> beanClass, java.lang.reflect.Method method) {}

    public record InterceptionState(List<VaubanInvocationContext.InterceptorInvocation> chain, Set<DotName> bindings, CreationalContext<?> ctx) {}

    private static final ScopedValue<InterceptionState> INTERCEPTION = ScopedValue.newInstance();

    // Enhanced interceptor binding annotations added via BCE Enhancement (not present in bytecode)
    private final Map<String, List<java.lang.annotation.Annotation>> enhancedBindings = new LinkedHashMap<>();

    public void registerEnhancedBindings(String beanClassName, List<java.lang.annotation.Annotation> annotations) {
        if (annotations != null && !annotations.isEmpty()) {
            enhancedBindings.computeIfAbsent(beanClassName, k -> new ArrayList<>()).addAll(annotations);
            // Boot-time, before any invocation — but a binding added after a chain was worked out
            // would make it stale, and nothing else would say so.
            chainCache.clear();
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

    public static boolean $$isIntercepting() {
        return INTERCEPTION.isBound();
    }

    public static List<VaubanInvocationContext.InterceptorInvocation> $$getAroundConstructChain() {
        return INTERCEPTION.get().chain();
    }

    public static Set<DotName> $$getAroundConstructBindings() {
        return INTERCEPTION.get().bindings();
    }

    public static CreationalContext<?> $$getAroundConstructContext() {
        return INTERCEPTION.isBound() ? INTERCEPTION.get().ctx() : null;
    }

    public static void $$runIntercepted(List<VaubanInvocationContext.InterceptorInvocation> chain, Set<DotName> bindings, CreationalContext<?> ctx, Runnable action) {
        ScopedValue.where(INTERCEPTION, new InterceptionState(chain, bindings, ctx)).run(action);
    }

    @SuppressWarnings("unchecked")
    public static <R> R $$callIntercepted(List<VaubanInvocationContext.InterceptorInvocation> chain, Set<DotName> bindings, CreationalContext<?> ctx, ScopedValue.CallableOp<R, Exception> action) throws Exception {
        try {
            return ScopedValue.where(INTERCEPTION, new InterceptionState(chain, bindings, ctx)).call(action);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @SuppressWarnings("java:S3010") // Static singleton set in constructor — single container instance by design
    public InterceptorManager(List<InterceptorDescriptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
        currentInstance = this;
    }

    /**
     * What this deployment's annotation types declare. Binding members are compared as normalized
     * {@link io.vidocq.vauban.core.annotation.AnnotationKey}s through it, so a member an extension
     * made {@code @Nonbinding} counts for as little as one the declaration marks — and no member is
     * read with {@code Method.invoke} (vauban#70).
     */
    public void setAnnotationTypes(io.vidocq.vauban.core.annotation.AnnotationTypes types) {
        this.annotationTypes = types;
    }

    public void setVaubanLookup(io.vidocq.vauban.core.container.VaubanLookup lookup) {
        this.vaubanLookup = lookup;
    }

    public io.vidocq.vauban.core.container.VaubanLookup getVaubanLookup() {
        return vaubanLookup;
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
                boolean isBinding = type.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                        || io.vidocq.vauban.core.container.VaubanBeanManager.isCustomInterceptorBinding(type);
                if (isBinding) {
                    result.put(type, ann);
                }
                // Transitive bindings and Stereotypes
                if (isBinding || type.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
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
            makeAccessibleSafe(method);
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

    public List<InterceptorDescriptor> resolveInterceptorDescriptorsForMethod(
            Set<DotName> classBindings, java.lang.reflect.Method method) {
        if (method == null) return List.of();
        Class<?> beanClass = method.getDeclaringClass();
        if (beanClass.getName().contains(INTERCEPTED_SUFFIX)) {
            beanClass = beanClass.getSuperclass();
        }
        return resolveInterceptorDescriptorsForMethod(classBindings, beanClass, method);
    }

    /**
     * The interceptors bound to {@code method} — a business method of {@code beanClass}, possibly
     * inherited — without creating instances: the bean's class-level bindings plus those of the
     * declaration the call runs ({@code BusinessMethods.declarationOf}), as the chain will apply them.
     */
    @SuppressWarnings("java:S135")
    public List<InterceptorDescriptor> resolveInterceptorDescriptorsForMethod(
            Set<DotName> classBindings, Class<?> beanClass, java.lang.reflect.Method method) {
        if (method == null) return List.of();

        var bindingsMap = collectAllBindings(beanClass);
        collectDeclarationBindings(beanClass, method, bindingsMap);

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
                if (!bindingMembersMatchWherePresent(descriptor.bindingAnnotations(), beanAnnotations)) {
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
        if (beanClass.getName().contains(INTERCEPTED_SUFFIX)) {
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
                if (!bindingMembersMatchWherePresent(descriptor.bindingAnnotations(), currentBeanAnnotations)) {
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
        if (beanClass.getName().contains(INTERCEPTED_SUFFIX)) {
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
                if (!bindingMembersMatchWherePresent(descriptor.bindingAnnotations(), beanAnnotations)) {
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
            return clazz.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    || io.vidocq.vauban.core.container.VaubanBeanManager.isCustomInterceptorBinding(clazz);
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
        if (method == null) {
            return resolveChain(classBindings, List.of(), ctx);
        }
        // Use the target class if available, as class-level bindings on the bean
        // apply to all its methods, including those inherited from superclasses.
        var beanClass = target != null ? target.getClass() : method.getDeclaringClass();
        if (beanClass.getName().contains(INTERCEPTED_SUFFIX)) {
            beanClass = beanClass.getSuperclass();
        }

        // Which interceptors apply is settled by the declarations; the generated subclass asks on
        // every call, so it is worked out once per (bean class, method).
        final var declaring = beanClass;
        var matches = chainCache.computeIfAbsent(new MethodChain(beanClass, method),
                key -> matchingForMethod(declaring, key.method(), classBindings));

        var chain = buildChain(matches, ctx, jakarta.interceptor.AroundInvoke.class);

        // CDI spec: target class @AroundInvoke methods are invoked last, after external interceptors
        if (target != null) {
            addLifecycleInvocations(beanClass, target, jakarta.interceptor.AroundInvoke.class, chain);
        }
        return chain;
    }

    /**
     * The bindings of {@code beanClass} and of the declaration {@code method} runs, and the
     * interceptors they select.
     */
    private List<InterceptorDescriptor> matchingForMethod(Class<?> beanClass,
            java.lang.reflect.Method method, Set<DotName> classBindings) {
        var bindingsMap = collectAllBindings(beanClass);
        collectDeclarationBindings(beanClass, method, bindingsMap);

        var allBindingNames = new java.util.LinkedHashSet<DotName>();
        for (var type : bindingsMap.keySet()) {
            allBindingNames.add(DotName.of(type.getName()));
        }
        allBindingNames.addAll(classBindings);
        return matching(allBindingNames, new java.util.ArrayList<>(bindingsMap.values()));
    }

    /**
     * Why {@code method} makes {@code beanClass} intercepted, for a deployment error: the method-level
     * interceptor bindings of the declaration it runs, and where that declaration sits — a bean bound
     * only through an inherited method carries no binding in its own source.
     *
     * <p>Internal to Vauban, not API: public only for the container's bean wrapper in another
     * package of this module; its text may change without notice.</p>
     */
    public String describeMethodBindings(Class<?> beanClass, java.lang.reflect.Method method) {
        var declaration = BusinessMethods.declarationOf(beanClass, method);
        if (declaration == null) return "method " + method.getName();
        var bindings = new LinkedHashMap<Class<? extends java.lang.annotation.Annotation>, java.lang.annotation.Annotation>();
        collectBindingsRecursively(declaration.getAnnotations(), bindings, new java.util.HashSet<>());
        var names = bindings.keySet().stream().map(type -> "@" + type.getName())
                .collect(java.util.stream.Collectors.joining(", "));
        var owner = declaration.getDeclaringClass();
        var params = java.util.Arrays.stream(declaration.getParameterTypes()).map(Class::getName)
                .collect(java.util.stream.Collectors.joining(", "));
        var where = owner == beanClass ? "a method it declares"
                : owner.isInterface() ? "an interface default method it inherits" : "a method it inherits";
        return "the method-level interceptor binding " + names + " of " + owner.getName() + "."
                + declaration.getName() + "(" + params + "), " + where;
    }

    /**
     * Adds the method-level bindings of the declaration a call of {@code method} (or of the business
     * method its {@code $$super$} bridge stands for) runs on a {@code beanClass} instance — the
     * declaration {@link VaubanInvocationContext#getMethod()} reports and reads its own bindings from.
     * Only a business method of the bean has bindings that count: a private or static method, or a
     * package-private one of another package, is never intercepted, and the processor does not
     * count it either.
     */
    private void collectDeclarationBindings(Class<?> beanClass, java.lang.reflect.Method method,
            Map<Class<? extends java.lang.annotation.Annotation>, java.lang.annotation.Annotation> bindingsMap) {
        var declaration = BusinessMethods.declarationOf(beanClass, method);
        if (declaration != null && BusinessMethods.isBusinessMethodOf(beanClass, declaration)) {
            collectBindingsRecursively(declaration.getAnnotations(), bindingsMap, new java.util.HashSet<>());
        }
    }

    /**
     * Resolve the interceptor chain for a constructor, including target class @AroundConstruct methods.
     * CDI 4.1 Section 9.5.2.
     */
    public List<VaubanInvocationContext.InterceptorInvocation> resolveChainForConstructor(
            Set<DotName> classBindings, Set<DotName> constructorBindings, java.lang.reflect.Constructor<?> constructor, CreationalContext<?> ctx) {
        Class<?> beanClass = constructor.getDeclaringClass();
        if (beanClass.getName().contains(INTERCEPTED_SUFFIX)) {
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
                if (!bindingMembersMatchWherePresent(descriptor.bindingAnnotations(), beanAnnotations)) {
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
        return buildChain(matching(methodBindings, beanAnnotations), ctx,
                jakarta.interceptor.AroundInvoke.class);
    }

    /**
     * The interceptors whose bindings the target carries, in the order CDI 4.1 §9.5.2 gives them.
     * It reads declarations only, so the answer holds for the life of the deployment.
     */
    private List<InterceptorDescriptor> matching(Set<DotName> methodBindings,
            List<java.lang.annotation.Annotation> beanAnnotations) {
        var matches = new ArrayList<InterceptorDescriptor>();
        for (var descriptor : interceptors) {
            if (!descriptor.enabled()) continue;
            // An interceptor matches if all its bindings are present on the target
            if (methodBindings.containsAll(descriptor.bindings()) && !descriptor.bindings().isEmpty()
                    && bindingMembersMatchWherePresent(descriptor.bindingAnnotations(), beanAnnotations)) {
                matches.add(descriptor);
            }
        }
        matches.sort(java.util.Comparator.comparingInt(InterceptorDescriptor::priority)
                .thenComparing(d -> d.interceptorClass().toString()));
        return matches;
    }

    /** The invocations for {@code matches}: one instance per interceptor, from {@code ctx}. */
    private List<VaubanInvocationContext.InterceptorInvocation> buildChain(
            List<InterceptorDescriptor> matches, CreationalContext<?> ctx,
            Class<? extends java.lang.annotation.Annotation> callback) {
        var effectiveCtx = ctx != null ? ctx : $$getAroundConstructContext();
        var chain = new ArrayList<VaubanInvocationContext.InterceptorInvocation>();
        for (var descriptor : matches) {
            if (effectiveCtx != null) {
                var instance = getOrCreateInstance(descriptor, effectiveCtx);
                addLifecycleInvocations(instance.getClass(), instance, callback, chain);
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

    private boolean bindingMembersMatchWherePresent(
            List<java.lang.annotation.Annotation> interceptorBindings,
            List<java.lang.annotation.Annotation> beanBindings) {
        if (interceptorBindings.isEmpty() || beanBindings.isEmpty()) {
            return true;
        }
        var beanTypes = beanBindings.stream()
                .map(java.lang.annotation.Annotation::annotationType)
                .collect(java.util.stream.Collectors.toSet());
        var comparable = interceptorBindings.stream()
                .filter(ann -> beanTypes.contains(ann.annotationType()))
                .toList();
        if (comparable.isEmpty()) {
            return true;
        }
        return bindingMembersMatch(comparable, beanBindings);
    }

    /**
     * Whether two bindings of the same type agree on the members that bind. They are compared as
     * normalized keys: member defaults applied, {@code @Nonbinding} members left out — the ones the
     * declaration marks and the ones an extension made non-binding alike, which reading the members
     * back could not tell apart (BUG-20260914-08).
     */
    private boolean annotationMembersEqual(java.lang.annotation.Annotation a, java.lang.annotation.Annotation b) {
        var types = annotationTypes;
        if (types == null) {
            // No deployment metadata (a standalone manager): compare what the instances return.
            return membersEqualReflectively(a, b);
        }
        return types.key(a).equals(types.key(b));
    }

    /** The pre-#70 comparison, kept for a manager built without a deployment's metadata. */
    private static boolean membersEqualReflectively(java.lang.annotation.Annotation a,
            java.lang.annotation.Annotation b) {
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
                        && !bindingMembersMatchWherePresent(descriptor.bindingAnnotations(), beanAnnotations)) {
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
        if (ctx instanceof io.vidocq.vauban.core.context.CreationalContextImpl<?> vCtx) {
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
                if (bindingMembersMatchWherePresent(descriptor.bindingAnnotations(), beanAnnotations)) {
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
        var onBeanClass = true;
        while (current != null && current != Object.class) {
            for (var ann : current.getDeclaredAnnotations()) {
                var annName = DotName.of(ann.annotationType().getName());
                if (isBinding.test(annName)) {
                    if (onBeanClass
                            || ann.annotationType().isAnnotationPresent(java.lang.annotation.Inherited.class)
                            || isInterceptorBindingViaStereotype(ann)) {
                        bindings.add(annName);
                    }
                }
            }
            onBeanClass = false;
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

    @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
    public Object getOrCreateInstance(InterceptorDescriptor descriptor, CreationalContext<?> ctx) {
        if (instanceFactory != null) {
            return instanceFactory.apply(descriptor, ctx);
        }
        
        String className = descriptor.interceptorClass().value();
        if (ctx instanceof io.vidocq.vauban.core.context.CreationalContextImpl<?> vCtx) {
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
            
            if (ctx instanceof io.vidocq.vauban.core.context.CreationalContextImpl<?> vCtx) {
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
                    makeAccessibleSafe(method);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void makeAccessibleSafe(java.lang.reflect.AccessibleObject member) {
        if (vaubanLookup != null) {
            vaubanLookup.makeAccessible(member);
        } else {
            member.trySetAccessible();
        }
    }

    public boolean hasInterceptors() {
        return !interceptors.isEmpty();
    }

    public List<InterceptorDescriptor> getInterceptors() {
        return interceptors;
    }
}
