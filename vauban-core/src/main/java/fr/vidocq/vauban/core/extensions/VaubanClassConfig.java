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

    private final ClassInfo classInfo;
    private final List<VaubanMethodConfig> methodConfigs;
    private final List<VaubanFieldConfig> fieldConfigs;
    private final Set<Class<? extends Annotation>> addedAnnotations = new LinkedHashSet<>();
    private final List<AnnotationInfo> addedAnnotationInfos = new ArrayList<>();
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();
    private boolean allAnnotationsRemoved;

    // For meta-annotation use (Discovery phase - qualifiers, interceptor bindings, stereotypes)
    private final Class<? extends Annotation> annotationType;

    public VaubanClassConfig(Class<? extends Annotation> annotationType, IndexLookup lookup) {
        this.annotationType = annotationType;

        var indexClass = lookup.getClass(
                fr.vidocq.vauban.indexer.model.DotName.of(annotationType.getName())).orElse(null);

        this.classInfo = indexClass != null ? new VaubanClassInfo(indexClass, lookup) : null;

        this.methodConfigs = new ArrayList<>();
        this.fieldConfigs = new ArrayList<>();
        if (classInfo != null) {
            for (var m : classInfo.methods()) {
                methodConfigs.add(new VaubanMethodConfig(m));
            }
        } else {
            for (var m : annotationType.getDeclaredMethods()) {
                methodConfigs.add(new VaubanMethodConfig(new ReflectionMethodInfo(m, annotationType)));
            }
        }
    }

    // For bean enhancement (Enhancement phase)
    public VaubanClassConfig(ClassInfo classInfo) {
        this.annotationType = null;
        this.classInfo = classInfo;

        this.methodConfigs = new ArrayList<>();
        for (var m : classInfo.methods()) {
            methodConfigs.add(new VaubanMethodConfig(m));
        }
        for (var m : classInfo.constructors()) {
            methodConfigs.add(new VaubanMethodConfig(m));
        }

        this.fieldConfigs = new ArrayList<>();
        for (var f : classInfo.fields()) {
            fieldConfigs.add(new VaubanFieldConfig(f));
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
        addedAnnotationInfos.add(annotation);
        return this;
    }

    @Override
    public ClassConfig addAnnotation(Annotation annotation) {
        addedAnnotations.add(annotation.annotationType());
        return this;
    }

    @Override
    public ClassConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        removePredicates.add(predicate);
        return this;
    }

    @Override
    public ClassConfig removeAllAnnotations() {
        allAnnotationsRemoved = true;
        return this;
    }

    @Override
    public Collection<MethodConfig> constructors() {
        return methodConfigs.stream()
                .filter(mc -> mc.info().isConstructor())
                .map(mc -> (MethodConfig) mc)
                .toList();
    }

    @Override
    public Collection<MethodConfig> methods() {
        return methodConfigs.stream()
                .filter(mc -> !mc.info().isConstructor())
                .map(mc -> (MethodConfig) mc)
                .toList();
    }

    @Override
    public Collection<FieldConfig> fields() {
        return List.copyOf(fieldConfigs);
    }

    public Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }

    public Set<Class<? extends Annotation>> getAddedAnnotations() {
        return Set.copyOf(addedAnnotations);
    }

    public List<AnnotationInfo> getAddedAnnotationInfos() {
        return List.copyOf(addedAnnotationInfos);
    }

    public boolean isAllAnnotationsRemoved() {
        return allAnnotationsRemoved;
    }

    public List<Predicate<AnnotationInfo>> getRemovePredicates() {
        return List.copyOf(removePredicates);
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

    public List<VaubanFieldConfig> getFieldConfigs() {
        return List.copyOf(fieldConfigs);
    }

    public boolean isModified() {
        if (!addedAnnotations.isEmpty() || !addedAnnotationInfos.isEmpty()
                || allAnnotationsRemoved || !removePredicates.isEmpty()) {
            return true;
        }
        if (methodConfigs.stream().anyMatch(VaubanMethodConfig::isModified)) return true;
        if (fieldConfigs.stream().anyMatch(VaubanFieldConfig::isModified)) return true;
        return false;
    }
}
