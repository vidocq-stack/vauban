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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection point of a type variable is a definition error (CDI 4.1, 5.2.3) on a bean, as bean discovery decides
 * what a bean is: a class with a bean-defining annotation, or one an extension made a bean. A concrete class without a
 * bean-defining annotation is no bean in the annotated discovery mode: a subclass that inherits its members gives
 * them their types (BUG-20261117-30, review round 1).
 */
@DisplayName("BUG-20261117-30 - an injection point of a type variable, on a bean as discovery decides")
class TypeVariableInjectionPointTest {

    private static final String TYPE_VARIABLE = "Injection point has type variable type T: ";

    @Dependent
    public static class Gadget {
    }

    /** No bean-defining annotation: no bean, and its initializer is its subclasses'. */
    public static class SetterBase<T> {
        T got;

        @Inject
        void set(T value) {
            got = value;
        }
    }

    @Dependent
    public static class SetterSub extends SetterBase<Gadget> {
    }

    /** No bean-defining annotation: no bean, and its field is its subclasses' (BUG-20261117-29, review round 1). */
    public static class FieldBase<T> {
        @Inject
        T got;
    }

    @Dependent
    public static class FieldSub extends FieldBase<Gadget> {
    }

    @Vetoed
    public static class VetoedSetter<T> {
        T got;

        @Inject
        void set(T value) {
            got = value;
        }
    }

    @Dependent
    public static class GenericSetterOfVetoed<T> extends VetoedSetter<T> {
    }

    /** What Cassini's {@code @Path} is to its scope extension: a class that names no scope of its own. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Resource {
    }

    @Resource
    public static class ResourceSetter<T> {
        @Inject
        void set(T value) {
            // Only the validation reads it.
        }
    }

    /** A bean only an extension adds to the deployment, through {@code ScannedClasses} (BUG-20261118-12). */
    @Dependent
    public static class ScannedSetter<T> {
        @Inject
        void set(T value) {
            // no behaviour needed
        }
    }

    public static class ScanningExtension implements BuildCompatibleExtension {
        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(ScannedSetter.class.getName());
        }
    }

    /** Makes a {@code @Resource} class a bean, as Cassini's {@code CassiniScopeExtension} does a {@code @Path} one. */
    public static class DependentScopeExtension implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = Resource.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(Dependent.class);
        }
    }

    @Test
    @DisplayName("an initializer a class without a bean-defining annotation declares is its subclass's")
    void anInitializerOfAClassWithoutABeanDefiningAnnotationIsItsSubclasss() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(SetterBase.class)
                .addBeanClass(SetterSub.class);

        try (var container = assertDoesNotThrow(builder::build, "the container refused the deployment")) {
            assertInstanceOf(Gadget.class, container.select(SetterSub.class).got, "what the initializer got");
        }
    }

    @Test
    @DisplayName("an @Inject field a class without a bean-defining annotation declares is its subclass's")
    void anInjectFieldOfAClassWithoutABeanDefiningAnnotationIsItsSubclasss() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(FieldBase.class)
                .addBeanClass(FieldSub.class);

        try (var container = assertDoesNotThrow(builder::build, "the container refused the deployment")) {
            assertInstanceOf(Gadget.class, container.select(FieldSub.class).got, "what the field got");
        }
    }

    @Test
    @DisplayName("an inherited initializer's parameter the bean class leaves a type variable is a definition error")
    void anInheritedInitializersParameterTheBeanClassLeavesATypeVariableIsADefinitionError() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(GenericSetterOfVetoed.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains(TYPE_VARIABLE + GenericSetterOfVetoed.class.getName()
                + ".set initializer param 0"), refused::getMessage);
    }

    @Test
    @DisplayName("a class an extension makes a bean has its injection points of a type variable checked")
    void aClassAnExtensionMakesABeanHasItsInjectionPointsChecked() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(ResourceSetter.class)
                .addBeanClass(DependentScopeExtension.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains(TYPE_VARIABLE + ResourceSetter.class.getName()
                + ".set initializer param 0"), refused::getMessage);
    }

    @Test
    @DisplayName("a class an extension adds through ScannedClasses has its class-level definition rules checked")
    void aClassAnExtensionAddsThroughScannedClassesHasItsDefinitionRulesChecked() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(ScanningExtension.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains(TYPE_VARIABLE + ScannedSetter.class.getName()
                + ".set initializer param 0"), refused::getMessage);
    }
}
