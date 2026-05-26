package io.vidocq.vauban.core.bean.resolution;

import io.vidocq.vauban.core.bean.model.*;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BeanResolver - CDI 4.1 typesafe resolution")
class BeanResolverTest {

    private AssignabilityRules assignability;

    static BeanDescriptor makeBean(String name, Set<TypeInfo> types, Set<QualifierInstance> qualifiers, ScopeInfo scope) {
        return new BeanDescriptor(
                BeanId.of(DotName.of(name)), DotName.of(name), BeanDescriptor.BeanKind.MANAGED,
                types, qualifiers, scope, false, 0, List.of(), null);
    }

    static BeanDescriptor makeAlternative(String name, Set<TypeInfo> types, int priority) {
        return new BeanDescriptor(
                BeanId.of(DotName.of(name)), DotName.of(name), BeanDescriptor.BeanKind.MANAGED,
                types, Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                ScopeInfo.APPLICATION, true, priority, List.of(), null);
    }

    @BeforeEach
    void setUp() {
        var builder = new IndexBuilder();
        builder.add(new ClassInfo(DotName.of("java.lang.Object"), null, List.of(), 0x0001,
                List.of(), List.of(), List.of(), ClassKind.CLASS));
        assignability = new AssignabilityRules(builder.build());
    }

    @Nested
    @DisplayName("simple resolution")
    class SimpleResolution {

        @Test
        @DisplayName("resolves a unique bean by type")
        void shouldResolveUniqueBean() {
            var serviceType = new TypeInfo.ClassType(DotName.of("com.example.MyService"));
            var bean = makeBean("com.example.MyService",
                    Set.of(serviceType, new TypeInfo.ClassType(DotName.of("java.lang.Object"))),
                    Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                    ScopeInfo.APPLICATION);

            var resolver = new BeanResolver(List.of(bean), assignability);
            var result = resolver.resolve(serviceType, Set.of(QualifierInstance.DEFAULT));
            assertEquals(1, result.size());
            assertEquals(bean, result.getFirst());
        }

        @Test
        @DisplayName("returns empty when no bean matches")
        void shouldReturnEmptyWhenNoMatch() {
            var resolver = new BeanResolver(List.of(), assignability);
            var result = resolver.resolve(
                    new TypeInfo.ClassType(DotName.of("com.example.Unknown")),
                    Set.of(QualifierInstance.DEFAULT));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("detects an ambiguous dependency")
        void shouldDetectAmbiguousDependency() {
            var serviceType = new TypeInfo.ClassType(DotName.of("com.example.MyService"));
            var bean1 = makeBean("com.example.Impl1",
                    Set.of(serviceType), Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.APPLICATION);
            var bean2 = makeBean("com.example.Impl2",
                    Set.of(serviceType), Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.APPLICATION);

            var resolver = new BeanResolver(List.of(bean1, bean2), assignability);
            var result = resolver.resolve(serviceType, Set.of(QualifierInstance.DEFAULT));
            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("qualifiers")
    class QualifierResolution {

        @Test
        @DisplayName("filters by qualifier")
        void shouldFilterByQualifier() {
            var serviceType = new TypeInfo.ClassType(DotName.of("com.example.MyService"));
            var customQualifier = new QualifierInstance(DotName.of("com.example.Special"), Map.of());

            var bean1 = makeBean("com.example.Impl1",
                    Set.of(serviceType),
                    Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                    ScopeInfo.APPLICATION);
            var bean2 = makeBean("com.example.Impl2",
                    Set.of(serviceType),
                    Set.of(customQualifier, QualifierInstance.ANY),
                    ScopeInfo.APPLICATION);

            var resolver = new BeanResolver(List.of(bean1, bean2), assignability);

            // Resolve with @Default -> only bean1
            var defaultResult = resolver.resolve(serviceType, Set.of(QualifierInstance.DEFAULT));
            assertEquals(1, defaultResult.size());
            assertEquals("com.example.Impl1", defaultResult.getFirst().beanClass().value());

            // Resolve with custom qualifier -> only bean2
            var customResult = resolver.resolve(serviceType, Set.of(customQualifier));
            assertEquals(1, customResult.size());
            assertEquals("com.example.Impl2", customResult.getFirst().beanClass().value());

            // Resolve with @Any -> both
            var anyResult = resolver.resolve(serviceType, Set.of(QualifierInstance.ANY));
            assertEquals(2, anyResult.size());
        }
    }

    @Nested
    @DisplayName("alternatives")
    class Alternatives {

        @Test
        @DisplayName("selects the alternative with the highest priority")
        void shouldSelectHighestPriorityAlternative() {
            var serviceType = new TypeInfo.ClassType(DotName.of("com.example.MyService"));
            var normal = makeBean("com.example.DefaultImpl",
                    Set.of(serviceType), Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.APPLICATION);
            var alt1 = makeAlternative("com.example.Alt1", Set.of(serviceType), 100);
            var alt2 = makeAlternative("com.example.Alt2", Set.of(serviceType), 200);

            var resolver = new BeanResolver(List.of(normal, alt1, alt2), assignability);
            var result = resolver.resolve(serviceType, Set.of(QualifierInstance.DEFAULT));
            assertEquals(1, result.size());
            assertEquals("com.example.Alt2", result.getFirst().beanClass().value());
        }
    }

    @Nested
    @DisplayName("injection point resolution")
    class InjectionPointResolution {

        @Test
        @DisplayName("resolves a satisfied injection point")
        void shouldResolveInjectionPoint() {
            var serviceType = new TypeInfo.ClassType(DotName.of("com.example.MyService"));
            var bean = makeBean("com.example.MyService",
                    Set.of(serviceType), Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.APPLICATION);

            var resolver = new BeanResolver(List.of(bean), assignability);
            var ip = new InjectionPointInfo(serviceType, Set.of(QualifierInstance.DEFAULT),
                    InjectionPointInfo.InjectionKind.FIELD, "test");
            var result = resolver.resolveInjectionPoint(ip);

            assertEquals(BeanResolver.ResolutionResult.Status.RESOLVED, result.status());
            assertTrue(result.isResolved());
        }

        @Test
        @DisplayName("detects an unsatisfied injection point")
        void shouldDetectUnsatisfied() {
            var resolver = new BeanResolver(List.of(), assignability);
            var ip = new InjectionPointInfo(
                    new TypeInfo.ClassType(DotName.of("com.example.Unknown")),
                    Set.of(QualifierInstance.DEFAULT),
                    InjectionPointInfo.InjectionKind.FIELD, "test");
            var result = resolver.resolveInjectionPoint(ip);

            assertEquals(BeanResolver.ResolutionResult.Status.UNSATISFIED, result.status());
        }
    }
}
