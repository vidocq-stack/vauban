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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.inject.Singleton;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("An @Enhancement that replaces the scope of a bean gives it the new scope (BUG-20261118-62)")
class EnhancementReplacesScopeTest {

    @Dependent
    public static class Plain {
        public String id() {
            return "plain";
        }
    }

    @ApplicationScoped
    public static class Shared {
        public String id() {
            return "shared";
        }
    }

    /** Takes the scope away with a predicate, and gives another one. */
    public static class Rescoping implements BuildCompatibleExtension {
        @Enhancement(types = Plain.class)
        public void rescope(ClassConfig plain) {
            plain.removeAnnotation(annotation -> annotation.name().equals(Dependent.class.getName()));
            plain.addAnnotation(ApplicationScoped.class);
        }
    }

    /** Takes every annotation away, and gives a scope. */
    public static class RescopingAll implements BuildCompatibleExtension {
        @Enhancement(types = Plain.class)
        public void rescope(ClassConfig plain) {
            plain.removeAllAnnotations();
            plain.addAnnotation(ApplicationScoped.class);
        }
    }

    /** Makes a normal-scoped bean a dependent one. */
    public static class Unsharing implements BuildCompatibleExtension {
        @Enhancement(types = Shared.class)
        public void rescope(ClassConfig shared) {
            shared.removeAnnotation(annotation -> annotation.name().equals(ApplicationScoped.class.getName()));
            shared.addAnnotation(Dependent.class);
        }
    }

    /** Removes an annotation the class does not have: the scope stays. */
    public static class RemovingAnother implements BuildCompatibleExtension {
        @Enhancement(types = Plain.class)
        public void rescope(ClassConfig plain) {
            plain.removeAnnotation(annotation -> annotation.name().equals(Singleton.class.getName()));
            plain.addAnnotation(Deprecated.class);
        }
    }

    private static VaubanContainer start(Class<?>... classes) {
        VaubanContainerBuilder builder = VaubanContainer.builder();
        List.of(classes).forEach(builder::addBeanClass);
        return builder.build();
    }

    @Test
    @DisplayName("@Dependent removed by a predicate, @ApplicationScoped added: one instance")
    void aDependentBeanBecomesApplicationScoped() {
        try (VaubanContainer container = start(Plain.class, Rescoping.class)) {
            assertSame(container.select(Plain.class), container.select(Plain.class));
            assertEquals("plain", container.select(Plain.class).id());
        }
    }

    @Test
    @DisplayName("every annotation removed, @ApplicationScoped added: one instance")
    void aBeanWithoutItsAnnotationsTakesTheScopeAdded() {
        try (VaubanContainer container = start(Plain.class, RescopingAll.class)) {
            assertSame(container.select(Plain.class), container.select(Plain.class));
        }
    }

    @Test
    @DisplayName("@ApplicationScoped removed, @Dependent added: an instance per lookup")
    void anApplicationScopedBeanBecomesDependent() {
        try (VaubanContainer container = start(Shared.class, Unsharing.class)) {
            assertNotSame(container.select(Shared.class), container.select(Shared.class));
        }
    }

    @Test
    @DisplayName("a removal that matches no scope leaves the scope")
    void aRemovalOfAnotherAnnotationLeavesTheScope() {
        try (VaubanContainer container = start(Plain.class, RemovingAnother.class)) {
            assertNotSame(container.select(Plain.class), container.select(Plain.class));
        }
    }
}
