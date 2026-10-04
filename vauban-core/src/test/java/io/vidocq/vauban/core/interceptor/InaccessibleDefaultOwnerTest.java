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

import io.vidocq.vauban.core.interceptor.fixtures.DefaultCarrier;
import io.vidocq.vauban.core.interceptor.fixtures.FixtureBound;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The one shape where the generated subclass cannot reach a shadowed default method explicitly
 * (BUG-20261004-08): {@code CarriedBean} inherits {@code carried()} from a package-private
 * interface of another package, and a superclass's private {@code carried()} shadows it for
 * {@code super.carried()}. Reaching the default explicitly needs an interface that declares or
 * inherits it among the subclass's direct superinterfaces, and no class may list an interface it
 * cannot access (JVMS 5.4.4 for the run-time subclass, JLS 6.6.1 for a rendered one). So that
 * method is not intercepted: the generated subclass leaves it alone, while the bean's other
 * methods are intercepted.
 *
 * <p>A call of {@code carried()} then behaves exactly as on a plain {@code CarriedBean}. On HotSpot
 * 25 that is an {@code AbstractMethodError}: a class that inherits the interface only through its
 * superclass, under a private shadowing method, does not get the default selected — although JVMS
 * 5.4.6 says it should — so the method is unreachable on the bean without Vauban too. The test
 * compares the two outcomes rather than pinning that JVM behaviour.</p>
 */
@DisplayName("Shadowed default method with no accessible interface — left out, as without Vauban")
class InaccessibleDefaultOwnerTest {

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

    @FixtureBound
    @Dependent
    public static class CarriedBean extends DefaultCarrier {
        public String own() {
            return "own";
        }
    }

    private static SeContainer container;

    @BeforeAll
    static void boot() {
        container = SeContainerInitializer.newInstance()
                .addBeanClasses(CarriedBean.class, FixtureInterceptor.class)
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
    @DisplayName("the shadowed default method is left alone and behaves as without Vauban; the other methods are intercepted")
    void shadowedDefaultWithNoAccessibleInterface() throws Exception {
        var bean = container.select(CarriedBean.class).get();
        assertEquals("own", bean.own());
        assertEquals(List.of(CarriedBean.class.getDeclaredMethod("own")), FixtureInterceptor.CALLS,
                "the bean is intercepted");
        FixtureInterceptor.CALLS.clear();

        assertFalse(InterceptorSubclassGenerator.fromClass(CarriedBean.class).methods().stream()
                .anyMatch(m -> m.name().equals("carried")), "the generated subclass leaves carried() alone");
        assertEquals(outcome(() -> DefaultCarrier.callCarried(new CarriedBean())),
                outcome(() -> DefaultCarrier.callCarried(bean)),
                "carried() on the intercepted bean must behave as on a plain instance");
        assertEquals(List.of(), FixtureInterceptor.CALLS);
    }

    /** The result of {@code call}, or the class of what it threw. */
    private static String outcome(java.util.concurrent.Callable<String> call) {
        try {
            return "returned " + call.call();
        } catch (Throwable thrown) {
            return "threw " + thrown.getClass().getName();
        }
    }
}
