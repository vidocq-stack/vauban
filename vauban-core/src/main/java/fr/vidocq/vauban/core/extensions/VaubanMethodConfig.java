package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.inject.build.compatible.spi.ParameterConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

public final class VaubanMethodConfig implements MethodConfig {

    private final MethodInfo methodInfo;
    private final Set<Class<? extends Annotation>> addedAnnotations = new LinkedHashSet<>();

    public VaubanMethodConfig(MethodInfo methodInfo) {
        this.methodInfo = methodInfo;
    }

    @Override
    public MethodInfo info() {
        return methodInfo;
    }

    @Override
    public MethodConfig addAnnotation(Class<? extends Annotation> annotationType) {
        addedAnnotations.add(annotationType);
        return this;
    }

    @Override
    public MethodConfig addAnnotation(AnnotationInfo annotation) {
        return this;
    }

    @Override
    public MethodConfig addAnnotation(Annotation annotation) {
        try {
            addedAnnotations.add(annotation.annotationType());
        } catch (Exception ignored) {}
        return this;
    }

    @Override
    public MethodConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        return this;
    }

    @Override
    public MethodConfig removeAllAnnotations() {
        return this;
    }

    @Override
    public List<ParameterConfig> parameters() {
        return List.of();
    }

    public Set<Class<? extends Annotation>> getAddedAnnotations() {
        return Set.copyOf(addedAnnotations);
    }

    public boolean hasAddedAnnotation(Class<? extends Annotation> annotationType) {
        return addedAnnotations.contains(annotationType);
    }
}
