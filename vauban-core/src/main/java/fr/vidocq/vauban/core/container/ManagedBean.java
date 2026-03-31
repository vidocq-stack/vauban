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
import java.util.function.Consumer;

/**
 * CDI Bean implementation backed by a BeanDescriptor and a BeanFactory.
 */
public final class ManagedBean<T> implements Bean<T> {

    private final BeanDescriptor descriptor;
    private final BeanFactory<T> factory;
    private final Class<T> beanClass;
    private final ClassLoader classLoader;
    private Consumer<Object> injector;
    private Consumer<Object> destroyer;

    @SuppressWarnings("unchecked")
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

    /**
     * Sets the injector callback that will be called after instance creation
     * to resolve and inject @Inject fields.
     */
    public void setInjector(Consumer<Object> injector) {
        this.injector = injector;
    }

    /**
     * Sets the destroyer callback that will be called when the bean instance is destroyed.
     * Used for disposer methods on producer beans.
     */
    public void setDestroyer(Consumer<Object> destroyer) {
        this.destroyer = destroyer;
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        T instance = factory.create();
        // CDI spec: producer returning null for non-Dependent scope -> IllegalProductException
        if (instance == null && descriptor.kind() != BeanDescriptor.BeanKind.MANAGED) {
            if (!descriptor.scope().equals(fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT)) {
                throw new jakarta.enterprise.inject.IllegalProductException(
                        "Producer " + descriptor.id() + " returned null for non-@Dependent bean");
            }
        }
        if (instance != null && injector != null) {
            injector.accept(instance);
        }
        return instance;
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        // Call disposer method first (for producer beans)
        if (destroyer != null && instance != null) {
            try {
                destroyer.accept(instance);
            } catch (Exception e) {
                // CDI spec: exceptions in disposer methods are suppressed
            }
        }
        callPreDestroy(instance);
        // Note: don't call creationalContext.release() here — it's the caller's responsibility
        // (calling release() here would cause infinite recursion when destroy is triggered by release)
    }

    private void callPreDestroy(Object instance) {
        if (instance == null) return;
        var clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.annotation.PreDestroy.class)) {
                    method.setAccessible(true);
                    try {
                        method.invoke(instance);
                    } catch (Exception e) {
                        // CDI spec says exceptions in @PreDestroy are caught, not propagated
                    }
                    return;
                }
            }
            clazz = clazz.getSuperclass();
        }
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
        return types;
    }

    private Set<Type> getProducerTypes() {
        var types = new LinkedHashSet<Type>();
        for (var typeInfo : descriptor.types()) {
            switch (typeInfo) {
                case fr.vidocq.vauban.indexer.model.TypeInfo.ClassType ct -> {
                    try {
                        var clazz = Class.forName(ct.name().value(), true, classLoader);
                        collectTypes(clazz, types);
                    } catch (ClassNotFoundException e) {
                        // skip
                    }
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
        collectTypesWithMapping(clazz, types, Map.of());
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
        var qualifiers = new LinkedHashSet<java.lang.annotation.Annotation>();
        for (var ann : param.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                qualifiers.add(ann);
            }
        }
        if (qualifiers.isEmpty()) {
            qualifiers.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        qualifiers.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
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
