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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code InvocationContext.getMethod()} for a business method the bean inherits, seen through the
 * {@code $$Intercepted} subclass the Vauban processor renders as source at build time, on the
 * module path. The subclass hands the context its {@code $$super$<name>} bridge; the context must
 * answer the method the bean class actually declares or inherits (BUG-20261004-01).
 *
 * <p>Unlike the run-time front-end, the processor also intercepts an inherited protected or
 * package-private method, so this is where that edge is pinned. Private methods are not business
 * methods and are never intercepted.</p>
 */
@DisplayName("InvocationContext.getMethod() — inherited method, processor subclass, module path")
class InheritedMethodModulePathTest {

    private static VaubanContainer container;
    private static InheritingService service;

    @BeforeAll
    static void boot() {
        container = VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(InheritingService.class)
                .build();
        service = container.select(InheritingService.class);
    }

    @AfterAll
    static void shutdown() {
        if (container != null) container.close();
    }

    @BeforeEach
    void reset() {
        AuditInterceptor.METHODS.clear();
    }

    private static Method onlyMethod() {
        assertEquals(1, AuditInterceptor.METHODS.size(),
                "exactly one intercepted call expected, got " + AuditInterceptor.METHODS);
        var method = AuditInterceptor.METHODS.getFirst();
        assertFalse(method.getName().startsWith("$$"), "getMethod() leaked the generated bridge: " + method);
        return method;
    }

    @Test
    @DisplayName("the processor generated the subclass at build time, and the container uses it")
    void buildTimeSubclass() throws Exception {
        var subclass = Class.forName("io.vidocq.vauban.moduleit.InheritingService$$Intercepted");
        assertNotNull(subclass);
        assertEquals(subclass, service.getClass(), "a @Dependent bean is its intercepted subclass");
    }

    @Test
    @DisplayName("a method the bean class declares is its own method")
    void declaredOnTheBeanClass() throws Exception {
        assertEquals("C", service.own());
        assertEquals(InheritingService.class.getDeclaredMethod("own"), onlyMethod());
    }

    @Test
    @DisplayName("a method declared on the direct superclass is the superclass's method")
    void declaredOnTheParent() throws Exception {
        assertEquals("B:7", service.fromParent(7));
        assertEquals(AuditedMiddle.class.getDeclaredMethod("fromParent", int.class), onlyMethod());
    }

    @Test
    @DisplayName("a method declared on the grandparent is the grandparent's method")
    void declaredOnTheGrandparent() throws Exception {
        assertEquals("A:x", service.fromGrandparent("x"));
        assertEquals(AuditedBase.class.getDeclaredMethod("fromGrandparent", String.class), onlyMethod());
    }

    @Test
    @DisplayName("a method overridden half-way up is the most derived declaration")
    void overriddenOnTheParent() throws Exception {
        assertEquals("B", service.overridden());
        assertEquals(AuditedMiddle.class.getDeclaredMethod("overridden"), onlyMethod());
    }

    @Test
    @DisplayName("an inherited protected method is the grandparent's method")
    void inheritedProtected() throws Exception {
        assertEquals("A-prot", service.inheritedProtected());
        assertEquals(AuditedBase.class.getDeclaredMethod("inheritedProtected"), onlyMethod());
    }

    @Test
    @DisplayName("an inherited package-private method is the grandparent's method")
    void inheritedPackagePrivate() throws Exception {
        assertEquals("A-pp", service.inheritedPackagePrivate());
        assertEquals(AuditedBase.class.getDeclaredMethod("inheritedPackagePrivate"), onlyMethod());
    }

    @Test
    @DisplayName("an interface default method the bean does not override is the interface's method")
    void defaultMethodOfAnInterface() throws Exception {
        assertEquals("hi y", service.greet("y"));
        assertEquals(Greeting.class.getDeclaredMethod("greet", String.class), onlyMethod());
    }

    @Test
    @DisplayName("an overload declared on the grandparent resolves to the grandparent's method")
    void overloadOnTheGrandparent() throws Exception {
        assertEquals("A-string:s", service.overloaded("s"));
        assertEquals(AuditedBase.class.getDeclaredMethod("overloaded", String.class), onlyMethod());
    }

    @Test
    @DisplayName("an overload declared on the parent resolves to the parent's method")
    void overloadOnTheParent() throws Exception {
        assertEquals("B-int:4", service.overloaded(4));
        assertEquals(AuditedMiddle.class.getDeclaredMethod("overloaded", int.class), onlyMethod());
    }
}
