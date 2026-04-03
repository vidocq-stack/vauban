package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilderFactory;
import jakarta.enterprise.inject.build.compatible.spi.BuildServices;

public final class VaubanBuildServices implements BuildServices {

    private final AnnotationBuilderFactory factory = new VaubanAnnotationBuilderFactory();

    @Override
    public AnnotationBuilderFactory annotationBuilderFactory() {
        return factory;
    }

    @Override
    public int getPriority() {
        return 0;
    }
}
