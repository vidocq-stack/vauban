package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.spi.AnnotatedCallable;
import jakarta.enterprise.inject.spi.AnnotatedConstructor;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedParameter;
import jakarta.enterprise.inject.spi.AnnotatedType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflection-based AnnotatedType implementation for Vauban.
 */
public final class VaubanAnnotatedType<T> implements AnnotatedType<T> {

    private final Class<T> javaClass;

    public VaubanAnnotatedType(Class<T> javaClass) {
        this.javaClass = javaClass;
    }

    @Override
    public Class<T> getJavaClass() {
        return javaClass;
    }

    @Override
    public Type getBaseType() {
        return javaClass;
    }

    @Override
    public Set<AnnotatedConstructor<T>> getConstructors() {
        var result = new LinkedHashSet<AnnotatedConstructor<T>>();
        for (var ctor : javaClass.getDeclaredConstructors()) {
            @SuppressWarnings("unchecked")
            var c = (Constructor<T>) ctor;
            result.add(new VaubanAnnotatedConstructor<>(c, this));
        }
        return result;
    }

    @Override
    public Set<AnnotatedMethod<? super T>> getMethods() {
        var result = new LinkedHashSet<AnnotatedMethod<? super T>>();
        for (var method : javaClass.getDeclaredMethods()) {
            result.add(new VaubanAnnotatedMethod<>(method, this));
        }
        return result;
    }

    @Override
    public Set<AnnotatedField<? super T>> getFields() {
        var result = new LinkedHashSet<AnnotatedField<? super T>>();
        for (var field : javaClass.getDeclaredFields()) {
            result.add(new VaubanAnnotatedField<>(field, this));
        }
        return result;
    }

    @Override
    public Set<Type> getTypeClosure() {
        var types = new LinkedHashSet<Type>();
        Class<?> current = javaClass;
        while (current != null) {
            types.add(current);
            current = current.getSuperclass();
        }
        for (var iface : javaClass.getInterfaces()) {
            types.add(iface);
        }
        types.add(Object.class);
        return types;
    }

    @Override
    public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        return javaClass.getAnnotation(annotationType);
    }

    @Override
    public Set<Annotation> getAnnotations() {
        return Set.of(javaClass.getAnnotations());
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return javaClass.isAnnotationPresent(annotationType);
    }

    // --- Inner classes ---

    record VaubanAnnotatedConstructor<T>(Constructor<T> constructor, AnnotatedType<T> declaringType)
            implements AnnotatedConstructor<T> {

        @Override
        public Constructor<T> getJavaMember() {
            return constructor;
        }

        @Override
        public List<AnnotatedParameter<T>> getParameters() {
            var params = new ArrayList<AnnotatedParameter<T>>();
            for (int i = 0; i < constructor.getParameterCount(); i++) {
                params.add(new VaubanAnnotatedParameter<>(constructor.getParameters()[i], i, this));
            }
            return params;
        }

        @Override
        public AnnotatedType<T> getDeclaringType() {
            return declaringType;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public Type getBaseType() {
            return constructor.getDeclaringClass();
        }

        @Override
        public Set<Type> getTypeClosure() {
            return Set.of(constructor.getDeclaringClass(), Object.class);
        }

        @Override
        public <A extends Annotation> A getAnnotation(Class<A> t) {
            return constructor.getAnnotation(t);
        }

        @Override
        public Set<Annotation> getAnnotations() {
            return Set.of(constructor.getAnnotations());
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> t) {
            return constructor.isAnnotationPresent(t);
        }
    }

    record VaubanAnnotatedMethod<T>(Method method, AnnotatedType<T> declaringType)
            implements AnnotatedMethod<T> {

        @Override
        public Method getJavaMember() {
            return method;
        }

        @Override
        public List<AnnotatedParameter<T>> getParameters() {
            var params = new ArrayList<AnnotatedParameter<T>>();
            for (int i = 0; i < method.getParameterCount(); i++) {
                params.add(new VaubanAnnotatedParameter<>(method.getParameters()[i], i, this));
            }
            return params;
        }

        @Override
        public AnnotatedType<T> getDeclaringType() {
            return declaringType;
        }

        @Override
        public boolean isStatic() {
            return Modifier.isStatic(method.getModifiers());
        }

        @Override
        public Type getBaseType() {
            return method.getGenericReturnType();
        }

        @Override
        public Set<Type> getTypeClosure() {
            return Set.of(method.getReturnType(), Object.class);
        }

        @Override
        public <A extends Annotation> A getAnnotation(Class<A> t) {
            return method.getAnnotation(t);
        }

        @Override
        public Set<Annotation> getAnnotations() {
            return Set.of(method.getAnnotations());
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> t) {
            return method.isAnnotationPresent(t);
        }
    }

    record VaubanAnnotatedField<T>(Field field, AnnotatedType<T> declaringType)
            implements AnnotatedField<T> {

        @Override
        public Field getJavaMember() {
            return field;
        }

        @Override
        public AnnotatedType<T> getDeclaringType() {
            return declaringType;
        }

        @Override
        public boolean isStatic() {
            return Modifier.isStatic(field.getModifiers());
        }

        @Override
        public Type getBaseType() {
            return field.getGenericType();
        }

        @Override
        public Set<Type> getTypeClosure() {
            return Set.of(field.getType(), Object.class);
        }

        @Override
        public <A extends Annotation> A getAnnotation(Class<A> t) {
            return field.getAnnotation(t);
        }

        @Override
        public Set<Annotation> getAnnotations() {
            return Set.of(field.getAnnotations());
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> t) {
            return field.isAnnotationPresent(t);
        }
    }

    record VaubanAnnotatedParameter<T>(Parameter parameter, int position,
                                       AnnotatedCallable<T> declaringCallable)
            implements AnnotatedParameter<T> {

        @Override
        public int getPosition() {
            return position;
        }

        @Override
        public AnnotatedCallable<T> getDeclaringCallable() {
            return declaringCallable;
        }

        @Override
        public Type getBaseType() {
            return parameter.getParameterizedType();
        }

        @Override
        public Set<Type> getTypeClosure() {
            return Set.of(parameter.getType(), Object.class);
        }

        @Override
        public <A extends Annotation> A getAnnotation(Class<A> t) {
            return parameter.getAnnotation(t);
        }

        @Override
        public Set<Annotation> getAnnotations() {
            return Set.of(parameter.getAnnotations());
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> t) {
            return parameter.isAnnotationPresent(t);
        }
    }
}
