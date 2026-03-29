package fr.vidocq.vauban.indexer.model;

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
        @DisplayName("cree tous les types primitifs")
        void shouldCreateAllKinds() {
            for (var kind : TypeInfo.PrimitiveType.Kind.values()) {
                var type = new TypeInfo.PrimitiveType(kind);
                assertEquals(kind, type.kind());
            }
        }

        @Test
        @DisplayName("cree depuis un descripteur char")
        void shouldCreateFromDescriptor() {
            assertEquals(TypeInfo.PrimitiveType.Kind.INT, TypeInfo.PrimitiveType.fromDescriptor('I').kind());
            assertEquals(TypeInfo.PrimitiveType.Kind.LONG, TypeInfo.PrimitiveType.fromDescriptor('J').kind());
            assertEquals(TypeInfo.PrimitiveType.Kind.BOOLEAN, TypeInfo.PrimitiveType.fromDescriptor('Z').kind());
            assertEquals(TypeInfo.PrimitiveType.Kind.DOUBLE, TypeInfo.PrimitiveType.fromDescriptor('D').kind());
        }

        @Test
        @DisplayName("rejette un descripteur invalide")
        void shouldRejectInvalidDescriptor() {
            assertThrows(IllegalArgumentException.class, () -> TypeInfo.PrimitiveType.fromDescriptor('X'));
        }
    }

    @Nested
    @DisplayName("ClassType")
    class ClassTypeTest {

        @Test
        @DisplayName("encapsule un DotName")
        void shouldWrapDotName() {
            var type = new TypeInfo.ClassType(DotName.of("java.lang.String"));
            assertEquals("java.lang.String", type.name().value());
        }
    }

    @Nested
    @DisplayName("ArrayType")
    class ArrayTypeTest {

        @Test
        @DisplayName("stocke le type composant et les dimensions")
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
        @DisplayName("stocke le type brut et les arguments")
        void shouldStoreRawTypeAndArguments() {
            var raw = DotName.of("java.util.List");
            var arg = new TypeInfo.ClassType(DotName.of("java.lang.String"));
            var type = new TypeInfo.ParameterizedType(raw, List.of(arg));
            assertEquals(raw, type.rawType());
            assertEquals(1, type.typeArguments().size());
            assertEquals(arg, type.typeArguments().getFirst());
        }

        @Test
        @DisplayName("fait une copie defensive des arguments")
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
        @DisplayName("est un singleton logique")
        void shouldExist() {
            var v = new TypeInfo.VoidType();
            assertNotNull(v);
        }
    }
}
