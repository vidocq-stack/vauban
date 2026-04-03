package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilderFactory;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;

public final class VaubanAnnotationBuilderFactory implements AnnotationBuilderFactory {

    @Override
    public AnnotationBuilder create(Class<? extends Annotation> annotationType) {
        return new VaubanAnnotationBuilder(annotationType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public AnnotationBuilder create(ClassInfo annotationType) {
        try {
            Class<?> clazz = Class.forName(annotationType.name());
            return new VaubanAnnotationBuilder((Class<? extends Annotation>) clazz);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Annotation type not found: " + annotationType.name(), e);
        }
    }
}
