package io.vidocq.vauban.core.langmodel.types;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.model.TypeInfo;
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
    private static Type mapArray(TypeInfo.ArrayType arrayType, IndexLookup lookup) {
        Type element = map(arrayType.componentType(), lookup);
        Type result = element;
        for (int i = 0; i < arrayType.dimensions(); i++) {
            result = new VaubanArrayType(result);
        }
        return result;
    }
}
