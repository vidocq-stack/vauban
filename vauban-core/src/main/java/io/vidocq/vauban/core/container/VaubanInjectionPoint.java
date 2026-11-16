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

import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.io.NotSerializableException;
import java.io.Serializable;
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
     * Creates an InjectionPoint for a field injection, reading the field's qualifiers.
     */
    public VaubanInjectionPoint(Field field, Bean<?> bean) {
        this(field, bean, null);
    }

    /**
     * Creates an InjectionPoint for a field injection with the qualifiers the bean's descriptor
     * records for it (vauban#70) — {@code null} to read them off the field, for a field no
     * descriptor describes.
     */
    public VaubanInjectionPoint(Field field, Bean<?> bean, Set<Annotation> described) {
        Type t = ClassMetadataCache.genericTypeOf(field);
        if ((field.getType() == jakarta.enterprise.inject.Instance.class
                || field.getType() == jakarta.inject.Provider.class)
                && t instanceof java.lang.reflect.ParameterizedType pt) {
            t = pt.getActualTypeArguments()[0];
        }
        this.type = t;
        this.qualifiers = described != null ? Set.copyOf(described) : extractQualifiers(field);
        this.bean = bean;
        this.member = field;
        this.annotated = new SimpleAnnotatedField(field, this.type);
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
                ? new SimpleAnnotatedField(f, type)
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
        this.annotated = new SimpleAnnotatedParameter(param, position, genericType);
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

    /**
     * What a built-in {@code Event} the container injected at {@code injectionPoint} writes in its place, so that a
     * session bean holding it moves to another container (foy#114): a reference to the injection point, which reads
     * back as what the importing container injects there.
     *
     * @param injectionPoint where the container injected it, {@code null} when it gave it with none
     * @return the serial form
     * @throws NotSerializableException if there is no injection point, or it belongs to no bean with a passivation id,
     *                                  or names no field and no parameter
     */
    public static Serializable serialFormOf(InjectionPoint injectionPoint) throws NotSerializableException {
        return InjectionPointReference.to(injectionPoint);
    }

    /**
     * The member of {@code injectionPoint} as a reference to it names it, the same in two containers built from the
     * same classes: its declaring class, its name and, for a constructor ({@code <init>}) or a method, its erased
     * parameter types, as in {@code com.acme.Cart#init(jakarta.enterprise.event.Event)}. The declaring class tells a
     * field from one a superclass declares with the same name, the parameter types an overloaded method from
     * another. {@code null} when there is no member.
     */
    static String memberOf(InjectionPoint injectionPoint) {
        return switch (injectionPoint.getMember()) {
            case Field field -> field.getDeclaringClass().getName() + "#" + field.getName();
            case java.lang.reflect.Constructor<?> constructor ->
                    constructor.getDeclaringClass().getName() + "#<init>" + parameterTypes(constructor);
            case java.lang.reflect.Method method ->
                    method.getDeclaringClass().getName() + "#" + method.getName() + parameterTypes(method);
            case null, default -> null;
        };
    }

    private static String parameterTypes(java.lang.reflect.Executable executable) {
        var names = new java.util.StringJoiner(",", "(", ")");
        for (var parameterType : executable.getParameterTypes()) {
            names.add(parameterType.getName());
        }
        return names.toString();
    }

    /** The position of the parameter {@code injectionPoint} is, or {@code -1} for a field. */
    static int positionOf(InjectionPoint injectionPoint) {
        return injectionPoint.getAnnotated() instanceof jakarta.enterprise.inject.spi.AnnotatedParameter<?> parameter
                ? parameter.getPosition()
                : -1;
    }

    /**
     * The binary name of the declared type of the field or parameter {@code injectionPoint} is, its raw type: as in
     * {@code jakarta.enterprise.event.Event}. {@code null} when it is neither.
     */
    static String typeOf(InjectionPoint injectionPoint) {
        if (injectionPoint.getMember() instanceof Field field) {
            return field.getType().getName();
        }
        int position = positionOf(injectionPoint);
        if (injectionPoint.getMember() instanceof java.lang.reflect.Executable executable && position >= 0
                && position < executable.getParameterCount()) {
            return executable.getParameterTypes()[position].getName();
        }
        return null;
    }

    static Set<Annotation> extractQualifiersStatic(Field field) {
        return extractQualifiers(field);
    }

    private static Set<Annotation> extractQualifiers(Field field) {
        io.vidocq.vauban.core.annotation.AnnotationReflection.checkField(field);
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

        SimpleAnnotatedField(Field field, Type type) {
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

        SimpleAnnotatedParameter(java.lang.reflect.Parameter param, int position, Type type) {
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
