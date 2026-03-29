package fr.vidocq.vauban.core.langmodel.declarations;

import fr.vidocq.vauban.core.langmodel.IndexLookup;
import fr.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import fr.vidocq.vauban.core.langmodel.types.TypeMapper;
import fr.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class VaubanMethodInfo implements jakarta.enterprise.lang.model.declarations.MethodInfo {

    private final fr.vidocq.vauban.indexer.model.MethodInfo indexMethod;
    private final VaubanClassInfo declaringClass;
    private final IndexLookup lookup;

    public VaubanMethodInfo(fr.vidocq.vauban.indexer.model.MethodInfo indexMethod,
                            VaubanClassInfo declaringClass,
                            IndexLookup lookup) {
        this.indexMethod = indexMethod;
        this.declaringClass = declaringClass;
        this.lookup = lookup;
    }

    @Override
    public String name() {
        return indexMethod.name();
    }

    @Override
    public List<jakarta.enterprise.lang.model.declarations.ParameterInfo> parameters() {
        var params = new ArrayList<jakarta.enterprise.lang.model.declarations.ParameterInfo>();
        for (int i = 0; i < indexMethod.parameters().size(); i++) {
            params.add(new VaubanParameterInfo(indexMethod.parameters().get(i), this, lookup));
        }
        return List.copyOf(params);
    }

    @Override
    public Type returnType() {
        return TypeMapper.map(indexMethod.returnType(), lookup);
    }

    @Override
    public Type receiverType() {
        return null;
    }

    @Override
    public List<Type> throwsTypes() {
        return indexMethod.exceptionTypes().stream()
                .map(t -> TypeMapper.map(t, lookup))
                .toList();
    }

    @Override
    public List<TypeVariable> typeParameters() {
        return List.of();
    }

    @Override
    public boolean isConstructor() {
        return indexMethod.isConstructor();
    }

    @Override
    public boolean isStatic() {
        return indexMethod.isStatic();
    }

    @Override
    public boolean isAbstract() {
        return indexMethod.isAbstract();
    }

    @Override
    public boolean isFinal() {
        return (indexMethod.accessFlags() & 0x0010) != 0;
    }

    @Override
    public int modifiers() {
        return indexMethod.accessFlags();
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo declaringClass() {
        return declaringClass;
    }

    // -- AnnotationTarget --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexMethod.annotations().stream().anyMatch(a -> a.name().equals(name));
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexMethod.annotations().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .orElse(null);
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        var ann = annotation(annotationType);
        return ann != null ? List.of(ann) : List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return indexMethod.annotations().stream()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanMethodInfo other
                && name().equals(other.name())
                && declaringClass.equals(other.declaringClass);
    }

    @Override
    public int hashCode() {
        return name().hashCode() * 31 + declaringClass.hashCode();
    }

    @Override
    public String toString() {
        return declaringClass.name() + "#" + name() + "()";
    }
}
