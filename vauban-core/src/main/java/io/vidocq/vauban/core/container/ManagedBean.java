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
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * CDI Bean implementation backed by a BeanDescriptor and a BeanFactory.
 */
public final class ManagedBean<T> implements Bean<T> {

    private static final System.Logger LOG = System.getLogger(ManagedBean.class.getName());

    private final BeanDescriptor descriptor;
    private final BeanFactory<T> factory;
    private final Class<T> beanClass;
    private final ClassLoader classLoader;
    private final VaubanLookup vaubanLookup;
    private BiConsumer<Object, CreationalContext<?>> injector;
    private BiConsumer<Object, CreationalContext<?>> destroyer;
    private io.vidocq.vauban.core.interceptor.InterceptorManager interceptorManager;
    // Lazily computed immutable set, safe under volatile: computed once, read-only after
    @SuppressWarnings("java:S3077")
    private volatile Set<Type> cachedTypes;
    @SuppressWarnings("java:S3077")
    private volatile Set<Annotation> qualifierInstances;
    @SuppressWarnings("java:S3077")
    private volatile Set<io.vidocq.vauban.core.annotation.AnnotationKey> qualifierKeys;
    private final io.vidocq.vauban.core.annotation.AnnotationTypes annotationTypes;

    public ManagedBean(BeanDescriptor descriptor, BeanFactory<T> factory, ClassLoader classLoader, VaubanLookup vaubanLookup) {
        this(descriptor, factory, classLoader, vaubanLookup, null);
    }

    /**
     * @param annotationTypes the container's annotation metadata, so {@link #getQualifiers()} hands out
     *                        the literal the qualifier's own module generated; {@code null} outside a
     *                        container, where the fallback instance is built instead
     */
    public ManagedBean(BeanDescriptor descriptor, BeanFactory<T> factory, ClassLoader classLoader,
            VaubanLookup vaubanLookup, io.vidocq.vauban.core.annotation.AnnotationTypes annotationTypes) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.factory = Objects.requireNonNull(factory);
        this.classLoader = Objects.requireNonNull(classLoader);
        this.vaubanLookup = vaubanLookup;
        this.annotationTypes = annotationTypes;
        try {
            this.beanClass = (Class<T>) Class.forName(descriptor.beanClass().value(), true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Bean class not found: " + descriptor.beanClass(), e);
        }
    }

    public BiConsumer<Object, CreationalContext<?>> getInjector() {
        return injector;
    }

    public BiConsumer<Object, CreationalContext<?>> getDestroyer() {
        return destroyer;
    }

    public void setInjector(BiConsumer<Object, CreationalContext<?>> injector) {
        this.injector = injector;
    }

    public void setDestroyer(BiConsumer<Object, CreationalContext<?>> destroyer) {
        this.destroyer = destroyer;
    }

    public void setInterceptorManager(io.vidocq.vauban.core.interceptor.InterceptorManager interceptorManager) {
        this.interceptorManager = interceptorManager;
    }

    public BeanFactory<T> factory() {
        return factory;
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        T instance = factory.create(creationalContext);
        
        // CDI spec: producer returning null for non-Dependent scope -> IllegalProductException
        if (instance == null && descriptor.kind() != BeanDescriptor.BeanKind.MANAGED
                && !descriptor.scope().equals(io.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT)) {
            throw new jakarta.enterprise.inject.IllegalProductException(
                    "Producer " + descriptor.id() + " returned null for non-@Dependent bean");
        }
        if (instance != null && injector != null) {
            injector.accept(instance, creationalContext);
        }
        return instance;
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        if (instance == null) return;

        if (destroyer != null) {
            try {
                destroyer.accept(instance, creationalContext);
            } catch (Exception e) {
                // CDI spec: exceptions in disposer methods are suppressed
            }
        }
        if (descriptor.kind() == BeanDescriptor.BeanKind.MANAGED) {
            callPreDestroy(instance, creationalContext);
        }
        if (creationalContext instanceof io.vidocq.vauban.core.context.CreationalContextImpl<?> cc) {
            // Remove ourselves from the dependent list before releasing to avoid recursive destroy
            cc.getDependentInstances().removeIf(dep -> dep.instance() == instance);
            cc.release();
        }
    }

    private void callPreDestroy(Object instance, CreationalContext<?> ctx) {
        if (instance == null) return;
        // Find all @PreDestroy methods in the bean hierarchy (respecting override rules)
        var preDestroyMethods = collectLifecycleMethodsInHierarchy(instance.getClass(), jakarta.annotation.PreDestroy.class);

        // Check for lifecycle interceptors
        java.util.Set<io.vidocq.vauban.indexer.model.DotName> bindings = (descriptor != null) ? descriptor.interceptorBindings() : findInterceptorBindings(instance);
        // Fallback: check via reflection if descriptor bindings are empty
        if ((bindings == null || bindings.isEmpty()) && interceptorManager != null && interceptorManager.hasInterceptors()) {
            bindings = findInterceptorBindings(instance);
        }
        if (interceptorManager != null && interceptorManager.hasInterceptors() && bindings != null && !bindings.isEmpty()) {
            interceptorManager.setClassLoader(instance.getClass().getClassLoader());
            var bindingAnnotations = (descriptor != null && !descriptor.interceptorBindingAnnotations().isEmpty())
                    ? new java.util.ArrayList<java.lang.annotation.Annotation>(descriptor.interceptorBindingAnnotations())
                    : new java.util.ArrayList<java.lang.annotation.Annotation>(collectBindingAnnotations(instance));
            var chain = interceptorManager.resolveLifecycleChain(
                    bindings, jakarta.annotation.PreDestroy.class, bindingAnnotations, ctx);
                if (!chain.isEmpty()) {
                    var bindingAnnotationsSet = new java.util.LinkedHashSet<java.lang.annotation.Annotation>(bindingAnnotations);
                    final var pdMethods = preDestroyMethods;
                    var invocationCtx = new io.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                            instance, null, new Object[0], chain,
                            (target, params) -> {
                                for (var m : pdMethods) {
                                    vaubanLookup.invokeMethod(target, m);
                                }
                                return null;
                            });
                    invocationCtx.setInterceptorBindings(bindingAnnotationsSet);
                    try {
                        invocationCtx.proceed();
                    } catch (Exception e) {
                        // CDI spec: suppress PreDestroy exceptions
                    }
                    return;
                }
        }

