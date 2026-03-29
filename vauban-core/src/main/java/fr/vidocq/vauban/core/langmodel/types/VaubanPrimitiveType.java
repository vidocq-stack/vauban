package fr.vidocq.vauban.core.langmodel.types;

import fr.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.PrimitiveType;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class VaubanPrimitiveType implements PrimitiveType {

    private final PrimitiveKind primitiveKind;

    public VaubanPrimitiveType(TypeInfo.PrimitiveType indexType) {
        this.primitiveKind = mapKind(indexType.kind());
    }

    @Override
    public String name() {
        return primitiveKind.name().toLowerCase();
    }

    @Override
    public PrimitiveKind primitiveKind() {
        return primitiveKind;
    }

    private static PrimitiveKind mapKind(TypeInfo.PrimitiveType.Kind kind) {
        return switch (kind) {
            case BOOLEAN -> PrimitiveKind.BOOLEAN;
            case BYTE -> PrimitiveKind.BYTE;
            case CHAR -> PrimitiveKind.CHAR;
            case SHORT -> PrimitiveKind.SHORT;
            case INT -> PrimitiveKind.INT;
            case LONG -> PrimitiveKind.LONG;
            case FLOAT -> PrimitiveKind.FLOAT;
            case DOUBLE -> PrimitiveKind.DOUBLE;
        };
    }

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
    public String toString() {
        return name();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaubanPrimitiveType that)) return false;
        return primitiveKind == that.primitiveKind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(primitiveKind);
    }
}
