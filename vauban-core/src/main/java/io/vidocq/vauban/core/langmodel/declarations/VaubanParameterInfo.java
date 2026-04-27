package io.vidocq.vauban.core.langmodel.declarations;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class VaubanParameterInfo implements jakarta.enterprise.lang.model.declarations.ParameterInfo {

    private final io.vidocq.vauban.indexer.model.ParameterInfo indexParam;
    private final VaubanMethodInfo declaringMethod;
    private final IndexLookup lookup;

    public VaubanParameterInfo(io.vidocq.vauban.indexer.model.ParameterInfo indexParam,
                               VaubanMethodInfo declaringMethod,
                               IndexLookup lookup) {
        this.indexParam = indexParam;
        this.declaringMethod = declaringMethod;
        this.lookup = lookup;
    }

    @Override
    public String name() {
        return indexParam.name();
    }

    @Override
    public Type type() {
        return TypeMapper.map(indexParam.type(), lookup);
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.MethodInfo declaringMethod() {
        return declaringMethod;
    }

    // -- AnnotationTarget --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexParam.annotations().stream().anyMatch(a -> a.name().equals(name));
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexParam.annotations().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .orElse(null);
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        var ann = annotation(annotationType);
        return ann != null ? List.of(ann) : List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return indexParam.annotations().stream()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanParameterInfo other
                && name().equals(other.name())
                && declaringMethod.equals(other.declaringMethod);
    }

    @Override
    public int hashCode() {
        return name().hashCode() * 31 + declaringMethod.hashCode();
    }

    @Override
    public String toString() {
        return name();
    }
}
