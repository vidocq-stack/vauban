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

    static final VaubanInjectionPoint EMPTY = new VaubanInjectionPoint(
            Object.class, Set.of(jakarta.enterprise.inject.Default.Literal.INSTANCE), null, null);

    private final Type type;
    private final Set<Annotation> qualifiers;
    private final Bean<?> bean;
    private final Member member;
    private final Annotated annotated;

    /**
     * Creates an InjectionPoint for a field injection.
     */
    public VaubanInjectionPoint(Field field, Bean<?> bean) {
        Type t = field.getGenericType();
        if (field.getType() == jakarta.enterprise.inject.Instance.class
                || field.getType() == jakarta.inject.Provider.class) {
            if (t instanceof java.lang.reflect.ParameterizedType pt) {
                t = pt.getActualTypeArguments()[0];
            }
        }
        this.type = t;
        this.qualifiers = extractQualifiers(field);
        this.bean = bean;
        this.member = field;
        this.annotated = new SimpleAnnotatedField(field, this.type, this.qualifiers);
    }

    /**
     * Creates an InjectionPoint from type and qualifier metadata.
     */
    public VaubanInjectionPoint(Type type, Set<Annotation> qualifiers, Bean<?> bean) {
        this(type, qualifiers, bean, null);
    }

    /**
     * Creates an InjectionPoint with member (constructor or method).
     */
    public VaubanInjectionPoint(Type type, Set<Annotation> qualifiers, Bean<?> bean, Member member) {
        this.type = type;
        this.qualifiers = Set.copyOf(qualifiers);
        this.bean = bean;
        this.member = member;
        this.annotated = (member instanceof Field f)
                ? new SimpleAnnotatedField(f, type, qualifiers)
                : new SimpleAnnotatedMember(member, type, qualifiers);
    }

    /**
     * Creates an InjectionPoint for a constructor/method parameter.
     */
    public VaubanInjectionPoint(java.lang.reflect.Parameter param, int position,
            java.lang.reflect.Executable executable, Type genericType,
            Set<Annotation> qualifiers, Bean<?> bean) {
        this.type = genericType;
        this.qualifiers = Set.copyOf(qualifiers);
        this.bean = bean;
        this.member = executable;
        this.annotated = new SimpleAnnotatedParameter(param, position, executable, genericType, qualifiers);
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

    static Set<Annotation> extractQualifiersStatic(Field field) {
        return extractQualifiers(field);
    }

    private static Set<Annotation> extractQualifiers(Field field) {
        var result = new LinkedHashSet<Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : field.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                result.add(ann);
                hasAnyAnnotation = true;
            }
        }
        // CDI 4.1 Section 2.3.5: If no qualifier is declared, @Default is the only qualifier.
        // @Any is NOT added to InjectionPoint qualifiers (it's a bean-side concept).
        if (!hasAnyAnnotation) {
            result.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return result;
    }

    @Override
    public String toString() {
        return "VaubanInjectionPoint{type=" + type + ", member=" + member + "}";
    }

    /**
     * Minimal Annotated implementation for injection point metadata.
     */
    private static final class SimpleAnnotatedMember implements Annotated {
        private final Member member;
        private final Type type;
        private final Set<Annotation> qualifiers;

        SimpleAnnotatedMember(Member member, Type type, Set<Annotation> qualifiers) {
            this.member = member;
            this.type = type;
            this.qualifiers = qualifiers;
        }

        @Override public Type getBaseType() { return type; }
        @Override public Set<Type> getTypeClosure() { return Set.of(type, Object.class); }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
            if (member == null) return null;
            if (member instanceof java.lang.reflect.AccessibleObject ao) {
                return ao.getAnnotation(annotationType);
            }
            return null;
        }

        @Override
        public Set<Annotation> getAnnotations() {
            if (member == null) return qualifiers;
            if (member instanceof java.lang.reflect.AccessibleObject ao) {
                return Set.of(ao.getAnnotations());
            }
            return qualifiers;
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
            return getAnnotation(annotationType) != null;
        }

        @Override
        public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
            if (member == null) return Set.of();
            if (member instanceof java.lang.reflect.AccessibleObject ao) {
                return Set.of(ao.getAnnotationsByType(annotationType));
            }
            return Set.of();
        }
    }

    private static final class SimpleAnnotatedField implements jakarta.enterprise.inject.spi.AnnotatedField<Object> {
        private final Field field;
        private final Type type;

        SimpleAnnotatedField(Field field, Type type, Set<Annotation> qualifiers) {
            this.field = field;
            this.type = type;
        }

        @Override public Field getJavaMember() { return field; }
        @Override public boolean isStatic() { return Modifier.isStatic(field.getModifiers()); }
        @Override public Type getBaseType() { return type; }

        @Override
        public Set<Type> getTypeClosure() {
            var types = new java.util.LinkedHashSet<Type>();
            types.add(type);
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
        public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
            return Set.of(field.getAnnotationsByType(annotationType));
        }

        @Override
        @SuppressWarnings("unchecked")
        public jakarta.enterprise.inject.spi.AnnotatedType<Object> getDeclaringType() {
            return null; // Not yet implemented
        }
    }

    /**
     * AnnotatedParameter implementation for constructor/method parameter injection points.
     */
    private static final class SimpleAnnotatedParameter implements jakarta.enterprise.inject.spi.AnnotatedParameter<Object> {
        private final java.lang.reflect.Parameter param;
        private final int position;
        private final Type type;

        SimpleAnnotatedParameter(java.lang.reflect.Parameter param, int position,
                java.lang.reflect.Executable executable, Type type, Set<Annotation> qualifiers) {
            this.param = param;
            this.position = position;
            this.type = type;
        }

        @Override public int getPosition() { return position; }

        @Override
        @SuppressWarnings("unchecked")
        public jakarta.enterprise.inject.spi.AnnotatedCallable<Object> getDeclaringCallable() {
            return null; // Simplified — TCK typically doesn't chain this
        }

        @Override public Type getBaseType() { return type; }

        @Override
        public Set<Type> getTypeClosure() {
            return Set.of(type, Object.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
            return param.getAnnotation(annotationType);
        }

        @Override
        public Set<Annotation> getAnnotations() {
            return Set.of(param.getAnnotations());
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
            return param.isAnnotationPresent(annotationType);
        }

        @Override
        public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
            return Set.of(param.getAnnotationsByType(annotationType));
        }
    }
}
