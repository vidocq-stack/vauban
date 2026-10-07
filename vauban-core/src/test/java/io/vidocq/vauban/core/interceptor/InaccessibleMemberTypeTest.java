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
import io.vidocq.vauban.core.interceptor.fixtures.HiddenParameterBase;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Inherited methods whose descriptors name a package-private class of another package, through the
 * run-time generators (BUG-20261004-09, {@code n3b}). The {@code $$Intercepted} subclass resolved
 * that class to look its {@code $$super$} bridge up ({@code ldc}) and to cast each argument
 * ({@code checkcast}): {@code IllegalAccessError} on every call. It now loads it by name and calls
 * the bridge through a method handle; a method <em>returning</em> such a class is not intercepted,
 * since no class outside its package can type the value an interceptor chain returns.
 */
@DisplayName("Inherited members naming a package-private class of another package — run-time front-ends")
class InaccessibleMemberTypeTest {

    @FixtureBound
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class FixtureInterceptor {
        static final List<String> CALLS = new CopyOnWriteArrayList<>();

        @AroundInvoke
        public Object record(InvocationContext ctx) throws Exception {
            CALLS.add(ctx.getMethod().getName() + "/" + ctx.getParameters().length);
            return ctx.proceed();
        }
    }

    /** Reached directly: the generated subclass alone. */
    @FixtureBound
    @Dependent
    public static class HiddenTaker extends HiddenParameterBase {
    }

    /** Reached through its client proxy, then its subclass. */
    @FixtureBound
    @ApplicationScoped
    public static class ScopedHiddenTaker extends HiddenParameterBase {
    }

    private static SeContainer container;

    @BeforeAll
    static void boot() {
        container = SeContainerInitializer.newInstance()
                .addBeanClasses(HiddenTaker.class, ScopedHiddenTaker.class, FixtureInterceptor.class)
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
    @DisplayName("a parameter of a package-private class of another package: the call is intercepted")
    void hiddenParameterIntercepted() {
        var bean = container.select(HiddenTaker.class).get();
        assertEquals("took hidden", HiddenParameterBase.callTake(bean));
        assertEquals("took 7 1 hidden 3", HiddenParameterBase.callTakeMany(bean));
        assertEquals(List.of("take/1", "takeMany/3"), FixtureInterceptor.CALLS);
    }

    @Test
    @DisplayName("a return type of a package-private class of another package: the method runs, not intercepted")
    void hiddenReturnTypeLeftAlone() {
        var bean = container.select(HiddenTaker.class).get();
        assertEquals("hidden", HiddenParameterBase.callGive(bean));
        assertEquals(List.of(), FixtureInterceptor.CALLS);
    }

    @Test
    @DisplayName("through the client proxy: forwarded to the contextual instance, intercepted there")
    void throughTheClientProxy() {
        var scoped = container.select(ScopedHiddenTaker.class).get();
        assertEquals("took hidden", HiddenParameterBase.callTake(scoped));
        assertEquals("hidden", HiddenParameterBase.callGive(scoped));
        assertEquals(List.of("take/1"), FixtureInterceptor.CALLS);
    }
}
