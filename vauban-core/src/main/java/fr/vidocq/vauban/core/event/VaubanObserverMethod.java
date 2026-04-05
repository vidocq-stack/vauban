package fr.vidocq.vauban.core.event;

import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.core.container.QualifierUtils;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * CDI {@link ObserverMethod} implementation backed by an {@link ObserverDescriptor}.
 */
public final class VaubanObserverMethod<T> implements ObserverMethod<T> {

    private final ObserverDescriptor descriptor;
    private final EventDispatcher dispatcher;
    private final Bean<?> declaringBean;
    private final ClassLoader classLoader;

    public VaubanObserverMethod(ObserverDescriptor descriptor, EventDispatcher dispatcher,
            Bean<?> declaringBean, ClassLoader classLoader) {
        this.descriptor = descriptor;
        this.dispatcher = dispatcher;
        this.declaringBean = declaringBean;
        this.classLoader = classLoader;
    }

    @Override
    public Class<?> getBeanClass() {
        try {
            return Class.forName(descriptor.declaringClass().value(), true, classLoader);
        } catch (ClassNotFoundException e) {
            return Object.class;
        }
    }

    @Override
    public Bean<?> getDeclaringBean() {
        return declaringBean;
    }

    @Override
    public Type getObservedType() {
        return resolveTypeInfo(descriptor.eventType());
    }

    private Type resolveTypeInfo(TypeInfo typeInfo) {
        return switch (typeInfo) {
            case TypeInfo.ClassType ct -> {
                try {
                    yield Class.forName(ct.name().value(), true, classLoader);
                } catch (ClassNotFoundException e) {
                    yield Object.class;
                }
            }
            case TypeInfo.ParameterizedType pt -> {
                try {
                    var rawClass = Class.forName(pt.rawType().value(), true, classLoader);
                    var typeArgs = pt.typeArguments().stream()
                            .map(this::resolveTypeInfo)
                            .toArray(Type[]::new);
                    yield new ResolvedParameterizedType(rawClass, typeArgs);
                } catch (ClassNotFoundException e) {
                    yield Object.class;
                }
            }
            case TypeInfo.TypeVariable tv -> {
                var bounds = tv.bounds().stream()
                        .map(this::resolveTypeInfo)
                        .filter(t -> t != Object.class)
                        .toArray(Type[]::new);
                yield new ResolvedTypeVariable(tv.name(), bounds.length > 0 ? bounds : new Type[]{Object.class});
            }
            case TypeInfo.WildcardType wt -> {
                var upper = wt.upperBound() != null ? resolveTypeInfo(wt.upperBound()) : Object.class;
                var lower = wt.lowerBound() != null ? resolveTypeInfo(wt.lowerBound()) : null;
                yield new ResolvedWildcardType(
                        new Type[]{upper},
                        lower != null ? new Type[]{lower} : new Type[0]);
            }
            case TypeInfo.ArrayType at -> {
                var component = resolveTypeInfo(at.componentType());
                if (component instanceof Class<?> cc) {
                    yield java.lang.reflect.Array.newInstance(cc, 0).getClass();
                }
                yield new ResolvedGenericArrayType(component);
            }
            default -> Object.class;
        };
    }

    private static final class ResolvedParameterizedType implements java.lang.reflect.ParameterizedType {
        private final Class<?> rawType;
        private final Type[] typeArguments;
        ResolvedParameterizedType(Class<?> rawType, Type[] typeArguments) {
            this.rawType = rawType;
            this.typeArguments = typeArguments;
        }
        @Override public Type[] getActualTypeArguments() { return typeArguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return null; }
        @Override public boolean equals(Object o) {
            return o instanceof java.lang.reflect.ParameterizedType other
                    && rawType.equals(other.getRawType())
                    && java.util.Arrays.equals(typeArguments, other.getActualTypeArguments())
                    && java.util.Objects.equals(null, other.getOwnerType());
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(typeArguments) ^ rawType.hashCode();
        }
        @Override public String toString() {
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

    private record ResolvedWildcardType(Type[] upperBounds, Type[] lowerBounds)
            implements java.lang.reflect.WildcardType {
        @Override public Type[] getUpperBounds() { return upperBounds.clone(); }
        @Override public Type[] getLowerBounds() { return lowerBounds.clone(); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof java.lang.reflect.WildcardType other)) return false;
            return java.util.Arrays.equals(upperBounds, other.getUpperBounds())
                    && java.util.Arrays.equals(lowerBounds, other.getLowerBounds());
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(upperBounds) ^ java.util.Arrays.hashCode(lowerBounds);
        }
    }

    @SuppressWarnings("unchecked")
    private static final class ResolvedTypeVariable implements java.lang.reflect.TypeVariable<java.lang.reflect.GenericDeclaration> {
        private final String tvName;
        private final Type[] tvBounds;
        ResolvedTypeVariable(String name, Type[] bounds) { this.tvName = name; this.tvBounds = bounds; }
        @Override public Type[] getBounds() { return tvBounds.clone(); }
        @Override public java.lang.reflect.GenericDeclaration getGenericDeclaration() { return Object.class; }
        @Override public String getName() { return tvName; }
        @Override public java.lang.reflect.AnnotatedType[] getAnnotatedBounds() { return new java.lang.reflect.AnnotatedType[0]; }
        @Override public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> c) { return null; }
        @Override public java.lang.annotation.Annotation[] getAnnotations() { return new java.lang.annotation.Annotation[0]; }
        @Override public java.lang.annotation.Annotation[] getDeclaredAnnotations() { return new java.lang.annotation.Annotation[0]; }
    }

    private record ResolvedGenericArrayType(Type componentType)
            implements java.lang.reflect.GenericArrayType {
        @Override public Type getGenericComponentType() { return componentType; }
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        return QualifierUtils.toAnnotations(new java.util.LinkedHashSet<>(descriptor.qualifiers()), null, classLoader);
    }

    @Override
    public Reception getReception() {
        return switch (descriptor.reception()) {
            case "IF_EXISTS" -> Reception.IF_EXISTS;
            default -> Reception.ALWAYS;
        };
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        return switch (descriptor.transactionPhase()) {
            case "BEFORE_COMPLETION" -> TransactionPhase.BEFORE_COMPLETION;
            case "AFTER_COMPLETION" -> TransactionPhase.AFTER_COMPLETION;
            case "AFTER_FAILURE" -> TransactionPhase.AFTER_FAILURE;
            case "AFTER_SUCCESS" -> TransactionPhase.AFTER_SUCCESS;
            default -> TransactionPhase.IN_PROGRESS;
        };
    }

    @Override
    public int getPriority() {
        return descriptor.priority();
    }

    @Override
    public boolean isAsync() {
        return descriptor.async();
    }

    @Override
    public void notify(T event) {
        // CDI spec: notify() directly invokes THIS observer, not all matching observers
        dispatcher.invokeObserverDirect(descriptor, event);
    }

    @Override
    public void notify(EventContext<T> eventContext) {
        notify(eventContext.getEvent());
    }
}
