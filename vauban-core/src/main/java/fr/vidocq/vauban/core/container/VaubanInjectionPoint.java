package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Implementation of {@link InjectionPoint} for Vauban CDI container.
 * Represents the metadata about a point where a bean is injected (field, parameter, etc.).
 */
public final class VaubanInjectionPoint implements InjectionPoint {
    private final Type type;
    private final Set<Annotation> qualifiers;
    private final Bean<?> bean;
    private final Member member;
    private final Annotated annotated;

    /**
     * Creates an InjectionPoint for a field injection.
     */
    public VaubanInjectionPoint(Field field, Bean<?> bean) {
        this.type = field.getGenericType();
        this.qualifiers = extractQualifiers(field);
        this.bean = bean;
        this.member = field;
        this.annotated = new SimpleAnnotatedField(field);
    }

    /**
     * Creates an InjectionPoint from type and qualifier metadata.
     */
    public VaubanInjectionPoint(Type type, Set<Annotation> qualifiers, Bean<?> bean) {
        this.type = type;
        this.qualifiers = Set.copyOf(qualifiers);
        this.bean = bean;
        this.member = null;
        this.annotated = null;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return qualifiers;
    }

    @Override
    public Bean<?> getBean() {
        return bean;
    }

    @Override
    public Member getMember() {
        return member;
    }

    @Override
    public Annotated getAnnotated() {
        return annotated;
    }

    @Override
    public boolean isDelegate() {
        return false;
    }

    @Override
    public boolean isTransient() {
        return member instanceof Field f && Modifier.isTransient(f.getModifiers());
    }

    private static Set<Annotation> extractQualifiers(Field field) {
        var result = new LinkedHashSet<Annotation>();
        for (var ann : field.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                result.add(ann);
            }
        }
        if (result.isEmpty()) {
            result.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        result.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        return result;
    }

    @Override
    public String toString() {
        return "VaubanInjectionPoint{type=" + type + ", member=" + member + "}";
    }

    /**
     * Minimal Annotated implementation for injection point metadata.
     */
    private static final class SimpleAnnotatedField implements jakarta.enterprise.inject.spi.AnnotatedField<Object> {
        private final Field field;

        SimpleAnnotatedField(Field field) {
            this.field = field;
        }

        @Override public Field getJavaMember() { return field; }
        @Override public boolean isStatic() { return Modifier.isStatic(field.getModifiers()); }
        @Override public Type getBaseType() { return field.getGenericType(); }

        @Override
        public Set<Type> getTypeClosure() {
            var types = new java.util.LinkedHashSet<Type>();
            types.add(field.getGenericType());
            types.add(Object.class);
            return types;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
            return field.getAnnotation(annotationType);
        }

        @Override
        public Set<Annotation> getAnnotations() {
            return Set.of(field.getAnnotations());
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
            return field.isAnnotationPresent(annotationType);
        }

        @Override
        @SuppressWarnings("unchecked")
        public jakarta.enterprise.inject.spi.AnnotatedType<Object> getDeclaringType() {
            return null; // Not yet implemented
        }
    }
}
