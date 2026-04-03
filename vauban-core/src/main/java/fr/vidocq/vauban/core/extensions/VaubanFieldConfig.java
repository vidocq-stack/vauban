package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;

import java.lang.annotation.Annotation;
import java.util.function.Predicate;

public final class VaubanFieldConfig implements FieldConfig {

    private final FieldInfo fieldInfo;

    public VaubanFieldConfig(FieldInfo fieldInfo) {
        this.fieldInfo = fieldInfo;
    }

    @Override
    public FieldInfo info() {
        return fieldInfo;
    }

    @Override
    public FieldConfig addAnnotation(Class<? extends Annotation> annotationType) {
        return this;
    }

    @Override
    public FieldConfig addAnnotation(AnnotationInfo annotation) {
        return this;
    }

    @Override
    public FieldConfig addAnnotation(Annotation annotation) {
        return this;
    }

    @Override
    public FieldConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        return this;
    }

    @Override
    public FieldConfig removeAllAnnotations() {
        return this;
    }
}
