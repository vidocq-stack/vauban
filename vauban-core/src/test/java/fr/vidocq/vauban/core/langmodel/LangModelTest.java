package fr.vidocq.vauban.core.langmodel;

import fr.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import fr.vidocq.vauban.core.langmodel.types.TypeMapper;
import fr.vidocq.vauban.core.langmodel.types.VaubanClassType;
import fr.vidocq.vauban.core.langmodel.types.VaubanPrimitiveType;
import fr.vidocq.vauban.core.langmodel.types.VaubanVoidType;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CDI Language Model bridge")
class LangModelTest {

    // Test data classes
    @Deprecated
    static class AnnotatedService {
        public String process(int id) { return ""; }
    }

    interface MyInterface {
        void doWork();
    }

    static class MyImpl extends AnnotatedService implements MyInterface {
        @Override public void doWork() {}
    }

    static IndexLookup buildLookup(Class<?>... classes) throws IOException {
        var builder = new IndexBuilder();
        for (var clazz : classes) {
            builder.add(scanClass(clazz));
        }
        return new IndexLookup(builder.build());
    }

    static ClassInfo scanClass(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
            return ClassFileScanner.scan(is.readAllBytes());
        }
    }

    @Nested
    @DisplayName("TypeMapper")
    class TypeMapperTest {

        @Test
        @DisplayName("mappe VoidType")
        void shouldMapVoidType() throws IOException {
            var lookup = buildLookup();
            var result = TypeMapper.map(new TypeInfo.VoidType(), lookup);
            assertInstanceOf(VaubanVoidType.class, result);
            assertTrue(result.isVoid());
            assertEquals("void", ((jakarta.enterprise.lang.model.types.VoidType) result).name());
        }

        @Test
        @DisplayName("mappe PrimitiveType INT")
        void shouldMapPrimitiveInt() throws IOException {
            var lookup = buildLookup();
            var result = TypeMapper.map(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT), lookup);
            assertInstanceOf(VaubanPrimitiveType.class, result);
            assertTrue(result.isPrimitive());
            assertEquals(PrimitiveType.PrimitiveKind.INT,
                    ((jakarta.enterprise.lang.model.types.PrimitiveType) result).primitiveKind());
        }

        @Test
        @DisplayName("mappe ClassType")
        void shouldMapClassType() throws IOException {
            var lookup = buildLookup(AnnotatedService.class);
            var result = TypeMapper.map(
                    new TypeInfo.ClassType(DotName.of(AnnotatedService.class.getName())), lookup);
            assertInstanceOf(VaubanClassType.class, result);
            assertTrue(result.isClass());
        }

        @Test
        @DisplayName("mappe ArrayType")
        void shouldMapArrayType() throws IOException {
            var lookup = buildLookup();
            var result = TypeMapper.map(
                    new TypeInfo.ArrayType(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT), 1), lookup);
            assertTrue(result.isArray());
            var arr = (jakarta.enterprise.lang.model.types.ArrayType) result;
            assertTrue(arr.componentType().isPrimitive());
        }
    }

    @Nested
    @DisplayName("VaubanClassInfo")
    class ClassInfoTest {

        @Test
        @DisplayName("expose le nom de la classe")
        void shouldExposeName() throws IOException {
            var lookup = buildLookup(AnnotatedService.class);
            var indexClass = scanClass(AnnotatedService.class);
            var classInfo = new VaubanClassInfo(indexClass, lookup);
            assertEquals(AnnotatedService.class.getName(), classInfo.name());
        }

        @Test
        @DisplayName("expose le nom simple")
        void shouldExposeSimpleName() throws IOException {
            var lookup = buildLookup(AnnotatedService.class);
            var indexClass = scanClass(AnnotatedService.class);
            var classInfo = new VaubanClassInfo(indexClass, lookup);
            // Inner classes have $ in their simple name from the bytecode
            assertTrue(classInfo.simpleName().contains("AnnotatedService"));
        }

        @Test
        @DisplayName("detecte les annotations")
        void shouldDetectAnnotations() throws IOException {
            var lookup = buildLookup(AnnotatedService.class);
            var indexClass = scanClass(AnnotatedService.class);
            var classInfo = new VaubanClassInfo(indexClass, lookup);
            assertTrue(classInfo.hasAnnotation(Deprecated.class));
            assertNotNull(classInfo.annotation(Deprecated.class));
        }

        @Test
        @DisplayName("expose les methodes")
        void shouldExposeMethods() throws IOException {
            var lookup = buildLookup(AnnotatedService.class);
            var indexClass = scanClass(AnnotatedService.class);
            var classInfo = new VaubanClassInfo(indexClass, lookup);
            var methodNames = classInfo.methods().stream()
                    .map(jakarta.enterprise.lang.model.declarations.MethodInfo::name).toList();
            assertTrue(methodNames.contains("process"));
        }

        @Test
        @DisplayName("expose les champs")
        void shouldExposeFields() throws IOException {
            var lookup = buildLookup(AnnotatedService.class);
            var indexClass = scanClass(AnnotatedService.class);
            var classInfo = new VaubanClassInfo(indexClass, lookup);
            assertNotNull(classInfo.fields());
        }

        @Test
        @DisplayName("expose la superclasse")
        void shouldExposeSuperclass() throws IOException {
            var lookup = buildLookup(MyImpl.class, AnnotatedService.class);
            var indexClass = scanClass(MyImpl.class);
            var classInfo = new VaubanClassInfo(indexClass, lookup);
            assertNotNull(classInfo.superClass());
            assertTrue(classInfo.superClass().isClass());
        }

        @Test
        @DisplayName("expose les super-interfaces")
        void shouldExposeSuperInterfaces() throws IOException {
            var lookup = buildLookup(MyImpl.class, MyInterface.class);
            var indexClass = scanClass(MyImpl.class);
            var classInfo = new VaubanClassInfo(indexClass, lookup);
            assertEquals(1, classInfo.superInterfaces().size());
        }

        @Test
        @DisplayName("detecte isPlainClass")
        void shouldDetectPlainClass() throws IOException {
            var lookup = buildLookup(AnnotatedService.class);
            var classInfo = new VaubanClassInfo(scanClass(AnnotatedService.class), lookup);
            assertTrue(classInfo.isPlainClass());
            assertFalse(classInfo.isInterface());
            assertFalse(classInfo.isEnum());
        }

        @Test
        @DisplayName("detecte interface")
        void shouldDetectInterface() throws IOException {
            var lookup = buildLookup(MyInterface.class);
            var classInfo = new VaubanClassInfo(scanClass(MyInterface.class), lookup);
            assertTrue(classInfo.isInterface());
            assertFalse(classInfo.isPlainClass());
        }
    }

    @Nested
    @DisplayName("VaubanAnnotationMember")
    class AnnotationMemberTest {

        @Test
        @DisplayName("expose les valeurs d'annotation")
        void shouldExposeAnnotationValues() throws IOException {
            var annot = new fr.vidocq.vauban.indexer.model.AnnotationInfo(
                    DotName.of("test.MyAnnotation"),
                    Map.of(
                            "strVal", new fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal("hello"),
                            "intVal", new fr.vidocq.vauban.indexer.model.AnnotationValue.IntVal(42),
                            "boolVal", new fr.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal(true)
                    ));
            var lookup = buildLookup();
            var member = new VaubanAnnotationMember(
                    new fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal("hello"), lookup);

            assertEquals(jakarta.enterprise.lang.model.AnnotationMember.Kind.STRING, member.kind());
            assertEquals("hello", member.asString());
            assertTrue(member.isString());
        }

        @Test
        @DisplayName("expose les valeurs int")
        void shouldExposeIntValues() throws IOException {
            var lookup = buildLookup();
            var member = new VaubanAnnotationMember(
                    new fr.vidocq.vauban.indexer.model.AnnotationValue.IntVal(42), lookup);
            assertEquals(jakarta.enterprise.lang.model.AnnotationMember.Kind.INT, member.kind());
            assertEquals(42, member.asInt());
        }
    }
}
