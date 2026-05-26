package io.vidocq.vauban.indexer.scanner;

import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.model.TypeInfo.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClassFileScanner")
class ClassFileScannerTest {

    // --- Test classes ---

    static class SimpleClass {
        private String name;
        public int value;

        public String getName() { return name; }
        public void setValue(int value) { this.value = value; }
    }

    @Deprecated
    static class AnnotatedClass {
        @Deprecated
        public void deprecatedMethod() {}
    }

    interface SimpleInterface {
        void doSomething();
        default String defaultMethod() { return "default"; }
    }

    static class ImplementingClass extends SimpleClass implements SimpleInterface {
        @Override public void doSomething() {}
    }

    enum SimpleEnum { A, B, C }

    record SimpleRecord(String name, int value) {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface CustomAnnotation {
        String value();
        int count() default 0;
    }

    @CustomAnnotation(value = "test", count = 42)
    static class CustomAnnotatedClass {}

    static abstract class AbstractClass {
        public abstract void abstractMethod();
        public final void finalMethod() {}
    }

    // --- Utility ---

    private static byte[] bytesOf(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, "Could not find class resource: " + resource);
            return is.readAllBytes();
        }
    }

    private static ClassInfo scan(Class<?> clazz) throws IOException {
        return ClassFileScanner.scan(bytesOf(clazz));
    }

    // --- Tests ---

    @Nested
    @DisplayName("scan of a simple class")
    class ScanSimpleClass {

        @Test
        @DisplayName("extracts the fully qualified class name")
        void shouldExtractClassName() throws IOException {
            var info = scan(SimpleClass.class);
            assertEquals(DotName.of(SimpleClass.class.getName()), info.name());
        }

        @Test
        @DisplayName("extracts the superclass (java.lang.Object)")
        void shouldExtractSuperclass() throws IOException {
            var info = scan(SimpleClass.class);
            assertEquals(DotName.of("java.lang.Object"), info.superName());
        }

        @Test
        @DisplayName("extracts the fields")
        void shouldExtractFields() throws IOException {
            var info = scan(SimpleClass.class);
            var fieldNames = info.fields().stream().map(FieldInfo::name).toList();
            assertTrue(fieldNames.contains("name"), "should contain the 'name' field");
            assertTrue(fieldNames.contains("value"), "should contain the 'value' field");
        }

        @Test
        @DisplayName("extracts the field types")
        void shouldExtractFieldTypes() throws IOException {
            var info = scan(SimpleClass.class);
            var nameField = info.fields().stream().filter(f -> f.name().equals("name")).findFirst().orElseThrow();
            assertInstanceOf(ClassType.class, nameField.type());
            assertEquals(DotName.of("java.lang.String"), ((ClassType) nameField.type()).name());

            var valueField = info.fields().stream().filter(f -> f.name().equals("value")).findFirst().orElseThrow();
            assertInstanceOf(PrimitiveType.class, valueField.type());
            assertEquals(PrimitiveType.Kind.INT, ((PrimitiveType) valueField.type()).kind());
        }

        @Test
        @DisplayName("extracts the methods")
        void shouldExtractMethods() throws IOException {
            var info = scan(SimpleClass.class);
            var methodNames = info.methods().stream().map(MethodInfo::name).toList();
            assertTrue(methodNames.contains("getName"));
            assertTrue(methodNames.contains("setValue"));
        }

        @Test
        @DisplayName("detects the field access flags")
        void shouldDetectFieldAccessFlags() throws IOException {
            var info = scan(SimpleClass.class);
            var nameField = info.fields().stream().filter(f -> f.name().equals("name")).findFirst().orElseThrow();
            assertTrue(nameField.isPrivate());
            assertFalse(nameField.isPublic());

            var valueField = info.fields().stream().filter(f -> f.name().equals("value")).findFirst().orElseThrow();
            assertTrue(valueField.isPublic());
        }

        @Test
        @DisplayName("detects the CLASS kind")
        void shouldDetectClassKind() throws IOException {
            var info = scan(SimpleClass.class);
            assertEquals(ClassKind.CLASS, info.kind());
            assertFalse(info.isInterface());
            assertFalse(info.isEnum());
            assertFalse(info.isRecord());
        }

        @Test
        @DisplayName("detects the constructor")
        void shouldDetectConstructor() throws IOException {
            var info = scan(SimpleClass.class);
            assertTrue(info.methods().stream().anyMatch(MethodInfo::isConstructor));
        }
    }

    @Nested
    @DisplayName("scan of an annotated class")
    class ScanAnnotatedClass {

        @Test
        @DisplayName("extracts the class annotations")
        void shouldExtractClassAnnotations() throws IOException {
            var info = scan(AnnotatedClass.class);
            assertTrue(info.hasAnnotation(DotName.of("java.lang.Deprecated")));
        }

        @Test
        @DisplayName("extracts the method annotations")
        void shouldExtractMethodAnnotations() throws IOException {
            var info = scan(AnnotatedClass.class);
            var method = info.methods().stream()
                    .filter(m -> m.name().equals("deprecatedMethod")).findFirst().orElseThrow();
            assertTrue(method.annotations().stream()
                    .anyMatch(a -> a.name().equals(DotName.of("java.lang.Deprecated"))));
        }

        @Test
        @DisplayName("extracts a custom annotation with its values")
        void shouldExtractCustomAnnotationWithValues() throws IOException {
            var info = scan(CustomAnnotatedClass.class);
            var annotation = info.annotation(DotName.of(CustomAnnotation.class.getName()));
            assertTrue(annotation.isPresent());

            var ann = annotation.get();
            assertEquals(new AnnotationValue.StringVal("test"), ann.member("value"));
            assertEquals(new AnnotationValue.IntVal(42), ann.member("count"));
        }
    }

    @Nested
    @DisplayName("scan of an interface")
    class ScanInterface {

        @Test
        @DisplayName("detects the INTERFACE kind")
        void shouldDetectInterfaceKind() throws IOException {
            var info = scan(SimpleInterface.class);
            assertEquals(ClassKind.INTERFACE, info.kind());
            assertTrue(info.isInterface());
        }

        @Test
        @DisplayName("extracts the abstract methods")
        void shouldExtractAbstractMethods() throws IOException {
            var info = scan(SimpleInterface.class);
            var doSomething = info.methods().stream()
                    .filter(m -> m.name().equals("doSomething")).findFirst().orElseThrow();
            assertTrue(doSomething.isAbstract());
        }

        @Test
        @DisplayName("extracts the default methods")
        void shouldExtractDefaultMethods() throws IOException {
            var info = scan(SimpleInterface.class);
            var defaultMethod = info.methods().stream()
                    .filter(m -> m.name().equals("defaultMethod")).findFirst().orElseThrow();
            assertFalse(defaultMethod.isAbstract());
            assertFalse(defaultMethod.isStatic());
        }
    }

    @Nested
    @DisplayName("scan of a class implementing an interface")
    class ScanImplementingClass {

        @Test
        @DisplayName("extracts the superclass")
        void shouldExtractSuperclass() throws IOException {
            var info = scan(ImplementingClass.class);
            assertEquals(DotName.of(SimpleClass.class.getName()), info.superName());
        }

        @Test
        @DisplayName("extracts the implemented interfaces")
        void shouldExtractInterfaces() throws IOException {
            var info = scan(ImplementingClass.class);
            assertTrue(info.interfaces().contains(DotName.of(SimpleInterface.class.getName())));
        }
    }

    @Nested
    @DisplayName("scan of an enum")
    class ScanEnum {

        @Test
        @DisplayName("detects the ENUM kind")
        void shouldDetectEnumKind() throws IOException {
            var info = scan(SimpleEnum.class);
            assertEquals(ClassKind.ENUM, info.kind());
            assertTrue(info.isEnum());
        }
    }

    @Nested
    @DisplayName("scan of a record")
    class ScanRecord {

        @Test
        @DisplayName("detects the RECORD kind")
        void shouldDetectRecordKind() throws IOException {
            var info = scan(SimpleRecord.class);
            assertEquals(ClassKind.RECORD, info.kind());
            assertTrue(info.isRecord());
        }

        @Test
        @DisplayName("extracts the record components as fields")
        void shouldExtractRecordComponents() throws IOException {
            var info = scan(SimpleRecord.class);
            var fieldNames = info.fields().stream().map(FieldInfo::name).toList();
            assertTrue(fieldNames.contains("name"));
            assertTrue(fieldNames.contains("value"));
        }
    }

    @Nested
    @DisplayName("scan of an annotation")
    class ScanAnnotation {

        @Test
        @DisplayName("detects the ANNOTATION kind")
        void shouldDetectAnnotationKind() throws IOException {
            var info = scan(CustomAnnotation.class);
            assertEquals(ClassKind.ANNOTATION, info.kind());
            assertTrue(info.isAnnotation());
        }
    }

    @Nested
    @DisplayName("scan of an abstract class")
    class ScanAbstractClass {

        @Test
        @DisplayName("detects abstract and final methods")
        void shouldDetectAbstractAndFinalMethods() throws IOException {
            var info = scan(AbstractClass.class);
            assertTrue(info.isAbstract());

            var abstractMethod = info.methods().stream()
                    .filter(m -> m.name().equals("abstractMethod")).findFirst().orElseThrow();
            assertTrue(abstractMethod.isAbstract());

            var finalMethod = info.methods().stream()
                    .filter(m -> m.name().equals("finalMethod")).findFirst().orElseThrow();
            assertFalse(finalMethod.isAbstract());
        }
    }

    @Nested
    @DisplayName("scan of return types and parameters")
    class ScanMethodSignatures {

        @Test
        @DisplayName("extracts the void return type")
        void shouldExtractVoidReturn() throws IOException {
            var info = scan(SimpleClass.class);
            var setter = info.methods().stream()
                    .filter(m -> m.name().equals("setValue")).findFirst().orElseThrow();
            assertInstanceOf(VoidType.class, setter.returnType());
        }

        @Test
        @DisplayName("extracts the String return type")
        void shouldExtractStringReturn() throws IOException {
            var info = scan(SimpleClass.class);
            var getter = info.methods().stream()
                    .filter(m -> m.name().equals("getName")).findFirst().orElseThrow();
            assertInstanceOf(ClassType.class, getter.returnType());
            assertEquals(DotName.of("java.lang.String"), ((ClassType) getter.returnType()).name());
        }

        @Test
        @DisplayName("extracts the method parameters")
        void shouldExtractMethodParameters() throws IOException {
            var info = scan(SimpleClass.class);
            var setter = info.methods().stream()
                    .filter(m -> m.name().equals("setValue")).findFirst().orElseThrow();
            assertEquals(1, setter.parameters().size());
            assertInstanceOf(PrimitiveType.class, setter.parameters().getFirst().type());
        }
    }
}
