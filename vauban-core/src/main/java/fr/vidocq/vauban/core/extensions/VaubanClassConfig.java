package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.core.langmodel.IndexLookup;
import fr.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

public final class VaubanClassConfig implements ClassConfig {

    private final Class<? extends Annotation> annotationType;
    private final ClassInfo classInfo;
    private final List<VaubanMethodConfig> methodConfigs;
    private final Set<Class<? extends Annotation>> addedAnnotations = new LinkedHashSet<>();

    public VaubanClassConfig(Class<? extends Annotation> annotationType, IndexLookup lookup) {
        this.annotationType = annotationType;

        var indexClass = lookup.getClass(
                fr.vidocq.vauban.indexer.model.DotName.of(annotationType.getName())).orElse(null);

        this.classInfo = indexClass != null ? new VaubanClassInfo(indexClass, lookup) : null;

        this.methodConfigs = new ArrayList<>();
        if (classInfo != null) {
            for (var m : classInfo.methods()) {
                methodConfigs.add(new VaubanMethodConfig(m));
            }
        } else {
            // Fallback: use reflection to get annotation methods
            for (var m : annotationType.getDeclaredMethods()) {
                methodConfigs.add(new VaubanMethodConfig(new ReflectionMethodInfo(m, annotationType)));
            }
        }
    }

    @Override
    public ClassInfo info() {
        return classInfo;
    }

    @Override
    public ClassConfig addAnnotation(Class<? extends Annotation> annotationType) {
        addedAnnotations.add(annotationType);
        return this;
    }

    @Override
    public ClassConfig addAnnotation(AnnotationInfo annotation) {
        return this;
    }

    @Override
    public ClassConfig addAnnotation(Annotation annotation) {
        addedAnnotations.add(annotation.annotationType());
        return this;
    }

    @Override
    public ClassConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        return this;
    }

    @Override
    public ClassConfig removeAllAnnotations() {
        return this;
    }

    @Override
    public Collection<MethodConfig> constructors() {
        return List.of();
    }

    @Override
    public Collection<MethodConfig> methods() {
        return List.copyOf(methodConfigs);
    }

    @Override
    public Collection<FieldConfig> fields() {
        return List.of();
    }

    public Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }

    public Set<Class<? extends Annotation>> getAddedAnnotations() {
        return Set.copyOf(addedAnnotations);
    }

    public Set<String> getNonbindingMembers() {
        var result = new HashSet<String>();
        for (var mc : methodConfigs) {
            if (mc.hasAddedAnnotation(jakarta.enterprise.util.Nonbinding.class)) {
                result.add(mc.info().name());
            }
        }
        return result;
    }

    public List<VaubanMethodConfig> getMethodConfigs() {
        return List.copyOf(methodConfigs);
    }
}
