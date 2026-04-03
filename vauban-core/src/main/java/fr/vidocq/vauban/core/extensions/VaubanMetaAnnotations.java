package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.core.langmodel.IndexLookup;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;

import java.lang.annotation.Annotation;
import java.util.*;

public final class VaubanMetaAnnotations implements MetaAnnotations {

    private final IndexLookup lookup;
    private final Map<Class<? extends Annotation>, VaubanClassConfig> customQualifiers = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, VaubanClassConfig> customInterceptorBindings = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, VaubanClassConfig> customStereotypes = new LinkedHashMap<>();
    private final List<ContextRegistration> customContexts = new ArrayList<>();

    public record ContextRegistration(
            Class<? extends Annotation> scopeAnnotation,
            boolean isNormal,
            Class<? extends AlterableContext> contextClass
    ) {}

    public VaubanMetaAnnotations(IndexLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public ClassConfig addQualifier(Class<? extends Annotation> annotationType) {
        var config = new VaubanClassConfig(annotationType, lookup);
        customQualifiers.put(annotationType, config);
        return config;
    }

    @Override
    public ClassConfig addInterceptorBinding(Class<? extends Annotation> annotationType) {
        var config = new VaubanClassConfig(annotationType, lookup);
        customInterceptorBindings.put(annotationType, config);
        return config;
    }

    @Override
    public ClassConfig addStereotype(Class<? extends Annotation> annotationType) {
        var config = new VaubanClassConfig(annotationType, lookup);
        customStereotypes.put(annotationType, config);
        return config;
    }

    @Override
    public void addContext(Class<? extends Annotation> scopeAnnotation,
                           Class<? extends AlterableContext> contextClass) {
        customContexts.add(new ContextRegistration(scopeAnnotation, true, contextClass));
    }

    @Override
    public void addContext(Class<? extends Annotation> scopeAnnotation, boolean isNormal,
                           Class<? extends AlterableContext> contextClass) {
        customContexts.add(new ContextRegistration(scopeAnnotation, isNormal, contextClass));
    }

    public Set<Class<? extends Annotation>> getCustomQualifiers() {
        return Set.copyOf(customQualifiers.keySet());
    }

    public Set<Class<? extends Annotation>> getCustomInterceptorBindings() {
        return Set.copyOf(customInterceptorBindings.keySet());
    }

    public Set<Class<? extends Annotation>> getCustomStereotypes() {
        return Set.copyOf(customStereotypes.keySet());
    }

    public List<ContextRegistration> getCustomContexts() {
        return List.copyOf(customContexts);
    }

    public VaubanClassConfig getQualifierConfig(Class<? extends Annotation> annotationType) {
        return customQualifiers.get(annotationType);
    }

    public VaubanClassConfig getStereotypeConfig(Class<? extends Annotation> annotationType) {
        return customStereotypes.get(annotationType);
    }

    public Map<String, Set<String>> getNonbindingMembersPerQualifier() {
        var result = new HashMap<String, Set<String>>();
        for (var entry : customQualifiers.entrySet()) {
            var nonbinding = entry.getValue().getNonbindingMembers();
            if (!nonbinding.isEmpty()) {
                result.put(entry.getKey().getName(), nonbinding);
            }
        }
        return result;
    }

    public Map<Class<? extends Annotation>, Set<Class<? extends Annotation>>> getStereotypeAnnotations() {
        var result = new HashMap<Class<? extends Annotation>, Set<Class<? extends Annotation>>>();
        for (var entry : customStereotypes.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAddedAnnotations());
        }
        return result;
    }
}
