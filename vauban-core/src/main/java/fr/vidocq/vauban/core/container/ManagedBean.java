package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.ScopeInfo;
import fr.vidocq.vauban.indexer.model.DotName;
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

    private final BeanDescriptor descriptor;
    private final BeanFactory<T> factory;
    private final Class<T> beanClass;
    private final ClassLoader classLoader;
    private BiConsumer<Object, CreationalContext<?>> injector;
    private Consumer<Object> destroyer;
    private fr.vidocq.vauban.core.interceptor.InterceptorManager interceptorManager;

    public ManagedBean(BeanDescriptor descriptor, BeanFactory<T> factory, ClassLoader classLoader) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.factory = Objects.requireNonNull(factory);
        this.classLoader = Objects.requireNonNull(classLoader);
        try {
            this.beanClass = (Class<T>) Class.forName(descriptor.beanClass().value(), true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Bean class not found: " + descriptor.beanClass(), e);
        }
    }

    public void setInjector(BiConsumer<Object, CreationalContext<?>> injector) {
        this.injector = injector;
    }

    public void setDestroyer(Consumer<Object> destroyer) {
        this.destroyer = destroyer;
    }

    public void setInterceptorManager(fr.vidocq.vauban.core.interceptor.InterceptorManager interceptorManager) {
        this.interceptorManager = interceptorManager;
    }

    public BeanFactory<T> factory() {
        return factory;
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        T instance = factory.create(creationalContext);
        
        // CDI spec: producer returning null for non-Dependent scope -> IllegalProductException
        if (instance == null && descriptor.kind() != BeanDescriptor.BeanKind.MANAGED) {
            if (!descriptor.scope().equals(fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT)) {
                throw new jakarta.enterprise.inject.IllegalProductException(
                        "Producer " + descriptor.id() + " returned null for non-@Dependent bean");
            }
        }
        if (instance != null && injector != null) {
            injector.accept(instance, creationalContext);
        }
        return instance;
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        if (instance == null) return;
        
        // Call disposer method first (for producer beans)
        if (destroyer != null) {
            try {
                destroyer.accept(instance);
            } catch (Exception e) {
                // CDI spec: exceptions in disposer methods are suppressed
            }
        }
        
        callPreDestroy(instance, creationalContext);

        // CDI Spec 6.1: release dependent instances tracked by this creational context.
        // Use a guard to prevent recursive release when this bean is itself being
        // destroyed by its parent's release().
        if (creationalContext instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> cc) {
            cc.release();
        }
    }

    private void callPreDestroy(Object instance, CreationalContext<?> ctx) {
        if (instance == null) return;
        // Find @PreDestroy method
        java.lang.reflect.Method preDestroyMethod = null;
        var clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.annotation.PreDestroy.class)) {
                    method.setAccessible(true);
                    preDestroyMethod = method;
                    break;
                }
            }
            if (preDestroyMethod != null) break;
            clazz = clazz.getSuperclass();
        }

        // Check for lifecycle interceptors
        if (interceptorManager != null && interceptorManager.hasInterceptors()) {
            var bindings = findInterceptorBindings(instance);
            if (!bindings.isEmpty()) {
                interceptorManager.setClassLoader(instance.getClass().getClassLoader());
                // Collect binding annotations for InvocationContext.getInterceptorBindings()
                var bindingAnnotations = collectBindingAnnotations(instance);
                var bindingAnnsList = new java.util.ArrayList<>(bindingAnnotations);
                var chain = interceptorManager.resolveLifecycleChain(
                        bindings, jakarta.annotation.PreDestroy.class, bindingAnnsList, ctx);
                if (!chain.isEmpty()) {
                    final var pdMethod = preDestroyMethod;
                    var invocationCtx = new fr.vidocq.vauban.core.interceptor.VaubanInvocationContext(
                            instance, null, new Object[0], chain,
                            (target, params) -> {
                                if (pdMethod != null) pdMethod.invoke(target);
                                return null;
                            });
                    invocationCtx.setInterceptorBindings(bindingAnnotations);
                    try {
                        invocationCtx.proceed();
                    } catch (Exception e) {
                        // CDI spec: suppress PreDestroy exceptions
                    }
                    return;
                }
            }
        }

        // No interceptors — call directly
        if (preDestroyMethod != null) {
            try {
                preDestroyMethod.invoke(instance);
            } catch (Exception e) {
                // CDI spec says exceptions in @PreDestroy are caught, not propagated
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
            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                annotations.add(ann);
            }
        }
        // Simplified transitive collection if needed, but for now basic ones
        return annotations;
    }

    private Set<fr.vidocq.vauban.indexer.model.DotName> findInterceptorBindings(Object instance) {
        var bindings = new java.util.LinkedHashSet<fr.vidocq.vauban.indexer.model.DotName>();
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted")) clazz = clazz.getSuperclass();
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                bindings.add(fr.vidocq.vauban.indexer.model.DotName.of(ann.annotationType().getName()));
            }
        }
        return bindings;
    }

    @Override
    public Class<?> getBeanClass() {
        return beanClass;
    }

    @Override
    public Set<Type> getTypes() {
        // For producer beans, derive types from the produced type, not the declaring class
        if (descriptor.kind() != BeanDescriptor.BeanKind.MANAGED) {
            return getProducerTypes();
        }
        var types = new LinkedHashSet<Type>();
        collectTypes(beanClass, types);
        types.add(Object.class);
        // Check if @Typed restricts the bean types
        if (hasTypedRestriction()) {
            return filterByTyped(types);
        }
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
        if (type instanceof Class<?> c) return c;
        if (type instanceof java.lang.reflect.ParameterizedType pt) return (Class<?>) pt.getRawType();
        return null;
    }

    private Set<Type> getProducerTypes() {
        // For @Typed producers, use reflection to get the correct parameterized types
        if (hasProducerTypedRestriction()) {
            var typedClasses = getProducerTypedClasses();
            Type producerGenericType = resolveProducerGenericType();
            if (typedClasses != null && producerGenericType != null) {
                // Build full type hierarchy from the produced type
                var allTypes = new LinkedHashSet<Type>();
                if (producerGenericType instanceof java.lang.reflect.ParameterizedType pt) {
                    allTypes.add(pt);
                    var rawClass = (Class<?>) pt.getRawType();
                    collectTypes(rawClass, allTypes);
                } else if (producerGenericType instanceof Class<?> c) {
                    collectTypes(c, allTypes);
                }
                allTypes.add(Object.class);
                // Filter by @Typed raw classes
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

        // Standard path: resolve descriptor types to Java Types
        var types = new LinkedHashSet<Type>();
        for (var typeInfo : descriptor.types()) {
            switch (typeInfo) {
                case fr.vidocq.vauban.indexer.model.TypeInfo.ClassType ct -> {
                    try {
                        types.add(Class.forName(ct.name().value(), true, classLoader));
                    } catch (ClassNotFoundException e) { /* skip */ }
                }
                case fr.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt -> {
                    try {
                        var rawClass = Class.forName(pt.rawType().value(), true, classLoader);
                        types.add(rawClass);
                    } catch (ClassNotFoundException e) { /* skip */ }
                }
                case fr.vidocq.vauban.indexer.model.TypeInfo.ArrayType at -> {
                    var arrayClass = resolveArrayClass(at, classLoader);
                    if (arrayClass != null) types.add(arrayClass);
                }
                case fr.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType pt -> {
                    types.add(primitiveClass(pt.kind()));
                }
                default -> {}
            }
        }
        types.add(Object.class);
        return types;
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
                        return m.getGenericReturnType();
                    }
                }
            } else if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                int dotIdx = idValue.lastIndexOf('.');
                if (dotIdx < 0) return null;
                String declaringClassName = idValue.substring(0, dotIdx);
                String fieldName = idValue.substring(dotIdx + 1);
                Class<?> declaringClass = Class.forName(declaringClassName, true, classLoader);
                return declaringClass.getDeclaredField(fieldName).getGenericType();
            }
        } catch (Exception e) { /* skip - fallback to descriptor */ }
        return null;
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

    private static Class<?> resolveArrayClass(fr.vidocq.vauban.indexer.model.TypeInfo.ArrayType at, ClassLoader cl) {
        try {
            var component = at.componentType();
            String desc;
            if (component instanceof fr.vidocq.vauban.indexer.model.TypeInfo.ClassType ct) {
                desc = "[".repeat(at.dimensions()) + "L" + ct.name().value() + ";";
            } else if (component instanceof fr.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType pt) {
                var primDescriptor = switch (pt.kind()) {
                    case BOOLEAN -> "Z";
                    case BYTE -> "B";
                    case CHAR -> "C";
                    case SHORT -> "S";
                    case INT -> "I";
                    case LONG -> "J";
                    case FLOAT -> "F";
                    case DOUBLE -> "D";
                };
                desc = "[".repeat(at.dimensions()) + primDescriptor;
            } else {
                return null;
            }
            return Class.forName(desc, true, cl);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Class<?> primitiveClass(fr.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType.Kind kind) {
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
        if (type instanceof java.lang.reflect.TypeVariable<?>) return true;
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsUnresolvedTypeVariable(arg)) return true;
            }
        }
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            return containsUnresolvedTypeVariable(gat.getGenericComponentType());
        }
        return false;
    }

    private static void collectTypes(Class<?> clazz, Set<Type> types) {
        if (clazz == null || clazz == Object.class) return;
        // CDI spec: for generic bean class, add parameterized type with own type variables
        var typeParams = clazz.getTypeParameters();
        if (typeParams.length > 0) {
            types.add(new ResolvedParameterizedType(clazz, typeParams));
        } else {
            types.add(clazz);
        }
        // Collect supertypes (skip self)
        collectTypesFromSupers(clazz, types, Map.of());
    }

    private static void collectTypesFromSupers(Class<?> clazz, Set<Type> types,
            Map<java.lang.reflect.TypeVariable<?>, Type> typeMapping) {
        if (clazz == null || clazz == Object.class) return;
        // Superclass
        var genericSuper = clazz.getGenericSuperclass();
        if (genericSuper != null && genericSuper != Object.class) {
            if (genericSuper instanceof java.lang.reflect.ParameterizedType pt) {
                var resolved = resolveParameterizedType(pt, typeMapping);
                types.add(resolved);
                var rawClass = (Class<?>) pt.getRawType();
                var newMapping = buildTypeMapping(rawClass, resolved);
                collectTypesFromSupers(rawClass, types, newMapping);
            } else if (genericSuper instanceof Class<?> c) {
                collectTypesWithMapping(c, types, typeMapping);
            }
        } else if (clazz.getSuperclass() != null) {
            collectTypesWithMapping(clazz.getSuperclass(), types, typeMapping);
        }
        // Interfaces
        for (var genericIface : clazz.getGenericInterfaces()) {
            if (genericIface instanceof java.lang.reflect.ParameterizedType pt) {
                var resolved = resolveParameterizedType(pt, typeMapping);
                types.add(resolved);
                var rawClass = (Class<?>) pt.getRawType();
                var newMapping = buildTypeMapping(rawClass, resolved);
                collectTypesFromSupers(rawClass, types, newMapping);
            } else if (genericIface instanceof Class<?> c) {
                collectTypesWithMapping(c, types, typeMapping);
            }
        }
    }

    private static void collectTypesWithMapping(Class<?> clazz, Set<Type> types,
            Map<java.lang.reflect.TypeVariable<?>, Type> typeMapping) {
        if (clazz == null || clazz == Object.class) return;
        types.add(clazz);
        // Superclass
        var genericSuper = clazz.getGenericSuperclass();
        if (genericSuper != null && genericSuper != Object.class) {
            if (genericSuper instanceof java.lang.reflect.ParameterizedType pt) {
                // Resolve type arguments using the current mapping
                var resolved = resolveParameterizedType(pt, typeMapping);
                types.add(resolved);
                // Build new mapping for the raw type's type parameters
                var rawClass = (Class<?>) pt.getRawType();
                var newMapping = buildTypeMapping(rawClass, resolved);
                collectTypesWithMappingSkipSelf(rawClass, types, newMapping);
            } else if (genericSuper instanceof Class<?> c) {
                collectTypesWithMapping(c, types, typeMapping);
            }
        } else if (clazz.getSuperclass() != null) {
            collectTypesWithMapping(clazz.getSuperclass(), types, typeMapping);
        }
        // Interfaces
        for (var genericIface : clazz.getGenericInterfaces()) {
            if (genericIface instanceof java.lang.reflect.ParameterizedType pt) {
                var resolved = resolveParameterizedType(pt, typeMapping);
                types.add(resolved);
                var rawClass = (Class<?>) pt.getRawType();
                var newMapping = buildTypeMapping(rawClass, resolved);
                collectTypesWithMappingSkipSelf(rawClass, types, newMapping);
            } else if (genericIface instanceof Class<?> c) {
                collectTypesWithMapping(c, types, typeMapping);
            }
        }
    }

    private static void collectTypesWithMappingSkipSelf(Class<?> clazz, Set<Type> types,
            Map<java.lang.reflect.TypeVariable<?>, Type> typeMapping) {
        if (clazz == null || clazz == Object.class) return;
        var genericSuper = clazz.getGenericSuperclass();
        if (genericSuper != null && genericSuper != Object.class) {
            if (genericSuper instanceof java.lang.reflect.ParameterizedType pt) {
                var resolved = resolveParameterizedType(pt, typeMapping);
                types.add(resolved);
                var rawClass = (Class<?>) pt.getRawType();
                var newMapping = buildTypeMapping(rawClass, resolved);
                collectTypesWithMappingSkipSelf(rawClass, types, newMapping);
            } else if (genericSuper instanceof Class<?> c) {
                collectTypesWithMapping(c, types, typeMapping);
            }
        } else if (clazz.getSuperclass() != null) {
            collectTypesWithMapping(clazz.getSuperclass(), types, typeMapping);
        }
        for (var genericIface : clazz.getGenericInterfaces()) {
            if (genericIface instanceof java.lang.reflect.ParameterizedType pt) {
                var resolved = resolveParameterizedType(pt, typeMapping);
                types.add(resolved);
                var rawClass = (Class<?>) pt.getRawType();
                var newMapping = buildTypeMapping(rawClass, resolved);
                collectTypesWithMappingSkipSelf(rawClass, types, newMapping);
            } else if (genericIface instanceof Class<?> c) {
                collectTypesWithMapping(c, types, typeMapping);
            }
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
        return QualifierUtils.toAnnotations(descriptor.qualifiers(), descriptor.name(), classLoader);
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

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        // Use reflection to get accurate generic types for injection points
        var result = new LinkedHashSet<InjectionPoint>();
        try {
            // Scan fields
            Class<?> cls = beanClass;
            while (cls != null && cls != Object.class) {
                for (var field : cls.getDeclaredFields()) {
                    if (field.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        result.add(new VaubanInjectionPoint(field, this));
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
                        var qualifiers = extractParamQualifiers(params[i]);
                        result.add(new VaubanInjectionPoint(paramTypes[i], qualifiers, this));
                    }
                }
            }
            // Scan @Inject initializer methods
            for (var method : beanClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    var paramTypes = method.getGenericParameterTypes();
                    var params = method.getParameters();
                    for (int i = 0; i < params.length; i++) {
                        var qualifiers = extractParamQualifiers(params[i]);
                        result.add(new VaubanInjectionPoint(paramTypes[i], qualifiers, this));
                    }
                }
            }
        } catch (Exception e) {
            // Fallback to empty set
        }
        return result;
    }

    private static Set<java.lang.annotation.Annotation> extractParamQualifiers(java.lang.reflect.Parameter param) {
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

    /**
     * A simple ParameterizedType implementation for generic bean types.
     */
    private record ResolvedParameterizedType(Class<?> rawClass, Type[] typeArgs)
            implements java.lang.reflect.ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return typeArgs.clone(); }
        @Override public Type getRawType() { return rawClass; }
        @Override public Type getOwnerType() { return rawClass.getEnclosingClass(); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof java.lang.reflect.ParameterizedType other)) return false;
            return rawClass.equals(other.getRawType())
                    && java.util.Arrays.equals(typeArgs, other.getActualTypeArguments());
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(typeArgs) ^ rawClass.hashCode();
        }
        @Override public String toString() {
            return rawClass.getTypeName() + "<" +
                    java.util.Arrays.stream(typeArgs).map(Type::getTypeName)
                            .collect(java.util.stream.Collectors.joining(", ")) + ">";
        }
    }
}
