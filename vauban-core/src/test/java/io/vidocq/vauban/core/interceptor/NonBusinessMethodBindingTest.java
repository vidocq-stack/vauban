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
package io.vidocq.vauban.core.interceptor;

import io.vidocq.vauban.core.interceptor.fixtures.FixtureBound;
import io.vidocq.vauban.core.interceptor.fixtures.ForeignBoundBase;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A binding declared on a method that is not a business method of the bean — private, static, or
 * package-private in a superclass of another package, which the bean does not inherit (JLS 8.4.8)
 * — does not make the bean intercepted. The processor does not count it either, so on the module
 * path counting it here meant wrapping the bean without a pre-generated subclass, and failing
 * with "Could not define interceptor subclass".
 */
@DisplayName("A binding on a method that is not a business method does not intercept the bean")
class NonBusinessMethodBindingTest {

    @FixtureBound
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class FixtureInterceptor {
        static final List<Method> CALLS = new CopyOnWriteArrayList<>();

        @AroundInvoke
        public Object record(InvocationContext ctx) throws Exception {
            CALLS.add(ctx.getMethod());
            return ctx.proceed();
        }
    }

    @Dependent
    public static class BoundThroughAForeignPackagePrivateMethod extends ForeignBoundBase {
        public String own() {
            return "own";
        }
    }

    @Dependent
    public static class BoundThroughAPrivateMethod {
        @FixtureBound
        private String hidden() {
            return "hidden";
        }

        public String visible() {
            return hidden();
        }
    }

    @Dependent
    public static class BoundThroughAStaticMethod {
        @FixtureBound
        static String util() {
            return "util";
        }

        public String visible() {
            return util();
        }
    }

    private static SeContainer container;

    @BeforeAll
    static void boot() {
        container = SeContainerInitializer.newInstance()
                .addBeanClasses(BoundThroughAForeignPackagePrivateMethod.class, BoundThroughAPrivateMethod.class,
                        BoundThroughAStaticMethod.class, FixtureInterceptor.class)
                .initialize();
    }

    @AfterAll
    static void shutdown() {
        if (container != null) container.close();
    }

    @BeforeEach
    void reset() {
        FixtureInterceptor.CALLS.clear();
    }

    @Test
    @DisplayName("a package-private method of a superclass in another package")
    void foreignPackagePrivateMethod() {
        var bean = container.select(BoundThroughAForeignPackagePrivateMethod.class).get();
        assertEquals(BoundThroughAForeignPackagePrivateMethod.class, bean.getClass(), "not intercepted");
        assertEquals("own", bean.own());
        assertEquals(List.of(), FixtureInterceptor.CALLS);
    }

    @Test
    @DisplayName("a private method")
    void privateMethod() {
        var bean = container.select(BoundThroughAPrivateMethod.class).get();
        assertEquals(BoundThroughAPrivateMethod.class, bean.getClass(), "not intercepted");
        assertEquals("hidden", bean.visible());
        assertEquals(List.of(), FixtureInterceptor.CALLS);
    }

    @Test
    @DisplayName("a static method")
    void staticMethod() {
        var bean = container.select(BoundThroughAStaticMethod.class).get();
        assertEquals(BoundThroughAStaticMethod.class, bean.getClass(), "not intercepted");
        assertEquals("util", bean.visible());
        assertEquals(List.of(), FixtureInterceptor.CALLS);
    }
}
