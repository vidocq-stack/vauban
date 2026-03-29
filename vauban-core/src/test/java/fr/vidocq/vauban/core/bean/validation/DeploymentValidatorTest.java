package fr.vidocq.vauban.core.bean.validation;

import fr.vidocq.vauban.core.bean.model.*;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeploymentValidator - validation du deploiement CDI")
class DeploymentValidatorTest {

    static BeanDescriptor makeBean(String name, List<InjectionPointInfo> ips) {
        return new BeanDescriptor(
                BeanId.of(DotName.of(name)), DotName.of(name), BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.ClassType(DotName.of(name)),
                        new TypeInfo.ClassType(DotName.of("java.lang.Object"))),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                ScopeInfo.APPLICATION, false, 0, ips, null);
    }

    private AssignabilityRules makeRules() {
        var builder = new IndexBuilder();
        builder.add(new ClassInfo(DotName.of("java.lang.Object"), null, List.of(),
                0x0001, List.of(), List.of(), List.of(), ClassKind.CLASS));
        return new AssignabilityRules(builder.build());
    }

    @Test
    @DisplayName("deploiement valide sans erreur")
    void shouldPassValidDeployment() {
        var serviceBean = makeBean("com.example.MyService", List.of());
        var controllerBean = makeBean("com.example.MyController", List.of(
                new InjectionPointInfo(
                        new TypeInfo.ClassType(DotName.of("com.example.MyService")),
                        Set.of(QualifierInstance.DEFAULT),
                        InjectionPointInfo.InjectionKind.FIELD,
                        "field MyController.service")));

        var beans = List.of(serviceBean, controllerBean);
        var resolver = new BeanResolver(beans, makeRules());
        var validator = new DeploymentValidator(beans, resolver);

        var errors = validator.validate();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    @DisplayName("detecte une dependance non satisfaite")
    void shouldDetectUnsatisfiedDependency() {
        var controllerBean = makeBean("com.example.MyController", List.of(
                new InjectionPointInfo(
                        new TypeInfo.ClassType(DotName.of("com.example.MissingService")),
                        Set.of(QualifierInstance.DEFAULT),
                        InjectionPointInfo.InjectionKind.FIELD,
                        "field MyController.service")));

        var beans = List.of(controllerBean);
        var resolver = new BeanResolver(beans, makeRules());
        var validator = new DeploymentValidator(beans, resolver);

        var errors = validator.validate();
        assertEquals(1, errors.size());
        assertEquals(DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY, errors.getFirst().kind());
    }

    @Test
    @DisplayName("detecte une dependance ambigue")
    void shouldDetectAmbiguousDependency() {
        var serviceType = new TypeInfo.ClassType(DotName.of("com.example.MyService"));
        var impl1 = new BeanDescriptor(
                BeanId.of(DotName.of("com.example.Impl1")), DotName.of("com.example.Impl1"),
                BeanDescriptor.BeanKind.MANAGED,
                Set.of(serviceType, new TypeInfo.ClassType(DotName.of("java.lang.Object"))),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                ScopeInfo.APPLICATION, false, 0, List.of(), null);
        var impl2 = new BeanDescriptor(
                BeanId.of(DotName.of("com.example.Impl2")), DotName.of("com.example.Impl2"),
                BeanDescriptor.BeanKind.MANAGED,
                Set.of(serviceType, new TypeInfo.ClassType(DotName.of("java.lang.Object"))),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                ScopeInfo.APPLICATION, false, 0, List.of(), null);

        var controller = makeBean("com.example.MyController", List.of(
                new InjectionPointInfo(serviceType, Set.of(QualifierInstance.DEFAULT),
                        InjectionPointInfo.InjectionKind.FIELD, "field MyController.service")));

        var beans = List.of(impl1, impl2, controller);
        var resolver = new BeanResolver(beans, makeRules());
        var validator = new DeploymentValidator(beans, resolver);

        var errors = validator.validate();
        assertTrue(errors.stream().anyMatch(e -> e.kind() == DeploymentValidator.ValidationError.Kind.AMBIGUOUS_DEPENDENCY));
    }
}
