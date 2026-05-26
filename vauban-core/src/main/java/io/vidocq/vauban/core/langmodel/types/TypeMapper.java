package io.vidocq.vauban.core.langmodel.types;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.types.PrimitiveType.PrimitiveKind;
import jakarta.enterprise.lang.model.types.Type;

/**
 * Converts indexer {@link TypeInfo} to CDI lang model {@link Type}.
 * <p>
 * Multi-dimensional arrays are represented as nested {@link VaubanArrayType} instances,
 * matching the CDI spec's recursive component type model.
 */
public final class TypeMapper {

    private TypeMapper() {
    }

    public static Type map(TypeInfo typeInfo, IndexLookup lookup) {
        return switch (typeInfo) {
            case TypeInfo.VoidType _ -> VaubanVoidType.INSTANCE;
            case TypeInfo.PrimitiveType p -> new VaubanPrimitiveType(p);
            // A primitive injection point / field type can reach the BCE boundary encoded as a
            // ClassType whose name is a reserved primitive ("boolean", "int", …) rather than a
            // TypeInfo.PrimitiveType. The CDI Lite lang model requires it to be a PrimitiveType,
            // so portable extensions can do `type instanceof PrimitiveType` (e.g. to box it).
            // Without this, such a type was exposed as ClassType[name=boolean] and broke synthetic
            // bean registration for primitive injection points (Cervantes @Claim boolean).
            case TypeInfo.ClassType c when primitiveKind(c.name()) != null ->
                    new VaubanPrimitiveType(primitiveKind(c.name()));
            case TypeInfo.ClassType c -> new VaubanClassType(c.name(), lookup);
            case TypeInfo.ArrayType a -> mapArray(a, lookup);
            case TypeInfo.ParameterizedType pt -> new VaubanParameterizedType(
                    pt.rawType(),
                    pt.typeArguments().stream().map(t -> map(t, lookup)).toList(),
                    lookup);
            case TypeInfo.TypeVariable tv -> new VaubanTypeVariable(
                    tv.name(),
                    tv.bounds().stream().map(t -> map(t, lookup)).toList());
            case TypeInfo.WildcardType w -> new VaubanWildcardType(
                    w.upperBound() != null ? map(w.upperBound(), lookup) : null,
                    w.lowerBound() != null ? map(w.lowerBound(), lookup) : null);
        };
    }

    /**
     * Maps an indexer array type to a CDI array type.
     * The indexer model stores dimensions as an int, but the CDI model uses
     * nested component types. For example, {@code int[][]} (dimensions=2) becomes
     * {@code ArrayType(ArrayType(int))}.
     */
    /**
     * Maps a reserved primitive name ("boolean", "int", …) to its {@link PrimitiveKind}, or
     * {@code null} for any real class name (no class may be named like a primitive).
     */
    private static PrimitiveKind primitiveKind(DotName name) {
        return switch (name.value()) {
            case "boolean" -> PrimitiveKind.BOOLEAN;
            case "byte" -> PrimitiveKind.BYTE;
            case "char" -> PrimitiveKind.CHAR;
            case "short" -> PrimitiveKind.SHORT;
            case "int" -> PrimitiveKind.INT;
            case "long" -> PrimitiveKind.LONG;
            case "float" -> PrimitiveKind.FLOAT;
            case "double" -> PrimitiveKind.DOUBLE;
            default -> null;
        };
    }

    private static Type mapArray(TypeInfo.ArrayType arrayType, IndexLookup lookup) {
        Type element = map(arrayType.componentType(), lookup);
        Type result = element;
        for (int i = 0; i < arrayType.dimensions(); i++) {
            result = new VaubanArrayType(result);
        }
        return result;
    }
}
