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
    private final List<AnnotationInfo> addedAnnotationInfos = new ArrayList<>();
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();
    private boolean allAnnotationsRemoved;
    private final List<VaubanParameterConfig> parameterConfigs;

    public VaubanMethodConfig(MethodInfo methodInfo) {
        this.methodInfo = methodInfo;
        this.parameterConfigs = new ArrayList<>();
        for (var p : methodInfo.parameters()) {
            parameterConfigs.add(new VaubanParameterConfig(p));
        }
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
        addedAnnotationInfos.add(annotation);
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
        removePredicates.add(predicate);
        return this;
    }

    @Override
    public MethodConfig removeAllAnnotations() {
        allAnnotationsRemoved = true;
        return this;
    }

    @Override
    public List<ParameterConfig> parameters() {
        return List.copyOf(parameterConfigs);
    }

    public Set<Class<? extends Annotation>> getAddedAnnotations() {
        return Set.copyOf(addedAnnotations);
    }

    public List<AnnotationInfo> getAddedAnnotationInfos() {
        return List.copyOf(addedAnnotationInfos);
    }

    public boolean hasAddedAnnotation(Class<? extends Annotation> annotationType) {
        return addedAnnotations.contains(annotationType);
    }

    public boolean isAllAnnotationsRemoved() {
        return allAnnotationsRemoved;
    }

    public List<Predicate<AnnotationInfo>> getRemovePredicates() {
        return List.copyOf(removePredicates);
    }

    public List<VaubanParameterConfig> getParameterConfigs() {
        return List.copyOf(parameterConfigs);
    }

    public boolean isModified() {
        if (!addedAnnotations.isEmpty() || !addedAnnotationInfos.isEmpty()
                || allAnnotationsRemoved || !removePredicates.isEmpty()) {
            return true;
        }
        return parameterConfigs.stream().anyMatch(VaubanParameterConfig::isModified);
    }
}
