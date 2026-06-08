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
package io.vidocq.vauban.core.bean.validation;

import io.vidocq.vauban.core.bean.model.*;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeploymentValidator - CDI deployment validation")
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
    @DisplayName("valid deployment with no error")
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
    @DisplayName("detects an unsatisfied dependency")
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
    @DisplayName("detects an ambiguous dependency")
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
