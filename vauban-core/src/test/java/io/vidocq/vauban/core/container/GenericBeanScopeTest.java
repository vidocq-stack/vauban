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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI 4.1, 3.1: "If the managed bean class is a generic type, it must have scope {@code @Dependent}. If a managed bean
 * with a parameterized bean class declares any scope other than {@code @Dependent}, the container automatically
 * detects the problem and treats it as a definition error" (CDI TCK {@code GenericManagedBeanTest}, in the Lite
 * part). The scope counts whether the class declares it or an extension adds it at {@code @Enhancement}, as Weld
 * checks the scope of the annotated type the extensions modified (BUG-20261117-35).
 */
@DisplayName("BUG-20261117-35 - a generic managed bean is @Dependent")
class GenericBeanScopeTest {

    private static final String NOT_DEPENDENT = " has type parameters and is not @Dependent: @";

    /** What Cassini's {@code @Path} is to its scope extension: a class that names no scope of its own. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Resource {
    }

    @Resource
    public static class GenericResource<T> {
    }

    @Resource
    public static class PlainResource {
    }

    /** As Cassini's {@code CassiniScopeExtension}: a class marked {@code @Resource} and no scope gets one. */
    public static class RequestScopeExtension implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = Resource.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(RequestScoped.class);
        }
    }

    /** The scope a generic class may have. */
    public static class DependentScopeExtension implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = Resource.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(Dependent.class);
        }
    }

    @ApplicationScoped
    public static class GenericApplicationScoped<T> {
    }

    @Singleton
    public static class GenericSingleton<T> {
    }

    @Test
    @DisplayName("a normal scope an extension adds to a generic class is a definition error")
    void aNormalScopeAnExtensionAddsToAGenericClassIsADefinitionError() {
        var builder = VaubanContainer.builder().addBeanClass(GenericResource.class)
                .addBeanClass(PlainResource.class).addBeanClass(RequestScopeExtension.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains("Managed bean " + GenericResource.class.getName() + NOT_DEPENDENT
                + RequestScoped.class.getName()), refused::getMessage);
        assertFalse(refused.getMessage().contains(PlainResource.class.getName()), refused::getMessage);
    }

    @Test
    @DisplayName("@Dependent an extension adds to a generic class is legal")
    void dependentAnExtensionAddsToAGenericClassIsLegal() {
        var builder = VaubanContainer.builder().addBeanClass(GenericResource.class)
                .addBeanClass(DependentScopeExtension.class);

        try (var container = assertDoesNotThrow(builder::build, "the container refused the deployment")) {
            var beans = container.getBeanManager().getBeans(GenericResource.class);
            assertEquals(Dependent.class, beans.iterator().next().getScope(), "the scope of the generic bean");
        }
    }

    @Test
    @DisplayName("a scope a generic class declares is named in the definition error")
    void aScopeAGenericClassDeclaresIsNamed() {
        var applicationScoped = VaubanContainer.builder().addBeanClass(GenericApplicationScoped.class);
        var singleton = VaubanContainer.builder().addBeanClass(GenericSingleton.class);

        var refusedApplication = assertThrows(DefinitionException.class, applicationScoped::build);
        var refusedSingleton = assertThrows(DefinitionException.class, singleton::build);
        assertTrue(refusedApplication.getMessage().contains("Managed bean " + GenericApplicationScoped.class.getName()
                + NOT_DEPENDENT + ApplicationScoped.class.getName()), refusedApplication::getMessage);
        assertTrue(refusedSingleton.getMessage().contains("Managed bean " + GenericSingleton.class.getName()
                + NOT_DEPENDENT + Singleton.class.getName()), refusedSingleton::getMessage);
    }
}
