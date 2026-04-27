package io.vidocq.vauban.indexer.model;

import java.util.List;
import java.util.Optional;

public record ClassInfo(
        DotName name,
        DotName superName,
        List<DotName> interfaces,
        int accessFlags,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        List<AnnotationInfo> annotations,
        ClassKind kind
) {

    public enum ClassKind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    }

    public ClassInfo {
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        annotations = List.copyOf(annotations);
    }

    public boolean isPublic() {
        return (accessFlags & 0x0001) != 0;
    }

    public boolean isAbstract() {
        return (accessFlags & 0x0400) != 0;
    }

    public boolean isFinal() {
        return (accessFlags & 0x0010) != 0;
    }

    public boolean isInterface() {
        return kind == ClassKind.INTERFACE || kind == ClassKind.ANNOTATION;
    }

    public boolean isAnnotation() {
        return kind == ClassKind.ANNOTATION;
    }

    public boolean isEnum() {
        return kind == ClassKind.ENUM;
    }

    public boolean isRecord() {
        return kind == ClassKind.RECORD;
    }

    public Optional<AnnotationInfo> annotation(DotName annotationName) {
        return annotations.stream().filter(a -> a.name().equals(annotationName)).findFirst();
    }

    public boolean hasAnnotation(DotName annotationName) {
        return annotations.stream().anyMatch(a -> a.name().equals(annotationName));
    }
}
