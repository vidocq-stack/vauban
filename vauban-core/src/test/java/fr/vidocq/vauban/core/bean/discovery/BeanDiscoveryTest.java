package fr.vidocq.vauban.core.bean.discovery;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.bean.model.ScopeInfo;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BeanDiscovery - decouverte des beans CDI")
class BeanDiscoveryTest {

    static ClassInfo scanClass(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
            return ClassFileScanner.scan(is.readAllBytes());
        }
    }

    // -- Test classes (must have runtime-retained annotations for scanner) --

    static ClassInfo makeAnnotatedClass(String name, String scope) {
        return new ClassInfo(
                DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                0x0001,
                List.of(),
                List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                List.of(new AnnotationInfo(DotName.of(scope), Map.of())),
                ClassKind.CLASS
        );
    }

    static ClassInfo makeVetoedClass(String name) {
        return new ClassInfo(
                DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                0x0001,
                List.of(),
                List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                List.of(
                        new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of()),
                        new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Vetoed"), Map.of())
                ),
                ClassKind.CLASS
        );
    }

    static ClassInfo makeAbstractClass(String name) {
        return new ClassInfo(
                DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                0x0401, // PUBLIC + ABSTRACT
                List.of(),
                List.of(),
                List.of(new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())),
                ClassKind.CLASS
        );
    }

    static ClassInfo makeInterface(String name) {
        return new ClassInfo(
                DotName.of(name), null, List.of(),
                0x0601,
                List.of(),
                List.of(),
                List.of(),
                ClassKind.INTERFACE
        );
    }

    static ClassInfo makeClassWithInjectField(String name, String fieldType) {
        return new ClassInfo(
                DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                0x0001,
                List.of(new FieldInfo("service", new TypeInfo.ClassType(DotName.of(fieldType)), 0x0002,
                        List.of(new AnnotationInfo(DotName.of("jakarta.inject.Inject"), Map.of())))),
                List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                List.of(new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())),
                ClassKind.CLASS
        );
    }

    static ClassInfo makeClassWithProducerMethod(String className, String methodName, String returnType) {
        return new ClassInfo(
                DotName.of(className), DotName.of("java.lang.Object"), List.of(),
                0x0001,
                List.of(),
                List.of(
                        new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of()),
                        new MethodInfo(methodName, new TypeInfo.ClassType(DotName.of(returnType)), List.of(), List.of(), 0x0001,
                                List.of(
                                        new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Produces"), Map.of()),
                                        new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())
                                ))
                ),
                List.of(new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())),
                ClassKind.CLASS
        );
    }

    @Nested
    @DisplayName("decouverte de managed beans")
    class ManagedBeans {

        @Test
        @DisplayName("decouvre un bean @ApplicationScoped")
        void shouldDiscoverApplicationScopedBean() {
            var builder = new IndexBuilder();
            builder.add(makeAnnotatedClass("com.example.MyService", "jakarta.enterprise.context.ApplicationScoped"));
            var discovery = new BeanDiscovery(builder.build());

            var beans = discovery.discoverBeans();
            assertEquals(1, beans.size());
            assertEquals(DotName.of("com.example.MyService"), beans.getFirst().beanClass());
            assertEquals(BeanDescriptor.BeanKind.MANAGED, beans.getFirst().kind());
        }

        @Test
        @DisplayName("decouvre un bean @Dependent")
        void shouldDiscoverDependentBean() {
            var builder = new IndexBuilder();
            builder.add(makeAnnotatedClass("com.example.MyRepo", "jakarta.enterprise.context.Dependent"));
            var discovery = new BeanDiscovery(builder.build());

            var beans = discovery.discoverBeans();
            assertEquals(1, beans.size());
            assertEquals(ScopeInfo.DEPENDENT.annotationName(), beans.getFirst().scope().annotationName());
        }

        @Test
        @DisplayName("ignore les classes @Vetoed")
        void shouldIgnoreVetoedClasses() {
            var builder = new IndexBuilder();
            builder.add(makeVetoedClass("com.example.VetoedService"));
            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.discoverBeans().isEmpty());
        }

        @Test
        @DisplayName("ignore les classes abstraites")
        void shouldIgnoreAbstractClasses() {
            var builder = new IndexBuilder();
            builder.add(makeAbstractClass("com.example.AbstractService"));
            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.discoverBeans().isEmpty());
        }

        @Test
        @DisplayName("ignore les interfaces")
        void shouldIgnoreInterfaces() {
            var builder = new IndexBuilder();
            builder.add(makeInterface("com.example.MyInterface"));
            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.discoverBeans().isEmpty());
        }

        @Test
        @DisplayName("ignore les classes sans annotation bean-defining")
        void shouldIgnoreClassesWithoutBeanDefiningAnnotation() {
            var builder = new IndexBuilder();
            builder.add(new ClassInfo(
                    DotName.of("com.example.PlainClass"), DotName.of("java.lang.Object"), List.of(),
                    0x0001, List.of(),
                    List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                    List.of(), ClassKind.CLASS));
            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.discoverBeans().isEmpty());
        }
    }

    @Nested
    @DisplayName("qualifiers")
    class Qualifiers {

        @Test
        @DisplayName("ajoute @Default et @Any implicitement")
        void shouldAddDefaultAndAny() {
            var builder = new IndexBuilder();
            builder.add(makeAnnotatedClass("com.example.MyService", "jakarta.enterprise.context.ApplicationScoped"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertTrue(bean.qualifiers().stream().anyMatch(QualifierInstance::isDefault));
            assertTrue(bean.qualifiers().stream().anyMatch(QualifierInstance::isAny));
        }
    }

    @Nested
    @DisplayName("types de beans")
    class BeanTypes {

        @Test
        @DisplayName("inclut la classe et Object dans les types")
        void shouldIncludeClassAndObject() {
            var builder = new IndexBuilder();
            builder.add(makeAnnotatedClass("com.example.MyService", "jakarta.enterprise.context.ApplicationScoped"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertTrue(bean.types().contains(new TypeInfo.ClassType(DotName.of("com.example.MyService"))));
            assertTrue(bean.types().contains(new TypeInfo.ClassType(DotName.of("java.lang.Object"))));
        }

        @Test
        @DisplayName("inclut les interfaces dans les types")
        void shouldIncludeInterfaces() {
            var builder = new IndexBuilder();
            builder.add(new ClassInfo(
                    DotName.of("com.example.MyImpl"), DotName.of("java.lang.Object"),
                    List.of(DotName.of("com.example.MyInterface")),
                    0x0001, List.of(),
                    List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                    List.of(new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())),
                    ClassKind.CLASS));
            builder.add(makeInterface("com.example.MyInterface"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertTrue(bean.types().contains(new TypeInfo.ClassType(DotName.of("com.example.MyInterface"))));
        }
    }

    @Nested
    @DisplayName("injection points")
    class InjectionPoints {

        @Test
        @DisplayName("decouvre les champs @Inject")
        void shouldDiscoverInjectFields() {
            var builder = new IndexBuilder();
            builder.add(makeClassWithInjectField("com.example.MyController", "com.example.MyService"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertEquals(1, bean.injectionPoints().size());
            var ip = bean.injectionPoints().getFirst();
            assertInstanceOf(TypeInfo.ClassType.class, ip.requiredType());
        }
    }

    @Nested
    @DisplayName("producer methods")
    class ProducerMethods {

        @Test
        @DisplayName("decouvre les producer methods")
        void shouldDiscoverProducerMethods() {
            var builder = new IndexBuilder();
            builder.add(makeClassWithProducerMethod("com.example.Config", "createService", "com.example.MyService"));
            var discovery = new BeanDiscovery(builder.build());

            var beans = discovery.discoverBeans();
            assertEquals(2, beans.size()); // 1 managed + 1 producer method

            var producer = beans.stream()
                    .filter(b -> b.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD)
                    .findFirst().orElseThrow();
            assertTrue(producer.types().contains(new TypeInfo.ClassType(DotName.of("com.example.MyService"))));
        }
    }
}
