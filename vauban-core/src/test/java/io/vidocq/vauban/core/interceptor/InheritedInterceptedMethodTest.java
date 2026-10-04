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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InvocationContext#getMethod()} for a business method the bean inherits, seen through the
 * {@code $$Intercepted} subclass the container generates at run time with the Class-File API (the
 * class-path fallback; {@code vauban-module-it} covers the subclass the processor renders as source).
 *
 * <p>The subclass reaches each original method through a {@code $$super$<name>} bridge and hands
 * that bridge to the invocation context. {@code getMethod()} must answer the method the bean
 * class actually declares or inherits — its declaring class and its name — never the bridge,
 * wherever it sits in the hierarchy (BUG-20261004-01).</p>
 *
 * <p>Private methods are not business methods and are never intercepted. This front-end does not
 * intercept an inherited protected or package-private method at all (BUG-20261004-03), so the
 * inherited non-public edge is pinned on the processor's subclass, in {@code vauban-module-it}.</p>
 */
@DisplayName("InvocationContext.getMethod() — method inherited by the intercepted bean")
class InheritedInterceptedMethodTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Traced {
    }

    /** A second binding, declared on an inherited method only; no interceptor is bound to it. */
    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Marked {
    }

    /** What the interceptor saw for one call. */
    record Seen(Method method, Set<String> bindings) {
    }

    @Traced
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class TracingInterceptor {
        static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

        @AroundInvoke
        public Object trace(InvocationContext ctx) throws Exception {
            var bindings = ctx.getInterceptorBindings().stream()
                    .map(a -> a.annotationType().getSimpleName())
                    .collect(Collectors.toSet());
            SEEN.add(new Seen(ctx.getMethod(), bindings));
            return ctx.proceed();
        }
    }

    /** An interface whose default method the bean does not override. */
    public interface Greeter {
        default String greet(String who) {
            return "hi " + who;
        }
    }

    /** A — two levels above the bean class. */
    public static class Grandparent {
        @Marked
        public String fromGrandparent(String s) {
            return "A:" + s;
        }

        public String overridden() {
            return "A";
        }
    }

    /** B — the bean's direct superclass. */
    public static class Parent extends Grandparent {
        public String fromParent(int n) {
            return "B:" + n;
        }

        @Override
        public String overridden() {
            return "B";
        }
    }

    /** C — the bean; the generated subclass extends it. */
    @Traced
    @ApplicationScoped
    public static class TracedService extends Parent {
        public String own() {
            return "C";
        }

        String packagePrivateOwn() {
            return "C-pp";
        }
    }

    /**
     * {@code @Dependent}, so no client proxy stands between the caller and the generated
     * subclass: the normal-scoped client proxies do not forward an interface default method
     * (BUG-20261004-02).
     */
    @Traced
    @Dependent
    public static class DependentGreeter implements Greeter {
    }

    private static SeContainer container;
    private static TracedService service;
    private static DependentGreeter greeter;

    @BeforeAll
    static void boot() {
        container = SeContainerInitializer.newInstance()
                .addBeanClasses(TracedService.class, DependentGreeter.class, TracingInterceptor.class)
                .initialize();
        service = container.select(TracedService.class).get();
        greeter = container.select(DependentGreeter.class).get();
    }

    @AfterAll
    static void shutdown() {
        if (container != null) container.close();
    }

    @BeforeEach
    void reset() {
        TracingInterceptor.SEEN.clear();
    }

    private static Seen onlySeen() {
        assertEquals(1, TracingInterceptor.SEEN.size(),
                "exactly one intercepted call expected, got " + TracingInterceptor.SEEN);
        var seen = TracingInterceptor.SEEN.getFirst();
        assertFalse(seen.method().getName().startsWith(InterceptedShape.SUPER_BRIDGE_PREFIX),
                "getMethod() leaked the generated bridge: " + seen.method());
        return seen;
    }

    @Test
    @DisplayName("a method the bean class declares is its own method")
    void declaredOnTheBeanClass() throws Exception {
        assertEquals("C", service.own());
        assertEquals(TracedService.class.getDeclaredMethod("own"), onlySeen().method());
    }

    @Test
    @DisplayName("a method declared on the direct superclass is the superclass's method")
    void declaredOnTheParent() throws Exception {
        assertEquals("B:7", service.fromParent(7));
        assertEquals(Parent.class.getDeclaredMethod("fromParent", int.class), onlySeen().method());
    }

    @Test
    @DisplayName("a method declared on the grandparent is the grandparent's method, with its bindings")
    void declaredOnTheGrandparent() throws Exception {
        assertEquals("A:x", service.fromGrandparent("x"));
        var seen = onlySeen();
        assertEquals(Grandparent.class.getDeclaredMethod("fromGrandparent", String.class), seen.method());
        assertTrue(seen.bindings().contains("Marked"),
                "the method-level binding of the inherited method must be visible, got " + seen.bindings());
    }

    @Test
    @DisplayName("a method overridden half-way up is the most derived declaration")
    void overriddenOnTheParent() throws Exception {
        assertEquals("B", service.overridden());
        assertEquals(Parent.class.getDeclaredMethod("overridden"), onlySeen().method());
    }

    @Test
    @DisplayName("a package-private method of the bean class is its own method")
    void packagePrivateOnTheBeanClass() throws Exception {
        assertEquals("C-pp", service.packagePrivateOwn());
        assertEquals(TracedService.class.getDeclaredMethod("packagePrivateOwn"), onlySeen().method());
    }

    @Test
    @DisplayName("an interface default method the bean does not override is the interface's method")
    void defaultMethodOfAnInterface() throws Exception {
        assertEquals("hi y", greeter.greet("y"));
        assertEquals(Greeter.class.getDeclaredMethod("greet", String.class), onlySeen().method());
    }
}
