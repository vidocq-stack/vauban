package io.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.ParameterConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

public final class VaubanParameterConfig implements ParameterConfig {

    private final ParameterInfo parameterInfo;
    private final List<AnnotationInfo> addedAnnotations = new ArrayList<>();
    private final Set<Class<? extends Annotation>> addedAnnotationClasses = new LinkedHashSet<>();
    private boolean allAnnotationsRemoved;
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();

    public VaubanParameterConfig(ParameterInfo parameterInfo) {
        this.parameterInfo = parameterInfo;
    }

    @Override
    public ParameterInfo info() {
        return parameterInfo;
    }

    @Override
    public ParameterConfig addAnnotation(Class<? extends Annotation> annotationType) {
        addedAnnotationClasses.add(annotationType);
        return this;
    }

    @Override
    public ParameterConfig addAnnotation(AnnotationInfo annotation) {
        addedAnnotations.add(annotation);
        return this;
    }

    @Override
    public ParameterConfig addAnnotation(Annotation annotation) {
        addedAnnotationClasses.add(annotation.annotationType());
        return this;
    }

    @Override
    public ParameterConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        removePredicates.add(predicate);
        return this;
    }

    @Override
    public ParameterConfig removeAllAnnotations() {
        allAnnotationsRemoved = true;
        return this;
    }

    public Set<Class<? extends Annotation>> getAddedAnnotationClasses() {
        return Set.copyOf(addedAnnotationClasses);
    }

    public List<AnnotationInfo> getAddedAnnotations() {
        return List.copyOf(addedAnnotations);
    }

    public boolean isAllAnnotationsRemoved() {
        return allAnnotationsRemoved;
    }

    public List<Predicate<AnnotationInfo>> getRemovePredicates() {
        return List.copyOf(removePredicates);
    }

    public boolean isModified() {
        return !addedAnnotationClasses.isEmpty() || !addedAnnotations.isEmpty()
                || allAnnotationsRemoved || !removePredicates.isEmpty();
    }
}
