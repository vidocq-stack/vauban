package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.ScopeInfo;
import fr.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import fr.vidocq.vauban.indexer.model.AnnotationValue;

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
    private Consumer<Object> injector;

    @SuppressWarnings("unchecked")
    public ManagedBean(BeanDescriptor descriptor, BeanFactory<T> factory) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.factory = Objects.requireNonNull(factory);
        try {
            this.beanClass = (Class<T>) Class.forName(descriptor.beanClass().value());
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

    @Override
    public T create(CreationalContext<T> creationalContext) {
        T instance = factory.create();
        if (injector != null) {
            injector.accept(instance);
        }
        return instance;
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        callPreDestroy(instance);
        if (creationalContext != null) {
            creationalContext.release();
        }
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
        var types = new LinkedHashSet<Type>();
        collectTypes(beanClass, types);
        types.add(Object.class);
        return types;
    }

    private static void collectTypes(Class<?> clazz, Set<Type> types) {
        if (clazz == null || clazz == Object.class) return;
        types.add(clazz);
        collectTypes(clazz.getSuperclass(), types);
        for (var iface : clazz.getInterfaces()) {
            collectTypes(iface, types);
        }
    }

    @Override
    public Set<Annotation> getQualifiers() {
        var result = new LinkedHashSet<Annotation>();
        for (var qi : descriptor.qualifiers()) {
            try {
                @SuppressWarnings("unchecked")
                var annClass = (Class<? extends Annotation>) Class.forName(qi.annotationName().value());
                var ann = createAnnotationInstance(annClass, qi.members());
                if (ann != null) {
                    result.add(ann);
                }
            } catch (ClassNotFoundException e) {
                // skip unresolvable qualifier
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Annotation createAnnotationInstance(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members) {
        return (Annotation) java.lang.reflect.Proxy.newProxyInstance(
            annType.getClassLoader(),
            new Class<?>[]{annType},
            (proxy, method, args) -> {
                var methodName = method.getName();
                return switch (methodName) {
                    case "annotationType" -> annType;
                    case "hashCode" -> computeAnnotationHashCode(annType, members);
                    case "equals" -> {
                        if (args[0] instanceof Annotation other) {
                            yield annType.equals(other.annotationType())
                                && membersEqual(annType, members, other);
                        }
                        yield false;
                    }
                    case "toString" -> "@" + annType.getName();
                    default -> {
                        var memberValue = members.get(methodName);
                        if (memberValue != null) {
                            yield convertAnnotationValue(memberValue);
                        }
                        var defaultValue = method.getDefaultValue();
                        if (defaultValue != null) yield defaultValue;
                        yield null;
                    }
                };
            });
    }

    private static int computeAnnotationHashCode(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members) {
        // Per Annotation.hashCode() contract: sum of (127 * memberName.hashCode() ^ memberValue.hashCode())
        int hash = 0;
        for (var m : annType.getDeclaredMethods()) {
            var memberValue = members.get(m.getName());
            Object value;
            if (memberValue != null) {
                value = convertAnnotationValue(memberValue);
            } else {
                value = m.getDefaultValue();
            }
            if (value != null) {
                hash += (127 * m.getName().hashCode()) ^ value.hashCode();
            }
        }
        return hash;
    }

    private static boolean membersEqual(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members, Annotation other) {
        try {
            for (var m : annType.getDeclaredMethods()) {
                var memberValue = members.get(m.getName());
                Object thisVal;
                if (memberValue != null) {
                    thisVal = convertAnnotationValue(memberValue);
                } else {
                    thisVal = m.getDefaultValue();
                }
                Object otherVal = m.invoke(other);
                if (!Objects.deepEquals(thisVal, otherVal)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object convertAnnotationValue(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.StringVal v -> v.value();
            case AnnotationValue.IntVal v -> v.value();
            case AnnotationValue.BooleanVal v -> v.value();
            case AnnotationValue.LongVal v -> v.value();
            case AnnotationValue.FloatVal v -> v.value();
            case AnnotationValue.DoubleVal v -> v.value();
            case AnnotationValue.ByteVal v -> v.value();
            case AnnotationValue.CharVal v -> v.value();
            case AnnotationValue.ShortVal v -> v.value();
            case AnnotationValue.ClassVal v -> {
                try { yield Class.forName(v.className().value()); }
                catch (ClassNotFoundException e) { yield Object.class; }
            }
            case AnnotationValue.EnumVal v -> {
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    var enumVal = Enum.valueOf((Class) Class.forName(v.enumType().value()), v.constantName());
                    yield enumVal;
                } catch (Exception e) { yield null; }
            }
            case AnnotationValue.AnnotationVal v -> null;
            case AnnotationValue.ArrayVal v -> v.values().stream()
                    .map(ManagedBean::convertAnnotationValue)
                    .toArray();
        };
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
        return Set.of();
    }

    @Override
    public boolean isAlternative() {
        return descriptor.isAlternative();
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return Set.of();
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
