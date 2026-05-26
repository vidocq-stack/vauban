package io.vidocq.vauban.indexer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TypeInfo")
class TypeInfoTest {

    @Nested
    @DisplayName("PrimitiveType")
    class PrimitiveTypeTest {

        @Test
        @DisplayName("creates all primitive types")
        void shouldCreateAllKinds() {
            for (var kind : TypeInfo.PrimitiveType.Kind.values()) {
                var type = new TypeInfo.PrimitiveType(kind);
                assertEquals(kind, type.kind());
            }
        }

        @Test
        @DisplayName("creates from a char descriptor")
        void shouldCreateFromDescriptor() {
            assertEquals(TypeInfo.PrimitiveType.Kind.INT, TypeInfo.PrimitiveType.fromDescriptor('I').kind());
            assertEquals(TypeInfo.PrimitiveType.Kind.LONG, TypeInfo.PrimitiveType.fromDescriptor('J').kind());
            assertEquals(TypeInfo.PrimitiveType.Kind.BOOLEAN, TypeInfo.PrimitiveType.fromDescriptor('Z').kind());
            assertEquals(TypeInfo.PrimitiveType.Kind.DOUBLE, TypeInfo.PrimitiveType.fromDescriptor('D').kind());
        }

        @Test
        @DisplayName("rejects an invalid descriptor")
        void shouldRejectInvalidDescriptor() {
            assertThrows(IllegalArgumentException.class, () -> TypeInfo.PrimitiveType.fromDescriptor('X'));
        }
    }

    @Nested
    @DisplayName("ClassType")
    class ClassTypeTest {

        @Test
        @DisplayName("wraps a DotName")
        void shouldWrapDotName() {
            var type = new TypeInfo.ClassType(DotName.of("java.lang.String"));
            assertEquals("java.lang.String", type.name().value());
        }
    }

    @Nested
    @DisplayName("ArrayType")
    class ArrayTypeTest {

        @Test
        @DisplayName("stores the component type and the dimensions")
        void shouldStoreComponentAndDimensions() {
            var component = new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT);
            var array = new TypeInfo.ArrayType(component, 2);
            assertEquals(component, array.componentType());
            assertEquals(2, array.dimensions());
        }
    }

    @Nested
    @DisplayName("ParameterizedType")
    class ParameterizedTypeTest {

        @Test
        @DisplayName("stores the raw type and the arguments")
        void shouldStoreRawTypeAndArguments() {
            var raw = DotName.of("java.util.List");
            var arg = new TypeInfo.ClassType(DotName.of("java.lang.String"));
            var type = new TypeInfo.ParameterizedType(raw, List.of(arg));
            assertEquals(raw, type.rawType());
            assertEquals(1, type.typeArguments().size());
            assertEquals(arg, type.typeArguments().getFirst());
        }

        @Test
        @DisplayName("makes a defensive copy of the arguments")
        void shouldDefensiveCopyArguments() {
            var args = new java.util.ArrayList<TypeInfo>();
            args.add(new TypeInfo.ClassType(DotName.of("java.lang.String")));
            var type = new TypeInfo.ParameterizedType(DotName.of("java.util.List"), args);
            args.clear();
            assertEquals(1, type.typeArguments().size());
        }
    }

    @Nested
    @DisplayName("VoidType")
    class VoidTypeTest {

        @Test
        @DisplayName("is a logical singleton")
        void shouldExist() {
            var v = new TypeInfo.VoidType();
            assertNotNull(v);
        }
    }
}
