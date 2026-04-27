package io.vidocq.vauban.core.langmodel.types;

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class VaubanTypeVariable implements TypeVariable {

    private final String name;
    private final List<Type> bounds;

    public VaubanTypeVariable(String name, List<Type> bounds) {
        this.name = Objects.requireNonNull(name);
        this.bounds = List.copyOf(bounds);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Type> bounds() {
        return bounds;
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return false;
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return false;
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return null;
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return List.of();
    }

    @Override
    public String toString() {
        if (bounds.isEmpty()) {
            return name;
        }
        var sb = new StringBuilder(name);
        sb.append(" extends ");
        for (int i = 0; i < bounds.size(); i++) {
            if (i > 0) sb.append(" & ");
            sb.append(bounds.get(i));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaubanTypeVariable that)) return false;
        return name.equals(that.name) && bounds.equals(that.bounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, bounds);
    }
}
