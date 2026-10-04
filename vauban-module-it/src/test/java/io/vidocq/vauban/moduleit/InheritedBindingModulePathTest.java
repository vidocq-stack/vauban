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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The processor's front-ends, on the module path, for a binding or a business method the bean
 * inherits: its client proxy forwards an interface default method (BUG-20261004-02), and a bean
 * bound only through an inherited method — a superclass method or an interface default method —
 * gets its {@code $$Intercepted} subclass at build time. As for a superclass method (CDI 4.1
 * §4.2), the binding of an inherited method stops applying once a class of the bean overrides it.
 *
 * <p>Each test boots its own container with one bean, so a bean the container cannot intercept
 * fails its own test only.</p>
 */
@DisplayName("Inherited binding — processor proxy and subclass, module path")
class InheritedBindingModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.METHODS.clear();
    }

    private static VaubanContainer boot(Class<?> beanClass) {
        return VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(beanClass)
                .build();
    }

    /** The subclass the processor rendered at build time, or a failure naming the missing class. */
    private static Class<?> buildTimeSubclassOf(Class<?> beanClass) {
        var name = beanClass.getName() + "$$Intercepted";
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("the processor did not generate " + name, e);
        }
    }

    @Test
    @DisplayName("a default method called through the client proxy is intercepted")
    void defaultMethodThroughTheClientProxy() throws Exception {
        try (var container = boot(ScopedGreetingService.class)) {
            var service = container.select(ScopedGreetingService.class);
            assertEquals("hi y", service.greet("y"));
            assertEquals(List.of(Greeting.class.getDeclaredMethod("greet", String.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a bean bound only through a default method gets its subclass, and the binding applies")
    void defaultMethodBindingApplies() throws Exception {
        try (var container = boot(DefaultBoundService.class)) {
            var service = container.select(DefaultBoundService.class);
            assertEquals(buildTimeSubclassOf(DefaultBoundService.class), service.getClass());
            assertEquals("audited z", service.audited("z"));
            assertEquals(List.of(AuditedGreeting.class.getDeclaredMethod("audited", String.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a default method overridden by the bean class drops the default method's binding")
    void overriddenDefaultMethodDropsItsBinding() throws Exception {
        try (var container = boot(DefaultBoundService.class)) {
            assertEquals("class", container.select(DefaultBoundService.class).rebound());
            assertEquals(List.of(), AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a bean bound only through a superclass method gets its subclass, and the binding applies")
    void inheritedMethodBindingApplies() throws Exception {
        try (var container = boot(InheritedBindingService.class)) {
            var service = container.select(InheritedBindingService.class);
            assertEquals(buildTimeSubclassOf(InheritedBindingService.class), service.getClass());
            assertEquals("bound", service.inheritedBound());
            assertEquals(List.of(BoundBase.class.getDeclaredMethod("inheritedBound")), AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("an overridden method drops the binding the superclass method declares")
    void overriddenMethodDropsTheSuperclassBinding() throws Exception {
        try (var container = boot(InheritedBindingService.class)) {
            assertEquals("child", container.select(InheritedBindingService.class).overriddenBound());
            assertEquals(List.of(), AuditInterceptor.METHODS);
        }
    }
}
