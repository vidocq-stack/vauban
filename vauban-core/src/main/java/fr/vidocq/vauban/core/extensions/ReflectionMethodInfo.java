package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

final class ReflectionMethodInfo implements jakarta.enterprise.lang.model.declarations.MethodInfo {

    private final Method method;

    ReflectionMethodInfo(Method method, Class<?> declaringClass) {
        this.method = method;
    }

    @Override
    public String name() {
        return method.getName();
    }

    @Override
    public List<ParameterInfo> parameters() {
        return List.of();
    }

    @Override
    public Type returnType() {
        return null;
    }

    @Override
    public Type receiverType() {
        return null;
    }

    @Override
    public List<Type> throwsTypes() {
        return List.of();
    }

    @Override
    public List<TypeVariable> typeParameters() {
        return List.of();
    }

    @Override
    public boolean isConstructor() {
        return false;
    }

    @Override
    public boolean isStatic() {
        return java.lang.reflect.Modifier.isStatic(method.getModifiers());
    }

    @Override
    public boolean isAbstract() {
        return java.lang.reflect.Modifier.isAbstract(method.getModifiers());
    }

    @Override
    public boolean isFinal() {
        return java.lang.reflect.Modifier.isFinal(method.getModifiers());
    }

    @Override
    public int modifiers() {
        return method.getModifiers();
    }

    @Override
    public ClassInfo declaringClass() {
        return null;
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return method.isAnnotationPresent(annotationType);
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
}
