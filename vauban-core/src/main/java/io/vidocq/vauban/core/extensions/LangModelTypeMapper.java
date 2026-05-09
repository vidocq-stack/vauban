package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import jakarta.enterprise.lang.model.types.WildcardType;

import java.util.List;

/**
 * Converts a CDI Lite lang-model {@link Type} (the type representation handed to
 * BCE methods) back into Vauban's index {@link TypeInfo}. This is the inverse of
 * {@link io.vidocq.vauban.core.langmodel.types.TypeMapper}, used by
 * {@link VaubanSyntheticBeanBuilder#type(Type)} so synthetic beans declared via
 * the lang-model API expose their full bean-type set (including parameterized
 * types like {@code Optional<String>} or {@code List<T>}) to the resolver.
 *
 * <p>Cf. VAU-BCE-001 — the previous {@code type(Type)} overload was a no-op
 * which silently dropped parameterized types, so any synthetic bean built via
 * the lang-model {@code Type} API only exposed {@code Object} as a bean type.</p>
 */
final class LangModelTypeMapper {

    private LangModelTypeMapper() {}

    static TypeInfo toIndexType(Type type) {
        return switch (type) {
            case PrimitiveType p -> new TypeInfo.PrimitiveType(switch (p.primitiveKind()) {
                case BOOLEAN -> TypeInfo.PrimitiveType.Kind.BOOLEAN;
                case BYTE -> TypeInfo.PrimitiveType.Kind.BYTE;
                case SHORT -> TypeInfo.PrimitiveType.Kind.SHORT;
                case INT -> TypeInfo.PrimitiveType.Kind.INT;
                case LONG -> TypeInfo.PrimitiveType.Kind.LONG;
                case FLOAT -> TypeInfo.PrimitiveType.Kind.FLOAT;
                case DOUBLE -> TypeInfo.PrimitiveType.Kind.DOUBLE;
                case CHAR -> TypeInfo.PrimitiveType.Kind.CHAR;
            });
            case ClassType ct -> classTypeToIndex(ct);
            case ParameterizedType pt -> {
                var rawName = classTypeName(pt.genericClass());
                List<TypeInfo> args = pt.typeArguments().stream()
                        .map(LangModelTypeMapper::toIndexType)
                        .toList();
                yield new TypeInfo.ParameterizedType(rawName, args);
            }
            case ArrayType at -> {
                var elem = toIndexType(at.componentType());
                if (elem instanceof TypeInfo.ArrayType inner) {
                    yield new TypeInfo.ArrayType(inner.componentType(), inner.dimensions() + 1);
                }
                yield new TypeInfo.ArrayType(elem, 1);
            }
            case WildcardType wt -> new TypeInfo.WildcardType(
                    wt.upperBound() != null ? toIndexType(wt.upperBound()) : null,
                    wt.lowerBound() != null ? toIndexType(wt.lowerBound()) : null);
            case TypeVariable tv -> new TypeInfo.TypeVariable(
                    tv.name(),
                    tv.bounds().stream().map(LangModelTypeMapper::toIndexType).toList());
            default -> new TypeInfo.ClassType(DotName.of(type.toString()));
        };
    }

    private static TypeInfo.ClassType classTypeToIndex(ClassType ct) {
        return new TypeInfo.ClassType(classTypeName(ct));
    }

    private static DotName classTypeName(ClassType ct) {
        // Prefer the cheap dot name from VaubanClassType (no index lookup needed).
        if (ct instanceof VaubanClassType vct) return vct.dotName();
        return DotName.of(ct.declaration().name());
    }
}
