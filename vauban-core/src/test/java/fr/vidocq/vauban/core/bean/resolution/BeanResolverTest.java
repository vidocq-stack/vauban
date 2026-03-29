package fr.vidocq.vauban.core.bean.resolution;

import fr.vidocq.vauban.core.bean.model.*;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BeanResolver - resolution typesafe CDI 4.1")
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
    @DisplayName("resolution simple")
    class SimpleResolution {

        @Test
        @DisplayName("resout un bean unique par type")
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
        @DisplayName("retourne vide quand aucun bean ne correspond")
        void shouldReturnEmptyWhenNoMatch() {
            var resolver = new BeanResolver(List.of(), assignability);
            var result = resolver.resolve(
                    new TypeInfo.ClassType(DotName.of("com.example.Unknown")),
                    Set.of(QualifierInstance.DEFAULT));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("detecte une dependance ambigue")
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
        @DisplayName("filtre par qualifier")
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
        @DisplayName("selectionne l'alternative avec la plus haute priorite")
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
        @DisplayName("resout un injection point satisfait")
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
        @DisplayName("detecte un injection point non satisfait")
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
