package fr.vidocq.vauban.indexer.model;

import java.util.List;

public sealed interface TypeInfo {

    record VoidType() implements TypeInfo {}

    record PrimitiveType(Kind kind) implements TypeInfo {

        public enum Kind {
            BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE
        }

        public static PrimitiveType fromDescriptor(char desc) {
            return new PrimitiveType(switch (desc) {
                case 'Z' -> Kind.BOOLEAN;
                case 'B' -> Kind.BYTE;
                case 'C' -> Kind.CHAR;
                case 'S' -> Kind.SHORT;
                case 'I' -> Kind.INT;
                case 'J' -> Kind.LONG;
                case 'F' -> Kind.FLOAT;
                case 'D' -> Kind.DOUBLE;
                default -> throw new IllegalArgumentException("Unknown primitive descriptor: " + desc);
            });
        }
    }

    record ClassType(DotName name) implements TypeInfo {}

    record ArrayType(TypeInfo componentType, int dimensions) implements TypeInfo {}

    record ParameterizedType(DotName rawType, List<TypeInfo> typeArguments) implements TypeInfo {
        public ParameterizedType {
            typeArguments = List.copyOf(typeArguments);
        }
    }

    record TypeVariable(String name, List<TypeInfo> bounds) implements TypeInfo {
        public TypeVariable {
            bounds = List.copyOf(bounds);
        }
    }

    record WildcardType(TypeInfo upperBound, TypeInfo lowerBound) implements TypeInfo {}
}
