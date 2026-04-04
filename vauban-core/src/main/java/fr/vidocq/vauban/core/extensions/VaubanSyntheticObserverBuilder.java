package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.util.*;

public final class VaubanSyntheticObserverBuilder<T> implements SyntheticObserverBuilder<T> {

    private final java.lang.reflect.Type eventType;
    private final Set<Annotation> qualifiers = new LinkedHashSet<>();
    private int priority = jakarta.interceptor.Interceptor.Priority.APPLICATION;
    private boolean async;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private Class<? extends SyntheticObserver<T>> observerClass;

    public VaubanSyntheticObserverBuilder(Class<T> eventType) {
        this.eventType = eventType;
    }

    public VaubanSyntheticObserverBuilder(Type eventType) {
        this.eventType = toReflectType(eventType);
    }

    private static java.lang.reflect.Type toReflectType(Type type) {
        if (type instanceof jakarta.enterprise.lang.model.types.ClassType ct) {
            var name = extractClassName(ct);
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Cannot resolve type: " + name, e);
            }
        }
        if (type instanceof jakarta.enterprise.lang.model.types.ParameterizedType pt) {
            var rawName = extractClassName(pt.genericClass());
            try {
                var rawClass = Class.forName(rawName);
                var typeArgs = pt.typeArguments().stream()
                        .map(VaubanSyntheticObserverBuilder::toReflectType)
                        .toArray(java.lang.reflect.Type[]::new);
                return new SimpleParameterizedType(rawClass, typeArgs);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Cannot resolve type: " + rawName, e);
            }
        }
        throw new IllegalArgumentException("Unsupported type for synthetic observer: " + type);
    }

    private static String extractClassName(jakarta.enterprise.lang.model.types.ClassType ct) {
        if (ct instanceof fr.vidocq.vauban.core.langmodel.types.VaubanClassType vct) {
            return vct.dotName().value();
        }
        return ct.declaration().name();
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(Class<?> declaringClass) {
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(ClassInfo declaringClass) {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SyntheticObserverBuilder<T> qualifier(Class<? extends Annotation> qualifierAnnotation) {
        qualifiers.add((Annotation) java.lang.reflect.Proxy.newProxyInstance(
                qualifierAnnotation.getClassLoader(),
                new Class<?>[]{qualifierAnnotation},
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) return qualifierAnnotation;
                    if ("hashCode".equals(method.getName())) return 0;
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("toString".equals(method.getName())) return "@" + qualifierAnnotation.getName();
                    return method.getDefaultValue();
                }));
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(AnnotationInfo qualifierAnnotation) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            @SuppressWarnings("unchecked")
            var annClass = (Class<? extends Annotation>) (cl != null
                    ? Class.forName(qualifierAnnotation.name(), false, cl)
                    : Class.forName(qualifierAnnotation.name()));
            return qualifier(annClass);
        } catch (ClassNotFoundException e) {
            return this;
        }
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(Annotation qualifierAnnotation) {
        qualifiers.add(qualifierAnnotation);
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> priority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> async(boolean isAsync) {
        this.async = isAsync;
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> transactionPhase(jakarta.enterprise.event.TransactionPhase transactionPhase) {
        return this;
    }

    // --- withParam overloads ---
    @Override public SyntheticObserverBuilder<T> withParam(String key, boolean value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, boolean[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, int value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, int[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, long value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, long[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, double value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, double[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, String value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, String[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Enum<?> value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Enum<?>[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Class<?> value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, ClassInfo value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Class<?>[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, ClassInfo[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Annotation value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Annotation[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, InvokerInfo value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, InvokerInfo[] value) { params.put(key, value); return this; }

    @Override
    public SyntheticObserverBuilder<T> observeWith(Class<? extends SyntheticObserver<T>> observerClass) {
        this.observerClass = observerClass;
        return this;
    }

    // --- Accessors ---
    public java.lang.reflect.Type getEventType() { return eventType; }
    public Set<Annotation> getQualifiers() { return qualifiers; }
    public int getPriority() { return priority; }
    public boolean isAsync() { return async; }
    public Map<String, Object> getParams() { return params; }
    public Class<? extends SyntheticObserver<T>> getObserverClass() { return observerClass; }

    private record SimpleParameterizedType(
            Class<?> rawType,
            java.lang.reflect.Type[] typeArguments
    ) implements java.lang.reflect.ParameterizedType {
        @Override public java.lang.reflect.Type[] getActualTypeArguments() { return typeArguments; }
        @Override public java.lang.reflect.Type getRawType() { return rawType; }
        @Override public java.lang.reflect.Type getOwnerType() { return null; }
    }
}