        // No interceptors — call directly
        for (var pdMethod : preDestroyMethods) {
            try {
                vaubanLookup.invokeMethod(instance, pdMethod);
            } catch (Exception e) {
                // CDI spec says exceptions in @PreDestroy are caught, not propagated
            }
        }
    }

    private static java.util.List<java.lang.reflect.Method> collectLifecycleMethodsInHierarchy(
            Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotation) {
        if (clazz.getName().contains("$$Intercepted") || clazz.getName().contains("$$Proxy")
                || clazz.getName().contains("_ClientProxy")) {
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
        collectLifecycleMethodsRecursive(clazz.getSuperclass(), annotation, result);
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                result.removeIf(m -> m.getName().equals(method.getName())
                        && java.util.Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
                result.add(method);
            } else {
                result.removeIf(m -> m.getName().equals(method.getName())
                        && java.util.Arrays.equals(m.getParameterTypes(), method.getParameterTypes()));
            }
        }
    }

    private Set<java.lang.annotation.Annotation> collectBindingAnnotations(Object instance) {
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted") || clazz.getName().contains("$$Proxy")) {
            clazz = clazz.getSuperclass();
        }
        var annotations = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        for (var ann : clazz.getAnnotations()) {
            var t = ann.annotationType();
            if (t.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    || VaubanBeanManager.isCustomInterceptorBinding(t)) {
                annotations.add(ann);
            }
        }
        // Collect transitive binding annotations from meta-annotations
        var toAdd = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        for (var ann : annotations) {
            collectTransitiveBindingAnnotations(ann.annotationType(), toAdd, annotations);
        }
        annotations.addAll(toAdd);
        return annotations;
    }

    private static void collectTransitiveBindingAnnotations(
            Class<? extends java.lang.annotation.Annotation> annType,
            Set<java.lang.annotation.Annotation> toAdd,
            Set<java.lang.annotation.Annotation> existing) {
        for (var meta : annType.getAnnotations()) {
            var mt = meta.annotationType();
            boolean isBinding = mt.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    || VaubanBeanManager.isCustomInterceptorBinding(mt);
            if (isBinding && !existing.contains(meta) && !toAdd.contains(meta)) {
                toAdd.add(meta);
                collectTransitiveBindingAnnotations(mt, toAdd, existing);
            }
        }
    }

    private Set<io.vidocq.vauban.indexer.model.DotName> findInterceptorBindings(Object instance) {
        var bindings = new java.util.LinkedHashSet<io.vidocq.vauban.indexer.model.DotName>();
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted")) clazz = clazz.getSuperclass();
        for (var ann : clazz.getAnnotations()) {
            var t = ann.annotationType();
            if (t.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    || VaubanBeanManager.isCustomInterceptorBinding(t)) {
                bindings.add(io.vidocq.vauban.indexer.model.DotName.of(t.getName()));
                // Collect transitive bindings from meta-annotations
                collectTransitiveInterceptorBindings(t, bindings);
            }
        }
        return bindings;
    }

    private static void collectTransitiveInterceptorBindings(Class<? extends java.lang.annotation.Annotation> annType,
            Set<io.vidocq.vauban.indexer.model.DotName> bindings) {
        for (var meta : annType.getAnnotations()) {
            var mt = meta.annotationType();
            if (mt.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    || VaubanBeanManager.isCustomInterceptorBinding(mt)) {
                var name = io.vidocq.vauban.indexer.model.DotName.of(mt.getName());
                if (bindings.add(name)) {
                    collectTransitiveInterceptorBindings(mt, bindings);
                }
            }
        }
    }

    @Override
    public Class<?> getBeanClass() {
        return beanClass;
    }

    @Override
    public Set<Type> getTypes() {
        var cached = cachedTypes;
        if (cached != null) return cached;

        Set<Type> types;
        // For producer beans, derive types from the produced type, not the declaring class
        if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                || descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
            types = getProducerTypes();
        } else if (descriptor.kind() == BeanDescriptor.BeanKind.SYNTHETIC) {
            // Synthetic beans use the types from the descriptor directly
            types = getProducerTypes();
        } else {
            types = new LinkedHashSet<>();
            collectTypes(beanClass, types);
            types.add(Object.class);
            // Check if @Typed restricts the bean types
            if (hasTypedRestriction()) {
                types = filterByTyped(types);
            }
            // CDI 4.1 Section 2.2.1: filter illegal bean types, keeping the bean's own type variables
            var ownTypeVars = new LinkedHashSet<java.lang.reflect.TypeVariable<?>>();
            java.util.Collections.addAll(ownTypeVars, beanClass.getTypeParameters());
            types = filterIllegalBeanTypes(types, ownTypeVars);
        }
        cachedTypes = types;
        return types;
    }

    /**
     * Check if the bean has @Typed restriction.
     */
    private boolean hasTypedRestriction() {
        return beanClass.isAnnotationPresent(jakarta.enterprise.inject.Typed.class);
    }

    /**
     * Filter unrestricted type set by @Typed annotation.
     * CDI 4.1 Section 2.2.2: @Typed restricts bean types to only the specified types + Object.
     * For generic types, we keep the parameterized version from the hierarchy.
     */
    private Set<Type> filterByTyped(Set<Type> unrestrictedTypes) {
        var typed = beanClass.getAnnotation(jakarta.enterprise.inject.Typed.class);
        var allowedRawTypes = new java.util.HashSet<Class<?>>(java.util.Arrays.asList(typed.value()));
        var result = new LinkedHashSet<Type>();
        for (var t : unrestrictedTypes) {
            if (t == Object.class) {
                result.add(t);
                continue;
            }
            Class<?> raw = rawTypeOf(t);
            if (raw != null && allowedRawTypes.contains(raw)) {
                result.add(t);
            }
        }
        return result;
    }

    private static Class<?> rawTypeOf(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case java.lang.reflect.ParameterizedType pt -> (Class<?>) pt.getRawType();
            case null, default -> null;
        };
    }

    /**
     * CDI 4.1 Section 2.2.1: Filter illegal bean types.
     * Parameterized types with unresolved TypeVariables or Wildcards are illegal,
     * unless the TypeVariables are the bean's own type parameters.
     */
    private static Set<Type> filterIllegalBeanTypes(Set<Type> types, Set<java.lang.reflect.TypeVariable<?>> allowedTypeVars) {
        return filterIllegalBeanTypes(types, allowedTypeVars, false);
    }

    private static Set<Type> filterIllegalBeanTypes(Set<Type> types, Set<java.lang.reflect.TypeVariable<?>> allowedTypeVars, boolean addRawForRemoved) {
        var result = new LinkedHashSet<Type>();
        for (var t : types) {
            switch (t) {
                case Class<?> c -> result.add(c);
                case java.lang.reflect.ParameterizedType pt
                        when isLegalParameterizedType(pt, allowedTypeVars) -> result.add(t);
                case java.lang.reflect.ParameterizedType pt
                        when addRawForRemoved && pt.getRawType() instanceof Class<?>
                                && containsTypeVariable(pt) ->
                        // CDI spec 5.2.4: keep parameterized types with type variables for producers
                        // so that assignability can match type variable bounds against required types
                        result.add(t);
                case java.lang.reflect.GenericArrayType gat
                        when !containsUnresolvedTypeVariable(gat) -> result.add(t);
                case null, default -> { /* Skip TypeVariable, WildcardType */ }
            }
        }
        return result;
    }

    private static boolean containsTypeVariable(java.lang.reflect.ParameterizedType pt) {
        for (var arg : pt.getActualTypeArguments()) {
            if (arg instanceof java.lang.reflect.TypeVariable<?>) return true;
            if (arg instanceof java.lang.reflect.ParameterizedType nested
                    && containsTypeVariable(nested)) return true;
        }
        return false;
    }

    private static boolean isLegalParameterizedType(java.lang.reflect.ParameterizedType pt,
            Set<java.lang.reflect.TypeVariable<?>> allowedTypeVars) {
        for (var arg : pt.getActualTypeArguments()) {
            if (arg instanceof java.lang.reflect.TypeVariable<?> tv
                    && !allowedTypeVars.contains(tv)) {
                return false;
            } else if (arg instanceof java.lang.reflect.WildcardType) {
                return false;
            } else if (arg instanceof java.lang.reflect.ParameterizedType nested
                    && !isLegalParameterizedType(nested, allowedTypeVars)) {
                return false;
            }
        }
        return true;
    }

    private Set<Type> getProducerTypes() {
        Type producerGenericType = resolveProducerGenericType();
        if (producerGenericType != null) {
            // CDI spec: array types have only {ArrayType, Object} as bean types
            if (producerGenericType instanceof Class<?> c && c.isArray()) {
                var arrayTypes = new LinkedHashSet<Type>();
                arrayTypes.add(c);
                arrayTypes.add(Object.class);
                return arrayTypes;
            }
            if (producerGenericType instanceof java.lang.reflect.GenericArrayType) {
                var arrayTypes = new LinkedHashSet<Type>();
                arrayTypes.add(producerGenericType);
                arrayTypes.add(Object.class);
                return arrayTypes;
            }
            // Build full type hierarchy from the produced type using reflection
            var allTypes = new LinkedHashSet<Type>();
            allTypes.addAll(io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(producerGenericType));
            allTypes.add(Object.class);

            if (hasProducerTypedRestriction()) {
                var typedClasses = getProducerTypedClasses();
                if (typedClasses != null) {
                    var filtered = new LinkedHashSet<Type>();
                    for (var t : allTypes) {
                        if (t == Object.class) { filtered.add(t); continue; }
                        Class<?> raw = rawTypeOf(t);
                        if (raw != null && typedClasses.contains(raw)) {
                            filtered.add(t);
                        }
                    }
                    return filtered;
                }
            }
            
            // Filter illegal types (producers have no allowed type variables)
            // CDI spec: for producers, replace removed illegal parameterized types with their raw type
            return filterIllegalBeanTypes(allTypes, Set.of(), true);
        }

        // Fallback: resolve descriptor types to Java Types
        var types = new LinkedHashSet<Type>();
        for (var typeInfo : descriptor.types()) {
            switch (typeInfo) {
                case io.vidocq.vauban.indexer.model.TypeInfo.ClassType ct -> {
                    try {
                        types.add(Class.forName(ct.name().value(), true, classLoader));
                    } catch (ClassNotFoundException e) { /* skip */ }
                }
                case io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt -> {
                    try {
                        var rawClass = Class.forName(pt.rawType().value(), true, classLoader);
                        // Reconstruct ParameterizedType recursively so nested parameterized type
                        // arguments (e.g. ClaimValue<Set<String>>) are preserved.
                        java.lang.reflect.Type[] args = resolveTypeArguments(pt.typeArguments(), classLoader);
                        types.add(new java.lang.reflect.ParameterizedType() {
                            @Override public java.lang.reflect.Type[] getActualTypeArguments() { return args; }
                            @Override public java.lang.reflect.Type getRawType() { return rawClass; }
                            @Override public java.lang.reflect.Type getOwnerType() { return null; }
                        });
                    } catch (ClassNotFoundException e) { /* skip */ }
                }
                case io.vidocq.vauban.indexer.model.TypeInfo.ArrayType at -> {
                    var arrayClass = resolveArrayClass(at, classLoader);
                    if (arrayClass != null) types.add(arrayClass);
                }
                case io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType ptt -> {
                    types.add(primitiveClass(ptt.kind()));
                }
                default -> { /* intentionally empty */ }
            }
        }
        types.add(Object.class);
        return filterIllegalBeanTypes(types, Set.of(), true);
    }

    private Type resolveProducerGenericType() {
        try {
            String idValue = descriptor.id().value();
            if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD) {
                int hashIdx = idValue.indexOf('#');
                if (hashIdx < 0) return null;
                String declaringClassName = idValue.substring(0, hashIdx);
                String methodName = idValue.substring(hashIdx + 1);
                Class<?> declaringClass = Class.forName(declaringClassName, true, classLoader);
                for (var m : declaringClass.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)
                            && m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        return resolveTypeVariables(m.getGenericReturnType(), declaringClass);
                    }
                }
                // Also check inherited methods
                for (var m : declaringClass.getMethods()) {
                    if (m.getName().equals(methodName)
                            && m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        return resolveTypeVariables(m.getGenericReturnType(), declaringClass);
                    }
                }
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                int dotIdx = idValue.lastIndexOf('.');
                if (dotIdx < 0) return null;
                String declaringClassName = idValue.substring(0, dotIdx);
                String fieldName = idValue.substring(dotIdx + 1);
                Class<?> declaringClass = Class.forName(declaringClassName, true, classLoader);
                var field = findField(declaringClass, fieldName);
                if (field != null) {
                    return resolveTypeVariables(field.getGenericType(), declaringClass);
                }
            }
        } catch (Exception e) { /* skip - fallback to descriptor */ }
        return null;
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Resolve TypeVariables in a type using the class hierarchy of the declaring class.
     * E.g., if declaringClass is FooProducer extends GenericProducer&lt;Spider&gt;,
     * and type is List&lt;T&gt; where T is GenericProducer's type param,
     * this resolves it to List&lt;Spider&gt;.
     */
    private static Type resolveTypeVariables(Type type, Class<?> declaringClass) {
        if (!containsUnresolvedTypeVariable(type)) return type;
        // Build a mapping from TypeVariable -> actual type by walking the class hierarchy
        var mapping = buildFullTypeMapping(declaringClass);
        if (mapping.isEmpty()) return type;
        return substituteTypeVariables(type, mapping);
    }

    private static Map<java.lang.reflect.TypeVariable<?>, Type> buildFullTypeMapping(Class<?> clazz) {
        var mapping = new java.util.HashMap<java.lang.reflect.TypeVariable<?>, Type>();
        var current = clazz;
        while (current != null && current != Object.class) {
            var genericSuper = current.getGenericSuperclass();
            if (genericSuper instanceof java.lang.reflect.ParameterizedType pt) {
                var rawSuper = (Class<?>) pt.getRawType();
                var typeParams = rawSuper.getTypeParameters();
                var typeArgs = pt.getActualTypeArguments();
                for (int i = 0; i < Math.min(typeParams.length, typeArgs.length); i++) {
                    // Resolve any already-mapped type variables in the arguments
                    var resolved = substituteTypeVariables(typeArgs[i], mapping);
                    mapping.put(typeParams[i], resolved);
                }
            }
            current = current.getSuperclass();
        }
        return mapping;
    }

    private static Type substituteTypeVariables(Type type, Map<java.lang.reflect.TypeVariable<?>, Type> mapping) {
        return switch (type) {
            case java.lang.reflect.TypeVariable<?> tv -> {
                var resolved = mapping.get(tv);
                yield resolved != null ? resolved : type;
            }
            case java.lang.reflect.ParameterizedType pt -> {
                var args = pt.getActualTypeArguments();
                var newArgs = new Type[args.length];
                boolean changed = false;
                for (int i = 0; i < args.length; i++) {
                    newArgs[i] = substituteTypeVariables(args[i], mapping);
                    if (newArgs[i] != args[i]) changed = true;
                }
                yield changed
                        ? new ResolvedParameterizedType((Class<?>) pt.getRawType(), newArgs, pt.getOwnerType())
                        : type;
            }
            case java.lang.reflect.GenericArrayType gat -> {
                var newComponent = substituteTypeVariables(gat.getGenericComponentType(), mapping);
                yield newComponent == gat.getGenericComponentType()
                        ? type
                        : new ResolvedGenericArrayType(newComponent);
            }
            case null, default -> type;
        };
    }

    private boolean hasProducerTypedRestriction() {
        try {
            String idValue = descriptor.id().value();
            if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD) {
                int hashIdx = idValue.indexOf('#');
                if (hashIdx < 0) return false;
                String declaringClassName = idValue.substring(0, hashIdx);
                String methodName = idValue.substring(hashIdx + 1);
                Class<?> declaringClass = Class.forName(declaringClassName, true, classLoader);
                for (var m : declaringClass.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)
                            && m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        return m.isAnnotationPresent(jakarta.enterprise.inject.Typed.class);
                    }
                }
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                int dotIdx = idValue.lastIndexOf('.');
                if (dotIdx < 0) return false;
                String declaringClassName = idValue.substring(0, dotIdx);
                String fieldName = idValue.substring(dotIdx + 1);
                Class<?> declaringClass = Class.forName(declaringClassName, true, classLoader);
                return declaringClass.getDeclaredField(fieldName)
                        .isAnnotationPresent(jakarta.enterprise.inject.Typed.class);
            }
        } catch (Exception e) { /* skip */ }
        return false;
    }

    private Set<Class<?>> getProducerTypedClasses() {
        try {
            jakarta.enterprise.inject.Typed typed = null;
            String idValue = descriptor.id().value();
            if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD) {
                int hashIdx = idValue.indexOf('#');
                if (hashIdx < 0) return null;
                String declaringClassName = idValue.substring(0, hashIdx);
                String methodName = idValue.substring(hashIdx + 1);
                Class<?> declaringClass = Class.forName(declaringClassName, true, classLoader);
                for (var m : declaringClass.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)
                            && m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                        typed = m.getAnnotation(jakarta.enterprise.inject.Typed.class);
                        break;
                    }
                }
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                int dotIdx = idValue.lastIndexOf('.');
                if (dotIdx < 0) return null;
                String declaringClassName = idValue.substring(0, dotIdx);
                String fieldName = idValue.substring(dotIdx + 1);
                Class<?> declaringClass = Class.forName(declaringClassName, true, classLoader);
                typed = declaringClass.getDeclaredField(fieldName)
                        .getAnnotation(jakarta.enterprise.inject.Typed.class);
            }
            if (typed != null) {
                return Set.of(typed.value());
            }
        } catch (Exception e) { /* skip */ }
        return null;
    }

    private static Class<?> resolveArrayClass(io.vidocq.vauban.indexer.model.TypeInfo.ArrayType at, ClassLoader cl) {
        try {
            String desc = switch (at.componentType()) {
                // The discovery index models primitive injection-point components as
                // ClassType("boolean") — map those names to descriptor chars too,
                // "[Lboolean;" is not loadable. Cf. VAU-BCE-004.
                case io.vidocq.vauban.indexer.model.TypeInfo.ClassType ct ->
                        "[".repeat(at.dimensions()) + switch (ct.name().value()) {
                            case "boolean" -> "Z";
                            case "byte" -> "B";
                            case "char" -> "C";
                            case "short" -> "S";
                            case "int" -> "I";
                            case "long" -> "J";
                            case "float" -> "F";
                            case "double" -> "D";
                            default -> "L" + ct.name().value() + ";";
                        };
                case io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType pt ->
                        "[".repeat(at.dimensions()) + switch (pt.kind()) {
                            case BOOLEAN -> "Z";
                            case BYTE -> "B";
                            case CHAR -> "C";
                            case SHORT -> "S";
                            case INT -> "I";
                            case LONG -> "J";
                            case FLOAT -> "F";
                            case DOUBLE -> "D";
                        };
                // A parameterized component (Class<?>[] from the lang-model side)
                // erases to its raw array class for the runtime bean-type set.
                case io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt ->
                        "[".repeat(at.dimensions()) + "L" + pt.rawType().value() + ";";
                default -> null;
            };
            return desc == null ? null : Class.forName(desc, true, cl);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Recursively converts a list of {@code TypeInfo} type arguments to {@code java.lang.reflect.Type[]}.
     * Handles nested parameterized types (e.g. {@code Set<String>} inside {@code ClaimValue<Set<String>>}).
     */
    private static java.lang.reflect.Type[] resolveTypeArguments(
            java.util.List<io.vidocq.vauban.indexer.model.TypeInfo> argInfos,
            ClassLoader classLoader) throws ClassNotFoundException {
        var args = new java.lang.reflect.Type[argInfos.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = resolveTypeArgument(argInfos.get(i), classLoader);
        }
        return args;
    }

    private static java.lang.reflect.Type resolveTypeArgument(
            io.vidocq.vauban.indexer.model.TypeInfo argInfo,
            ClassLoader classLoader) throws ClassNotFoundException {
        return switch (argInfo) {
            case io.vidocq.vauban.indexer.model.TypeInfo.ClassType ct ->
                    Class.forName(ct.name().value(), true, classLoader);
            case io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt -> {
                var rawClass = Class.forName(pt.rawType().value(), true, classLoader);
                var nestedArgs = resolveTypeArguments(pt.typeArguments(), classLoader);
                yield new java.lang.reflect.ParameterizedType() {
                    @Override public java.lang.reflect.Type[] getActualTypeArguments() { return nestedArgs; }
                    @Override public java.lang.reflect.Type getRawType() { return rawClass; }
                    @Override public java.lang.reflect.Type getOwnerType() { return null; }
                };
            }
            case io.vidocq.vauban.indexer.model.TypeInfo.ArrayType at -> {
                var arrayClass = resolveArrayClass(at, classLoader);
                yield arrayClass != null ? arrayClass : Object.class;
            }
            case io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType pt ->
                    primitiveClass(pt.kind());
            default -> Object.class;
        };
    }

    private static Class<?> primitiveClass(io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType.Kind kind) {
        return switch (kind) {
            case BOOLEAN -> boolean.class;
            case BYTE -> byte.class;
            case CHAR -> char.class;
            case SHORT -> short.class;
            case INT -> int.class;
            case LONG -> long.class;
            case FLOAT -> float.class;
            case DOUBLE -> double.class;
        };
    }

    private static boolean containsUnresolvedTypeVariable(Type type) {
        return switch (type) {
            case java.lang.reflect.TypeVariable<?> _ -> true;
            case java.lang.reflect.ParameterizedType pt -> {
                for (var arg : pt.getActualTypeArguments()) {
                    if (containsUnresolvedTypeVariable(arg)) yield true;
                }
                yield false;
            }
            case java.lang.reflect.GenericArrayType gat ->
                    containsUnresolvedTypeVariable(gat.getGenericComponentType());
            case null, default -> false;
        };
    }

    private static void collectTypes(Class<?> clazz, Set<Type> types) {
        if (clazz == null || clazz == Object.class) return;
        // CDI spec: for generic bean class, add parameterized type with own type variables
        var typeParams = clazz.getTypeParameters();
        if (typeParams.length > 0) {
            types.add(new ResolvedParameterizedType(clazz, typeParams, clazz.getEnclosingClass()));
        } else {
            types.add(clazz);
        }
        // Collect supertypes (skip self)
        collectSupertypes(clazz, types, Map.of(), false);
    }

    /**
     * Walks the generic supertype hierarchy (superclass + interfaces) of {@code clazz},
     * adding every encountered type to {@code types}. Parameterized supertypes are resolved
     * against {@code typeMapping} before being added; raw {@code Class} supertypes restart
     * the walk with {@code addSelf = true} so they are added themselves.
     *
     * <p>Replaces the three historical near-identical methods
     * ({@code collectTypesFromSupers} / {@code collectTypesWithMapping} /
     * {@code collectTypesWithMappingSkipSelf}) — the only behavioral difference ever was
     * whether {@code clazz} itself is added, captured by {@code addSelf}.</p>
     */
    private static void collectSupertypes(Class<?> clazz, Set<Type> types,
            Map<java.lang.reflect.TypeVariable<?>, Type> typeMapping, boolean addSelf) {
        if (clazz == null || clazz == Object.class) return;
        if (addSelf) types.add(clazz);
        var genericSuper = clazz.getGenericSuperclass();
        if (genericSuper != null && genericSuper != Object.class) {
            collectSupertype(genericSuper, types, typeMapping);
        } else if (clazz.getSuperclass() != null) {
            collectSupertypes(clazz.getSuperclass(), types, typeMapping, true);
        }
        for (var genericIface : clazz.getGenericInterfaces()) {
            collectSupertype(genericIface, types, typeMapping);
        }
    }

    private static void collectSupertype(Type supertype, Set<Type> types,
            Map<java.lang.reflect.TypeVariable<?>, Type> typeMapping) {
        switch (supertype) {
            case java.lang.reflect.ParameterizedType pt -> {
                // Resolve type arguments using the current mapping, then build a new
                // mapping for the raw type's own type parameters before recursing
                var resolved = resolveParameterizedType(pt, typeMapping);
                types.add(resolved);
                var rawClass = (Class<?>) pt.getRawType();
                collectSupertypes(rawClass, types, buildTypeMapping(rawClass, resolved), false);
            }
            case Class<?> c -> collectSupertypes(c, types, typeMapping, true);
            default -> { }
        }
    }

    /**
     * Resolve a ParameterizedType by substituting TypeVariables using the given mapping.
     */
    private static java.lang.reflect.ParameterizedType resolveParameterizedType(
            java.lang.reflect.ParameterizedType pt,
            Map<java.lang.reflect.TypeVariable<?>, Type> mapping) {
        var args = pt.getActualTypeArguments();
        var resolved = new Type[args.length];
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof java.lang.reflect.TypeVariable<?> tv && mapping.containsKey(tv)) {
                resolved[i] = mapping.get(tv);
                changed = true;
            } else {
                resolved[i] = args[i];
            }
        }
        if (!changed) return pt;
        // Create a new ParameterizedType with resolved arguments
        var rawType = pt.getRawType();
        var owner = pt.getOwnerType();
        final Type[] finalResolved = resolved;
        return new java.lang.reflect.ParameterizedType() {
            @Override public Type[] getActualTypeArguments() { return finalResolved.clone(); }
            @Override public Type getRawType() { return rawType; }
            @Override public Type getOwnerType() { return owner; }
            @Override public boolean equals(Object o) {
                if (!(o instanceof java.lang.reflect.ParameterizedType other)) return false;
                return rawType.equals(other.getRawType())
                        && java.util.Arrays.equals(finalResolved, other.getActualTypeArguments());
            }
            @Override public int hashCode() {
                return java.util.Arrays.hashCode(finalResolved) ^ rawType.hashCode();
            }
            @Override public String toString() {
                return rawType.getTypeName() + "<" +
                        java.util.Arrays.stream(finalResolved).map(Type::getTypeName)
                                .collect(java.util.stream.Collectors.joining(", ")) + ">";
            }
        };
    }

    /**
     * Build a mapping from TypeVariables to their actual type arguments.
     */
    private static Map<java.lang.reflect.TypeVariable<?>, Type> buildTypeMapping(
            Class<?> rawClass, java.lang.reflect.ParameterizedType paramType) {
        var typeParams = rawClass.getTypeParameters();
        var typeArgs = paramType.getActualTypeArguments();
        if (typeParams.length != typeArgs.length) return Map.of();
        var mapping = new java.util.HashMap<java.lang.reflect.TypeVariable<?>, Type>();
        for (int i = 0; i < typeParams.length; i++) {
            mapping.put(typeParams[i], typeArgs[i]);
        }
        return mapping;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        // Built once: a descriptor never changes, and a lookup used to rebuild every bean's qualifiers
        // on every call.
        var instances = qualifierInstances;
        if (instances == null) {
            instances = Set.copyOf(QualifierUtils.toAnnotations(
                    descriptor.qualifiers(), descriptor.name(), classLoader, annotationTypes));
            qualifierInstances = instances;
        }
        return instances;
    }

    /**
     * The keys of this bean's qualifiers, computed once: what resolution compares. A {@code @Named}
     * bean that writes no name takes the bean's, as its qualifier instance does.
     */
    Set<io.vidocq.vauban.core.annotation.AnnotationKey> qualifierKeys(
            io.vidocq.vauban.core.bean.resolution.QualifierMatcher matcher) {
        var keys = qualifierKeys;
        if (keys == null) {
            var computed = new java.util.HashSet<io.vidocq.vauban.core.annotation.AnnotationKey>();
            for (var qualifier : descriptor.qualifiers()) {
                computed.add(matcher.key(QualifierUtils.withBeanName(qualifier, descriptor.name())));
            }
            keys = Set.copyOf(computed);
            qualifierKeys = keys;
        }
        return keys;
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return scopeAnnotationClass(descriptor.scope());
    }

    @Override
    public String getName() {
        return descriptor.name();
    }

    @Override
    public Set<Class<? extends Annotation>> getStereotypes() {
        var result = new LinkedHashSet<Class<? extends Annotation>>();
        for (var ann : beanClass.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(
                    jakarta.enterprise.inject.Stereotype.class)) {
                result.add(ann.annotationType());
            }
        }
        return result;
    }

    @Override
    public boolean isAlternative() {
        return descriptor.isAlternative();
    }

    /**
     * The bean's injection points, with the qualifiers its descriptor records for each (vauban#70):
     * a field, a constructor parameter or an initializer parameter the descriptor does not describe
     * falls back on reading the member.
     */
    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        var points = descriptor == null ? null : descriptor.injectionPoints();
        var result = new LinkedHashSet<InjectionPoint>();
        try {
            var typeMapping = buildTypeVariableMapping(beanClass);

            // Scan fields (walk hierarchy)
            Class<?> cls = beanClass;
            while (cls != null && cls != Object.class) {
                for (var field : cls.getDeclaredFields()) {
                    if (field.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        var described = QualifierHelper.fieldQualifiers(points, field, annotationTypes);
                        result.add(new VaubanInjectionPoint(
                                resolveType(field.getGenericType(), typeMapping),
                                described != null ? Set.of(described)
                                        : VaubanInjectionPoint.extractQualifiersStatic(field),
                                this, field));
                    }
                }
                cls = cls.getSuperclass();
            }
            // Scan @Inject constructor
            for (var ctor : beanClass.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    var paramTypes = ctor.getGenericParameterTypes();
                    var params = ctor.getParameters();
                    for (int i = 0; i < params.length; i++) {
                        var described = QualifierHelper.parameterQualifiers(points, ctor, i, annotationTypes);
                        var qualifiers = described != null
                                ? Set.of(described) : extractParamQualifiers(params[i]);
                        result.add(new VaubanInjectionPoint(params[i], i, ctor,
                                resolveType(paramTypes[i], typeMapping), qualifiers, this));
                    }
                }
            }
            // Scan @Inject initializer methods (walk hierarchy)
            cls = beanClass;
            while (cls != null && cls != Object.class) {
                for (var method : cls.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        var paramTypes = method.getGenericParameterTypes();
                        var params = method.getParameters();
                        for (int i = 0; i < params.length; i++) {
                            var described = QualifierHelper.parameterQualifiers(points, method, i, annotationTypes);
                            var qualifiers = described != null
                                    ? Set.of(described) : extractParamQualifiers(params[i]);
                            result.add(new VaubanInjectionPoint(params[i], i, method,
                                    resolveType(paramTypes[i], typeMapping), qualifiers, this));
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (RuntimeException e) {
            // Reporting that a bean has no injection points, or half of them, is a wrong answer —
            // and the caller has no way to tell it from the truth (BUG-20260914-17).
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.inject.spi.DefinitionException(
                    "Could not describe the injection points of " + beanClass.getName(), e);
        }
        return result;
    }

    public static Map<java.lang.reflect.TypeVariable<?>, Type> buildTypeVariableMapping(Class<?> beanClass) {
        var mapping = new java.util.HashMap<java.lang.reflect.TypeVariable<?>, Type>();
        Class<?> cls = beanClass;
        while (cls != null && cls != Object.class) {
            Type genericSuper = cls.getGenericSuperclass();
            if (genericSuper instanceof java.lang.reflect.ParameterizedType pt) {
                var rawSuper = (Class<?>) pt.getRawType();
                var typeParams = rawSuper.getTypeParameters();
                var actualArgs = pt.getActualTypeArguments();
                for (int i = 0; i < typeParams.length; i++) {
                    var resolved = resolveType(actualArgs[i], mapping);
                    mapping.put(typeParams[i], resolved);
                }
            }
            cls = cls.getSuperclass();
        }
        return mapping;
    }

    public static Type resolveType(Type type, Map<java.lang.reflect.TypeVariable<?>, Type> mapping) {
        return switch (type) {
            case java.lang.reflect.TypeVariable<?> tv -> {
                var resolved = mapping.get(tv);
                yield resolved != null ? resolved : type;
            }
            case java.lang.reflect.ParameterizedType pt -> {
                var args = pt.getActualTypeArguments();
                var resolvedArgs = new Type[args.length];
                boolean changed = false;
                for (int i = 0; i < args.length; i++) {
                    resolvedArgs[i] = resolveType(args[i], mapping);
                    if (resolvedArgs[i] != args[i]) changed = true;
                }
                yield changed
                        ? new ResolvedParameterizedType((Class<?>) pt.getRawType(), resolvedArgs, pt.getOwnerType())
                        : type;
            }
            case java.lang.reflect.GenericArrayType gat -> {
                var resolvedComponent = resolveType(gat.getGenericComponentType(), mapping);
                if (resolvedComponent instanceof Class<?> cc) {
                    yield java.lang.reflect.Array.newInstance(cc, 0).getClass();
                }
                yield resolvedComponent != gat.getGenericComponentType()
                        ? new ResolvedGenericArrayType(resolvedComponent)
                        : type;
            }
            case null, default -> type;
        };
    }

    private record ResolvedParameterizedType(Class<?> rawType, Type[] typeArguments, Type ownerType)
            implements java.lang.reflect.ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return typeArguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return ownerType; }

        @Override
        public boolean equals(Object o) {
            return o instanceof java.lang.reflect.ParameterizedType other
                    && rawType.equals(other.getRawType())
                    && java.util.Arrays.equals(typeArguments, other.getActualTypeArguments())
                    && Objects.equals(ownerType, other.getOwnerType());
        }

        @Override
        public int hashCode() {
            // Must match sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl so that
            // equal types coming from JDK reflection and from Vauban hash to the same bucket
            return java.util.Arrays.hashCode(typeArguments)
                    ^ Objects.hashCode(ownerType)
                    ^ Objects.hashCode(rawType);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder(rawType.getName());
            if (typeArguments.length > 0) {
                sb.append('<');
                for (int i = 0; i < typeArguments.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeArguments[i].getTypeName());
                }
                sb.append('>');
            }
            return sb.toString();
        }
    }

    private record ResolvedGenericArrayType(Type componentType)
            implements java.lang.reflect.GenericArrayType {
        @Override public Type getGenericComponentType() { return componentType; }

        // The record-generated equals/hashCode would only match other ResolvedGenericArrayType
        // instances; align with GenericArrayTypeImpl so JDK-built equal types interoperate
        @Override
        public boolean equals(Object o) {
            return o instanceof java.lang.reflect.GenericArrayType other
                    && componentType.equals(other.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(componentType);
        }

        @Override
        public String toString() {
            return componentType.getTypeName() + "[]";
        }
    }

    private static Set<java.lang.annotation.Annotation> extractParamQualifiers(java.lang.reflect.Parameter param) {
        io.vidocq.vauban.core.annotation.AnnotationReflection.checkParameter(param);
        var qualifiers = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : param.getAnnotations()) {
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
        return qualifiers;
    }

    public BeanDescriptor descriptor() {
        return descriptor;
    }

    private static Class<? extends Annotation> scopeAnnotationClass(ScopeInfo scope) {
        var name = scope.annotationName().value();
        try {
            @SuppressWarnings("unchecked")
            var clazz = (Class<? extends Annotation>) Class.forName(name);
            return clazz;
        } catch (ClassNotFoundException e) {
            return jakarta.enterprise.context.Dependent.class;
        }
    }

}
