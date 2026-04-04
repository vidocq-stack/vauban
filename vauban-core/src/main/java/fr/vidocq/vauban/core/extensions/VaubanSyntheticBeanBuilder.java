package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Collects the configuration for a synthetic bean during @Synthesis phase.
 */
public final class VaubanSyntheticBeanBuilder<T> implements SyntheticBeanBuilder<T> {

    private final Class<T> beanClass;
    private final Set<java.lang.reflect.Type> types = new LinkedHashSet<>();
    private final Set<Annotation> qualifiers = new LinkedHashSet<>();
    private Class<? extends Annotation> scopeAnnotation;
    private String name;
    private boolean alternative;
    private int priority;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private Class<? extends SyntheticBeanCreator<T>> creatorClass;
    private Class<? extends SyntheticBeanDisposer<T>> disposerClass;

    public VaubanSyntheticBeanBuilder(Class<T> beanClass) {
        this.beanClass = beanClass;
        this.types.add(beanClass);
        this.types.add(Object.class);
    }

    @Override
    public SyntheticBeanBuilder<T> type(Class<?> type) {
        types.add(type);
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> type(ClassInfo type) {
        // Best effort: store class name, resolve later
        try {
            types.add(Class.forName(type.name(), false, beanClass.getClassLoader()));
        } catch (ClassNotFoundException e) {
            // ignore
        }
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> type(Type type) {
        return this; // simplified
    }

    @Override
    @SuppressWarnings("unchecked")
    public SyntheticBeanBuilder<T> qualifier(Class<? extends Annotation> qualifierAnnotation) {
        // Create a proxy instance of the qualifier annotation with default values
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
    public SyntheticBeanBuilder<T> qualifier(AnnotationInfo qualifierAnnotation) {
        // Convert AnnotationInfo name to a Class and create proxy
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
    public SyntheticBeanBuilder<T> qualifier(Annotation qualifierAnnotation) {
        qualifiers.add(qualifierAnnotation);
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> scope(Class<? extends Annotation> scopeAnnotation) {
        this.scopeAnnotation = scopeAnnotation;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> alternative(boolean isAlternative) {
        this.alternative = isAlternative;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> priority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> name(String name) {
        this.name = name;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> stereotype(Class<? extends Annotation> stereotypeAnnotation) {
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> stereotype(ClassInfo stereotypeAnnotation) {
        return this;
    }

    // --- withParam overloads ---

    @Override public SyntheticBeanBuilder<T> withParam(String key, boolean value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, boolean[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, int value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, int[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, long value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, long[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, double value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, double[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, String value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, String[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, Enum<?> value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, Enum<?>[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, Class<?> value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, ClassInfo value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, Class<?>[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, ClassInfo[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, AnnotationInfo value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, Annotation value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, AnnotationInfo[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, Annotation[] value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, InvokerInfo value) { params.put(key, value); return this; }
    @Override public SyntheticBeanBuilder<T> withParam(String key, InvokerInfo[] value) { params.put(key, value); return this; }

    @Override
    public SyntheticBeanBuilder<T> createWith(Class<? extends SyntheticBeanCreator<T>> creatorClass) {
        this.creatorClass = creatorClass;
        return this;
    }

    @Override
    public SyntheticBeanBuilder<T> disposeWith(Class<? extends SyntheticBeanDisposer<T>> disposerClass) {
        this.disposerClass = disposerClass;
        return this;
    }

    // --- Accessors for BceProcessor ---

    public Class<T> getBeanClass() { return beanClass; }
    public Set<java.lang.reflect.Type> getTypes() { return types; }
    public Set<Annotation> getQualifiers() { return qualifiers; }
    public Class<? extends Annotation> getScopeAnnotation() { return scopeAnnotation; }
    public String getName() { return name; }
    public boolean isAlternative() { return alternative; }
    public int getPriority() { return priority; }
    public Map<String, Object> getParams() { return params; }
    public Class<? extends SyntheticBeanCreator<T>> getCreatorClass() { return creatorClass; }
    public Class<? extends SyntheticBeanDisposer<T>> getDisposerClass() { return disposerClass; }
}
