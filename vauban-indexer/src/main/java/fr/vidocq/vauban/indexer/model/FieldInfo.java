package fr.vidocq.vauban.indexer.model;

import java.util.List;

public record FieldInfo(String name, TypeInfo type, int accessFlags, List<AnnotationInfo> annotations) {

    public FieldInfo {
        annotations = List.copyOf(annotations);
    }

    public boolean isStatic() {
        return (accessFlags & 0x0008) != 0;
    }

    public boolean isFinal() {
        return (accessFlags & 0x0010) != 0;
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
}
