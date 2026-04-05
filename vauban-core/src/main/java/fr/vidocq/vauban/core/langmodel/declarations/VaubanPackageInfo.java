package fr.vidocq.vauban.core.langmodel.declarations;

import jakarta.enterprise.lang.model.AnnotationInfo;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class VaubanPackageInfo implements jakarta.enterprise.lang.model.declarations.PackageInfo {

    private final String packageName;

    public VaubanPackageInfo(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public String name() {
        return packageName;
    }

    // -- AnnotationTarget (packages have no annotations in our model) --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return false;
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return false;
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return null;
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return List.of();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanPackageInfo other && packageName.equals(other.packageName);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode();
    }

    @Override
    public String toString() {
        return "package " + packageName;
    }
}
