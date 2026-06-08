/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("java:S2187") // Test scaffold — helpers and data classes for future tests
@DisplayName("BeanDiscovery - CDI bean discovery")
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
    @DisplayName("managed bean discovery")
    class ManagedBeans {

        @Test
        @DisplayName("discovers an @ApplicationScoped bean")
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
        @DisplayName("discovers a @Dependent bean")
        void shouldDiscoverDependentBean() {
            var builder = new IndexBuilder();
            builder.add(makeAnnotatedClass("com.example.MyRepo", "jakarta.enterprise.context.Dependent"));
            var discovery = new BeanDiscovery(builder.build());

            var beans = discovery.discoverBeans();
            assertEquals(1, beans.size());
            assertEquals(ScopeInfo.DEPENDENT.annotationName(), beans.getFirst().scope().annotationName());
        }

        @Test
        @DisplayName("ignores @Vetoed classes")
        void shouldIgnoreVetoedClasses() {
            var builder = new IndexBuilder();
            builder.add(makeVetoedClass("com.example.VetoedService"));
            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.discoverBeans().isEmpty());
        }

        @Test
        @DisplayName("ignores abstract classes")
        void shouldIgnoreAbstractClasses() {
            var builder = new IndexBuilder();
            builder.add(makeAbstractClass("com.example.AbstractService"));
            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.discoverBeans().isEmpty());
        }

        @Test
        @DisplayName("ignores interfaces")
        void shouldIgnoreInterfaces() {
            var builder = new IndexBuilder();
            builder.add(makeInterface("com.example.MyInterface"));
            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.discoverBeans().isEmpty());
        }

        @Test
        @DisplayName("ignores classes without a bean-defining annotation")
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
        @DisplayName("adds @Default and @Any implicitly")
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
    @DisplayName("bean types")
    class BeanTypes {

        @Test
        @DisplayName("includes the class and Object in the types")
        void shouldIncludeClassAndObject() {
            var builder = new IndexBuilder();
            builder.add(makeAnnotatedClass("com.example.MyService", "jakarta.enterprise.context.ApplicationScoped"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertTrue(bean.types().contains(new TypeInfo.ClassType(DotName.of("com.example.MyService"))));
            assertTrue(bean.types().contains(new TypeInfo.ClassType(DotName.of("java.lang.Object"))));
        }

        @Test
        @DisplayName("includes interfaces in the types")
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
        @DisplayName("discovers @Inject fields")
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
    @DisplayName("stereotypes")
    class Stereotypes {

        /**
         * Creates a stereotype annotation class in the index.
         * A stereotype is an annotation meta-annotated with @Stereotype.
         */
        static ClassInfo makeStereotypeAnnotation(String name, List<AnnotationInfo> metaAnnotations) {
            return new ClassInfo(
                    DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                    0x2601, // PUBLIC + INTERFACE + ANNOTATION + ABSTRACT
                    List.of(), List.of(),
                    metaAnnotations,
                    ClassKind.ANNOTATION
            );
        }

        static ClassInfo makeClassWithAnnotation(String name, String annotationName) {
            return new ClassInfo(
                    DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                    0x0001,
                    List.of(),
                    List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                    List.of(new AnnotationInfo(DotName.of(annotationName), Map.of())),
                    ClassKind.CLASS
            );
        }

        static ClassInfo makeClassWithAnnotations(String name, List<AnnotationInfo> annotations) {
            return new ClassInfo(
                    DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                    0x0001,
                    List.of(),
                    List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                    annotations,
                    ClassKind.CLASS
            );
        }

        @Test
        @DisplayName("a stereotype is a bean-defining annotation")
        void stereotypeIsBeanDefiningAnnotation() {
            var builder = new IndexBuilder();
            // Register the @Service stereotype annotation
            builder.add(makeStereotypeAnnotation("com.example.Service", List.of(
                    new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Stereotype"), Map.of()),
                    new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())
            )));
            // A class annotated only with @Service (no explicit scope)
            builder.add(makeClassWithAnnotation("com.example.MyService", "com.example.Service"));
            var discovery = new BeanDiscovery(builder.build());

            var beans = discovery.discoverBeans();
            assertEquals(1, beans.size());
            assertEquals(DotName.of("com.example.MyService"), beans.getFirst().beanClass());
        }

        @Test
        @DisplayName("the stereotype scope is inherited when the bean has no explicit scope")
        void stereotypeScopeIsInherited() {
            var builder = new IndexBuilder();
            builder.add(makeStereotypeAnnotation("com.example.Service", List.of(
                    new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Stereotype"), Map.of()),
                    new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())
            )));
            builder.add(makeClassWithAnnotation("com.example.MyService", "com.example.Service"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertEquals(ScopeInfo.APPLICATION, bean.scope());
        }

        @Test
        @DisplayName("an explicit scope on the bean takes precedence over the stereotype")
        void explicitScopeOverridesStereotype() {
            var builder = new IndexBuilder();
            builder.add(makeStereotypeAnnotation("com.example.Service", List.of(
                    new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Stereotype"), Map.of()),
                    new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())
            )));
            builder.add(makeClassWithAnnotations("com.example.MyService", List.of(
                    new AnnotationInfo(DotName.of("com.example.Service"), Map.of()),
                    new AnnotationInfo(DotName.of("jakarta.enterprise.context.RequestScoped"), Map.of())
            )));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertEquals(ScopeInfo.REQUEST, bean.scope());
        }

        @Test
        @DisplayName("@Named on the stereotype makes the bean named")
        void stereotypeNamedIsInherited() {
            var builder = new IndexBuilder();
            builder.add(makeStereotypeAnnotation("com.example.Service", List.of(
                    new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Stereotype"), Map.of()),
                    new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of()),
                    new AnnotationInfo(DotName.of("jakarta.inject.Named"), Map.of())
            )));
            builder.add(makeClassWithAnnotation("com.example.MyService", "com.example.Service"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertEquals("myService", bean.name());
        }

        @Test
        @DisplayName("the stereotype qualifiers are inherited by the bean")
        void stereotypeQualifiersAreInherited() {
            var builder = new IndexBuilder();
            // Create a custom qualifier annotation
            builder.add(new ClassInfo(
                    DotName.of("com.example.MyQualifier"), DotName.of("java.lang.Object"), List.of(),
                    0x2601,
                    List.of(), List.of(),
                    List.of(new AnnotationInfo(DotName.of("jakarta.inject.Qualifier"), Map.of())),
                    ClassKind.ANNOTATION
            ));
            builder.add(makeStereotypeAnnotation("com.example.Service", List.of(
                    new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Stereotype"), Map.of()),
                    new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of()),
                    new AnnotationInfo(DotName.of("com.example.MyQualifier"), Map.of())
            )));
            builder.add(makeClassWithAnnotation("com.example.MyService", "com.example.Service"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertTrue(bean.qualifiers().stream()
                    .anyMatch(q -> q.annotationName().equals(DotName.of("com.example.MyQualifier"))));
        }

        @Test
        @DisplayName("a bean with no scope and a stereotype with no scope has the Dependent scope")
        void noScopeDefaultsToDependent() {
            var builder = new IndexBuilder();
            builder.add(makeStereotypeAnnotation("com.example.MyStereotype", List.of(
                    new AnnotationInfo(DotName.of("jakarta.enterprise.inject.Stereotype"), Map.of())
            )));
            builder.add(makeClassWithAnnotation("com.example.MyService", "com.example.MyStereotype"));
            var discovery = new BeanDiscovery(builder.build());

            var bean = discovery.discoverBeans().getFirst();
            assertEquals(ScopeInfo.DEPENDENT, bean.scope());
        }
    }

    @Nested
    @DisplayName("producer methods")
    class ProducerMethods {

        @Test
        @DisplayName("discovers producer methods")
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

    @Nested
    @DisplayName("scanned-classes filter (non-bean archive + BCE ScannedClasses)")
    class ScannedClassesFilter {

        // VAU-DISC-001 — Regression. In annotated discovery mode (a non-bean archive, i.e.
        // beanArchive=false), a Build Compatible Extension that adds classes via
        // ScannedClasses.add() must NOT suppress beans that carry a bean-defining annotation.
        // VaubanContainerBuilder restricts discovery to the BCE-scanned set when !isBeanArchive;
        // that restriction used to swallow legitimately-annotated beans. Surfaced by the Mansart
        // Jakarta Data TCK harness: its MansartDataExtension scans the runtime producer, which
        // turned the scanned set into an exclusive whitelist and silently dropped the test's
        // @Singleton @Produces DataSource — leaving every @Repository's RepositoryRuntime
        // unsatisfied ("No @Default DataSource bean found"), so all 73 EntityTests errored.
        @Test
        @DisplayName("an annotated @Produces bean survives an active scanned-classes filter")
        void annotatedProducerSurvivesScannedClassesFilter() {
            var builder = new IndexBuilder();
            // The class the BCE forces in via ScannedClasses.add(...) (mimics MansartRuntimeProducer).
            builder.add(makeAnnotatedClass("com.example.ScannedRuntime", "jakarta.inject.Singleton"));
            // A producer class NOT in the scanned set but carrying a bean-defining annotation —
            // the test-supplied @Produces DataSource (mimics the TCK's H2DataSourceProducer).
            builder.add(makeClassWithProducerMethod(
                    "com.example.DataSourceProducer", "dataSource", "com.example.DataSource"));
            var discovery = new BeanDiscovery(builder.build());

            // Reproduce VaubanContainerBuilder's wiring when !isBeanArchive and the BCE scanned classes.
            var scanned = java.util.Set.of(DotName.of("com.example.ScannedRuntime"));
            discovery.setForcedBeanClasses(scanned);
            discovery.setScannedClassesFilter(scanned);

            var beans = discovery.discoverBeans();
            var producer = beans.stream()
                    .filter(b -> b.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD)
                    .filter(b -> b.types().contains(new TypeInfo.ClassType(DotName.of("com.example.DataSource"))))
                    .findFirst();
            assertTrue(producer.isPresent(),
                    "annotated @Produces bean must survive the scanned-classes filter; discovered=" + beans);
        }
    }
}
