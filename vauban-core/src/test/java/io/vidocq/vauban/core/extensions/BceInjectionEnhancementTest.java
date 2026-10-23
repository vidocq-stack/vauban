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
 * It is also made available under the European Union Public Licence v. 1.2
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BceInjectionEnhancementTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Chosen {
        String value();
    }

    @Dependent
    @Chosen("selected")
    public static class Dependency {}

    public static class Target {
        public Dependency field;
        public Dependency constructor;
        public Dependency initialized;
        public boolean unrelatedOverloadCalled;

        @Inject
        public Target(Dependency constructor) {
            this.constructor = constructor;
        }

        public void initialize(Dependency dependency) {
            initialized = dependency;
        }

        @SuppressWarnings("java:S1172") // the parameter is the signature under test
        public void initialize(String ignored) {
            unrelatedOverloadCalled = true;
        }
    }

    public static class InjectionBce implements BuildCompatibleExtension {
        @Enhancement(types = Target.class)
        public void addInjection(ClassConfig config) {
            config.addAnnotation(Dependent.class);
            config.fields().stream()
                    .filter(field -> field.info().name().equals("field"))
                    .forEach(field -> {
                        field.addAnnotation(Inject.class);
                        field.addAnnotation(AnnotationBuilder.of(Chosen.class).member("value", "selected").build());
                    });
            config.methods().stream()
                    .filter(method -> method.info().name().equals("initialize")
                            && method.info().parameters().getFirst().type().isClass()
                            && method.info().parameters().getFirst().type().asClass().declaration().name()
                                    .equals(Dependency.class.getName()))
                    .forEach(method -> {
                        method.addAnnotation(Inject.class);
                        method.parameters().getFirst().addAnnotation(
                                AnnotationBuilder.of(Chosen.class).member("value", "selected").build());
                    });
            config.constructors()
                    .forEach(constructor -> constructor.parameters().getFirst().addAnnotation(
                            AnnotationBuilder.of(Chosen.class).member("value", "selected").build()));
        }
    }

    /**
     * A bean whose no-arg constructor is the bean constructor until an enhancement marks the {@code Dependency} one
     * {@code @Inject}; the {@code String} overload takes as many parameters and must not be chosen instead.
     */
    @Dependent
    public static class TwoConstructors {
        public final Dependency dependency;

        public TwoConstructors() {
            this.dependency = null;
        }

        @SuppressWarnings("java:S1172") // the overload is the signature under test
        public TwoConstructors(String ignored) {
            this.dependency = null;
        }

        public TwoConstructors(Dependency dependency) {
            this.dependency = dependency;
        }
    }

    public static class ConstructorInjectionBce implements BuildCompatibleExtension {
        @Enhancement(types = TwoConstructors.class)
        public void injectConstructor(ClassConfig config) {
            config.constructors().stream()
                    .filter(constructor -> constructor.info().parameters().size() == 1
                            && constructor.info().parameters().getFirst().type().isClass()
                            && constructor.info().parameters().getFirst().type().asClass().declaration().name()
                                    .equals(Dependency.class.getName()))
                    .forEach(constructor -> {
                        constructor.addAnnotation(Inject.class);
                        constructor.parameters().getFirst().addAnnotation(
                                AnnotationBuilder.of(Chosen.class).member("value", "selected").build());
                    });
        }
    }

    @Test
    void constructorAnEnhancementMarksInjectIsTheBeanConstructor() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(TwoConstructors.class)
                .addBeanClass(Dependency.class)
                .addBeanClass(ConstructorInjectionBce.class)
                .build()) {
            var instance = container.select(TwoConstructors.class);

            assertNotNull(instance.dependency,
                    "the bean must be created through the constructor the enhancement marked @Inject");
            var bean = container.getBeanManager().getBeans(TwoConstructors.class).iterator().next();
            assertTrue(bean.getInjectionPoints().stream()
                    .anyMatch(point -> point.getMember() instanceof java.lang.reflect.Constructor<?>
                            && hasSelectedQualifier(point)),
                    "the parameter of the enhanced bean constructor must be reported as an injection point");
        }
    }

    @Test
    void unsatisfiedParameterOfConstructorAnEnhancementMarksInjectFailsDeployment() {
        var builder = VaubanContainer.builder()
                .addBeanClass(TwoConstructors.class)
                .addBeanClass(ConstructorInjectionBce.class);

        assertThrows(jakarta.enterprise.inject.spi.DeploymentException.class, builder::build);
    }

    @Test
    void appliesAddedFieldAndInitializerInjectionAndConstructorParameterQualifier() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Target.class)
                .addBeanClass(Dependency.class)
                .addBeanClass(InjectionBce.class)
                .build()) {
            var bean = container.getBeanManager().getBeans(Target.class).iterator().next();
            var injectionPoints = bean.getInjectionPoints();
            assertTrue(injectionPoints.stream()
                    .filter(point -> point.getMember() instanceof java.lang.reflect.Constructor<?>)
                    .allMatch(BceInjectionEnhancementTest::hasSelectedQualifier),
                    "the enhanced constructor parameter must carry @Chosen");
            assertTrue(injectionPoints.stream()
                    .anyMatch(point -> point.getMember() instanceof java.lang.reflect.Field
                            && hasSelectedQualifier(point)),
                    "the BCE-added field must be reported as an injection point");
            assertTrue(injectionPoints.stream()
                    .anyMatch(point -> point.getMember() instanceof java.lang.reflect.Method method
                            && method.getName().equals("initialize") && hasSelectedQualifier(point)),
                    "the BCE-added initializer parameter must be reported as an injection point");
            var target = container.select(Target.class);

            assertNotNull(target.field);
            assertNotNull(target.constructor);
            assertNotNull(target.initialized);
            org.junit.jupiter.api.Assertions.assertFalse(target.unrelatedOverloadCalled);
        }
    }

    private static boolean hasSelectedQualifier(jakarta.enterprise.inject.spi.InjectionPoint point) {
        return point.getQualifiers().stream().anyMatch(qualifier ->
                qualifier.annotationType() == Chosen.class && ((Chosen) qualifier).value().equals("selected"));
    }
}
