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
 * An interface default method the bean inherits, with the run-time front-ends: the client proxy
 * and the {@code $$Intercepted} subclass generated with the Class-File API.
 *
 * <ul>
 *   <li>A call through the client proxy of a normal-scoped bean reaches the contextual instance and
 *       its interceptors (BUG-20261004-02).</li>
 *   <li>An interceptor binding declared on a default method the bean does not override applies to
 *       it, as a binding declared on a superclass method does (CDI 4.1 §4.2): the interceptor chain,
 *       {@code getInterceptorBindings()} and {@code getMethod()} agree on it, and a bean bound only
 *       through such a method is intercepted.</li>
 *   <li>Once a class of the bean overrides the default method, its binding no longer applies.</li>
 * </ul>
 */
@DisplayName("Interface default method — client proxy, subclass and bindings")
class DefaultMethodInterceptionTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Traced {
    }

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

    @Marked
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION + 1)
    public static class MarkingInterceptor {
        static final List<Method> CALLS = new CopyOnWriteArrayList<>();

        @AroundInvoke
        public Object mark(InvocationContext ctx) throws Exception {
            CALLS.add(ctx.getMethod());
            return ctx.proceed();
        }
    }

    public interface Greeter {
        default String greet(String who) {
            return "hi " + who;
        }

        @Marked
        default String markedGreet(String who) {
            return "marked " + who;
        }

        /** Overridden by {@link DependentGreeter}, without the binding. */
        @Marked
        default String rebound() {
            return "iface";
        }
    }

    /** Reached through its client proxy. */
    @Traced
    @ApplicationScoped
    public static class ScopedGreeter implements Greeter {
    }

    /** Reached directly: the generated subclass alone, no client proxy. */
    @Traced
    @Dependent
    public static class DependentGreeter implements Greeter {
        @Override
        public String rebound() {
            return "dependent";
        }
    }

    public interface MarkedOnly {
        @Marked
        default String only() {
            return "only";
        }
    }

    /** No class-level binding: its only binding sits on the default method it inherits. */
    @Dependent
    public static class BoundOnlyByADefaultMethod implements MarkedOnly {
    }

    /** A private method with the signature of {@link Hider#hidden}: not inherited, never called. */
    public static class PrivateHidden {
        @SuppressWarnings("unused")
        private String hidden(String who) {
            return "private " + who;
        }
    }

    public interface Hider {
        @Marked
        default String hidden(String who) {
            return "default " + who;
        }
    }

    /** Inherits {@link Hider#hidden}; its superclass's private {@code hidden} is not a member. */
    @Traced
    @Dependent
    public static class ShadowedGreeter extends PrivateHidden implements Hider {
    }

    /** The same, reached through its client proxy. */
    @Traced
    @ApplicationScoped
    public static class ScopedShadowedGreeter extends PrivateHidden implements Hider {
    }

    private static SeContainer container;
    private static ScopedGreeter scoped;
    private static DependentGreeter dependent;
    private static BoundOnlyByADefaultMethod boundOnlyByADefaultMethod;
    private static ShadowedGreeter shadowed;
    private static ScopedShadowedGreeter scopedShadowed;

    @BeforeAll
    static void boot() {
        container = SeContainerInitializer.newInstance()
                .addBeanClasses(ScopedGreeter.class, DependentGreeter.class, BoundOnlyByADefaultMethod.class,
                        ShadowedGreeter.class, ScopedShadowedGreeter.class,
                        TracingInterceptor.class, MarkingInterceptor.class)
                .initialize();
        scoped = container.select(ScopedGreeter.class).get();
        dependent = container.select(DependentGreeter.class).get();
        boundOnlyByADefaultMethod = container.select(BoundOnlyByADefaultMethod.class).get();
        shadowed = container.select(ShadowedGreeter.class).get();
        scopedShadowed = container.select(ScopedShadowedGreeter.class).get();
    }

    @AfterAll
    static void shutdown() {
        if (container != null) container.close();
    }

    @BeforeEach
    void reset() {
        TracingInterceptor.SEEN.clear();
        MarkingInterceptor.CALLS.clear();
    }

    private static Seen onlySeen() {
        assertEquals(1, TracingInterceptor.SEEN.size(),
                "exactly one intercepted call expected, got " + TracingInterceptor.SEEN);
        return TracingInterceptor.SEEN.getFirst();
    }

    @Test
    @DisplayName("a default method called through the client proxy is intercepted")
    void defaultMethodThroughTheClientProxy() throws Exception {
        assertEquals("hi y", scoped.greet("y"));
        assertEquals(Greeter.class.getDeclaredMethod("greet", String.class), onlySeen().method());
    }

    @Test
    @DisplayName("the binding of a default method applies: chain, bindings and method agree")
    void defaultMethodBindingApplies() throws Exception {
        assertEquals("marked z", dependent.markedGreet("z"));
        var seen = onlySeen();
        var markedGreet = Greeter.class.getDeclaredMethod("markedGreet", String.class);
        assertEquals(markedGreet, seen.method());
        assertTrue(seen.bindings().contains("Marked"), "got " + seen.bindings());
        assertEquals(List.of(markedGreet), MarkingInterceptor.CALLS,
                "the interceptor bound to the default method's binding must run");
    }

    @Test
    @DisplayName("the binding of a default method applies through the client proxy too")
    void defaultMethodBindingAppliesThroughTheClientProxy() throws Exception {
        assertEquals("marked w", scoped.markedGreet("w"));
        var markedGreet = Greeter.class.getDeclaredMethod("markedGreet", String.class);
        assertEquals(markedGreet, onlySeen().method());
        assertEquals(List.of(markedGreet), MarkingInterceptor.CALLS);
    }

    @Test
    @DisplayName("a bean bound only through a default method it inherits is intercepted")
    void beanBoundOnlyByADefaultMethod() throws Exception {
        assertEquals("only", boundOnlyByADefaultMethod.only());
        assertEquals(List.of(MarkedOnly.class.getDeclaredMethod("only")), MarkingInterceptor.CALLS);
    }

    @Test
    @DisplayName("a private superclass method with the same signature does not shadow the default method")
    void privateSuperclassMethodDoesNotShadowTheDefault() throws Exception {
        // The bean's member is Hider.hidden: the private PrivateHidden.hidden is not inherited
        // (JLS 8.4.8). getMethod(), the bindings and the chain must report that one. Called through
        // Hider: this test class is a nestmate of PrivateHidden, so a call typed ShadowedGreeter
        // would resolve to the private method and run it directly.
        // The generated subclass reaches the default explicitly, Hider being one of its direct
        // superinterfaces: a plain super.hidden(...) resolves to the private PrivateHidden.hidden,
        // as any call typed ShadowedGreeter from outside the nest does (BUG-20261004-08).
        Hider hider = shadowed;
        assertEquals("default x", hider.hidden("x"));
        var seen = onlySeen();
        var hidden = Hider.class.getDeclaredMethod("hidden", String.class);
        assertEquals(hidden, seen.method());
        assertTrue(seen.bindings().contains("Marked"), "got " + seen.bindings());
        assertEquals(List.of(hidden), MarkingInterceptor.CALLS);
    }

    @Test
    @DisplayName("the same default method, through the client proxy of a normal-scoped bean")
    void shadowedDefaultThroughTheClientProxy() throws Exception {
        // The proxy forwards through Hider as well: a call typed ScopedShadowedGreeter on the
        // contextual instance would resolve to the private method.
        Hider hider = scopedShadowed;
        assertEquals("default y", hider.hidden("y"));
        var hidden = Hider.class.getDeclaredMethod("hidden", String.class);
        assertEquals(hidden, onlySeen().method());
        assertEquals(List.of(hidden), MarkingInterceptor.CALLS);
    }

    @Test
    @DisplayName("a default method overridden by the bean class drops the default method's binding")
    void overriddenDefaultMethodDropsItsBinding() throws Exception {
        assertEquals("dependent", dependent.rebound());
        var seen = onlySeen();
        assertEquals(DependentGreeter.class.getDeclaredMethod("rebound"), seen.method());
        assertFalse(seen.bindings().contains("Marked"), "got " + seen.bindings());
        assertEquals(List.of(), MarkingInterceptor.CALLS);
    }
}
