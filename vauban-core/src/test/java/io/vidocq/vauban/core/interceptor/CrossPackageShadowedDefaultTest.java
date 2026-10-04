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
import io.vidocq.vauban.core.interceptor.fixtures.ForeignPackagePrivateTagBase;
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

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An interface default method shadowed by a package-private method of a superclass in another
 * package, with the run-time front-ends. {@code ForeignPackagePrivateTagBase.tag(String)} is not a
 * member of the beans (JLS 8.4.8): their member is {@code Tagged.tag(String)}, which the generated
 * subclass and the client proxy reach through {@code Tagged} (BUG-20261004-08). The client proxy
 * used to forward the package-private method through a MethodHandle — a method the bean does not
 * have — and the default through the interface as well: {@code ClassFormatError: Duplicate method
 * name "tag"} when the proxy was defined.
 */
@DisplayName("Default method shadowed by a package-private method of another package — run-time front-ends")
class CrossPackageShadowedDefaultTest {

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

    public interface Tagged {
        default String tag(String value) {
            return "default " + value;
        }
    }

    /** Reached through its client proxy. */
    @FixtureBound
    @ApplicationScoped
    public static class ScopedCrossPackageTagged extends ForeignPackagePrivateTagBase implements Tagged {
        public String own() {
            return "own";
        }
    }

    /** Reached directly: the generated subclass alone. */
    @FixtureBound
    @Dependent
    public static class CrossPackageTagged extends ForeignPackagePrivateTagBase implements Tagged {
    }

    private static SeContainer container;

    @BeforeAll
    static void boot() {
        container = SeContainerInitializer.newInstance()
                .addBeanClasses(ScopedCrossPackageTagged.class, CrossPackageTagged.class, FixtureInterceptor.class)
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
    @DisplayName("through the client proxy: the default runs on the contextual instance, intercepted once")
    void throughTheClientProxy() throws Exception {
        var scoped = container.select(ScopedCrossPackageTagged.class).get();
        assertEquals("own", scoped.own());
        Tagged tagged = scoped;
        assertEquals("default x", tagged.tag("x"));
        var tag = Tagged.class.getDeclaredMethod("tag", String.class);
        assertEquals(List.of(ScopedCrossPackageTagged.class.getDeclaredMethod("own"), tag), FixtureInterceptor.CALLS);
    }

    @Test
    @DisplayName("through the generated subclass: the default runs, intercepted once")
    void throughTheSubclass() throws Exception {
        Tagged tagged = container.select(CrossPackageTagged.class).get();
        assertEquals("default y", tagged.tag("y"));
        assertEquals(List.of(Tagged.class.getDeclaredMethod("tag", String.class)), FixtureInterceptor.CALLS);
    }
}
