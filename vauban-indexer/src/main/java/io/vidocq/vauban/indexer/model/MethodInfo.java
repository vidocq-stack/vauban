package io.vidocq.vauban.indexer.model;

import java.util.List;

public record MethodInfo(
        String name,
        TypeInfo returnType,
        List<ParameterInfo> parameters,
        List<TypeInfo> exceptionTypes,
        int accessFlags,
        List<AnnotationInfo> annotations
) {

    public MethodInfo {
        parameters = List.copyOf(parameters);
        exceptionTypes = List.copyOf(exceptionTypes);
        annotations = List.copyOf(annotations);
    }

    public boolean isConstructor() {
        return "<init>".equals(name);
    }

    public boolean isStaticInitializer() {
        return "<clinit>".equals(name);
    }

    public boolean isStatic() {
        return (accessFlags & 0x0008) != 0;
    }

    public boolean isAbstract() {
        return (accessFlags & 0x0400) != 0;
    }

    public boolean isPublic() {
        return (accessFlags & 0x0001) != 0;
    }

    public boolean isPrivate() {
        return (accessFlags & 0x0002) != 0;
    }

    public boolean isProtected() {
        return (accessFlags & 0x0004) != 0;
    }

    public boolean isSynthetic() {
        return (accessFlags & 0x1000) != 0;
    }
}
