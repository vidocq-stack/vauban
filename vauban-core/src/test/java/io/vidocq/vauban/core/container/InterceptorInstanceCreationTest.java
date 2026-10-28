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

import io.vidocq.vauban.core.interceptor.VaubanInvocationContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.CreationException;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the container creates interceptor instances: through a constructor whose parameters it resolves (by qualifier
 * when one is declared), while another interceptor is being created (an interceptor injecting an intercepted bean),
 * and what it reports when an interceptor cannot be created. Also covers a bean intercepted only by its own
 * {@code @AroundInvoke} method, and the intercepted factory called directly.
 */
@DisplayName("InterceptorBeanWrapper: creating interceptor instances")
class InterceptorInstanceCreationTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    /** How many more times the {@link CountingInterceptor} constructor refuses to build an instance. */
    static final AtomicInteger COUNTING_REFUSALS = new AtomicInteger();

    /** How many more times the {@link TrustingInterceptor} constructor refuses to build an instance. */
    static final AtomicInteger TRUSTING_REFUSALS = new AtomicInteger();

    /** How many more times the {@link SturdyInterceptor} constructor refuses to build an instance. */
    static final AtomicInteger STURDY_REFUSALS = new AtomicInteger();

    /**
     * Fails the construction while {@code refusals} is positive: the container's first attempt, creating the
     * interceptor as a bean, fails, and it then creates the interceptor itself.
     */
    static void refuseOnce(AtomicInteger refusals) {
        if (refusals.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("first construction refused");
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Counted {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Watched {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Broken {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Guarded {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Fragile {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Sturdy {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Trusting {
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    public @interface Prefix {
    }

    public interface Label {
        String text();
    }

    @Prefix
    @Dependent
    public static class PrefixLabel implements Label {
        @Override
        public String text() {
            return "prefix";
        }
    }

    @Dependent
    public static class Clock {
        public String now() {
            return "now";
        }
    }

    /** Built through its {@code @Inject} constructor: a qualified parameter and a plain one. */
    @Counted
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class CountingInterceptor {
        private final Label label;
        private final Clock clock;

        /** A no-arg constructor as well: the container prefers the {@code @Inject} one. */
        public CountingInterceptor() {
            this(() -> "none", new Clock());
        }

        @Inject
        public CountingInterceptor(@Prefix Label label, Clock clock) {
            refuseOnce(COUNTING_REFUSALS);
            this.label = label;
            this.clock = clock;
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add(label.text() + "@" + clock.now() + ":" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @Dependent
    public static class CountedService {
        @Counted
        public String work() {
            return "worked";
        }
    }

    @Broken
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class BrokenInterceptor {
        public BrokenInterceptor() {
            throw new IllegalStateException("interceptor cannot be built");
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Dependent
    public static class BrokenService {
        @Broken
        public String work() {
            return "never";
        }
    }

    /** Intercepts the bean the {@link GuardInterceptor} injects; created while the guard is being created. */
    @Watched
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION + 10)
    public static class WatchInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("watched:" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @Watched
    @Dependent
    public static class Watchman {
        public String look() {
            return "looked";
        }
    }

    @Guarded
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class GuardInterceptor {
        @Inject
        Watchman watchman;

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("guard:" + watchman.look());
            return context.proceed();
        }
    }

    @Dependent
    public static class GuardedService {
        @Guarded
        public String enter() {
            return "entered";
        }
    }

    /** Intercepts the bean the {@link CarefulInterceptor} injects, and cannot be built. */
    @Fragile
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION + 10)
    public static class FragileInterceptor {
        public FragileInterceptor() {
            throw new IllegalStateException("nested interceptor cannot be built");
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Fragile
    @Dependent
    public static class FragileHelper {
        public String help() {
            return "helped";
        }
    }

    @Counted
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION + 20)
    public static class CarefulInterceptor {
        @Inject
        FragileHelper helper;

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    /** Intercepts the bean the {@link TrustingInterceptor} injects. */
    @Sturdy
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION + 10)
    public static class SturdyInterceptor {
        public SturdyInterceptor() {
            refuseOnce(STURDY_REFUSALS);
        }

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("sturdy:" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @Sturdy
    @Dependent
    public static class SturdyHelper {
        public String help() {
            return "helped";
        }
    }

    @Trusting
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION + 20)
    public static class TrustingInterceptor {
        public TrustingInterceptor() {
            refuseOnce(TRUSTING_REFUSALS);
        }

        @Inject
        SturdyHelper helper;

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("trusting:" + helper.help());
            return context.proceed();
        }
    }

    @Dependent
    public static class TrustingService {
        @Counted
        @Trusting
        public String work() {
            return "trusted";
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.CONSTRUCTOR})
    public @interface Refused {
    }

    /** Fails every construction it intercepts with a checked exception. */
    @Refused
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class RefusingInterceptor {
        @jakarta.interceptor.AroundConstruct
        public Object construct(InvocationContext context) throws Exception {
            throw new java.io.IOException("construction refused");
        }
    }

    @Refused
    @Dependent
    public static class RefusedService {
    }

    /** Intercepted, and built through an {@code @Inject} constructor whose parameter the descriptor describes. */
    @Dependent
    public static class TimedService {
        private final Clock clock;

        @Inject
        public TimedService(Clock clock) {
            this.clock = clock;
        }

        @Counted
        public String time() {
            return clock.now();
        }
    }

    public static class PlainBase {
        public String base() {
            return "base";
        }
    }

    /** Neither bound nor intercepting itself, in a deployment with no interceptor. */
    @Dependent
    public static class PlainChild extends PlainBase {
    }

    /** Intercepted by its own {@code @AroundInvoke} method only; says goodbye when destroyed. */
    @Dependent
    public static class SelfInterceptedFarewell {
        public String work() {
            return "self";
        }

        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }

        @jakarta.annotation.PreDestroy
        void farewell() {
            CALLS.add("farewell");
        }
    }

    /** Intercepted by its own {@code @AroundInvoke} method, in a deployment with no interceptor. */
    @Dependent
    public static class SelfIntercepted {
        public String work() {
            return "self";
        }

        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            CALLS.add("self:" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
        COUNTING_REFUSALS.set(0);
        STURDY_REFUSALS.set(0);
        TRUSTING_REFUSALS.set(0);
    }

    @Test
    @DisplayName("an interceptor's constructor parameters are resolved, a qualified one by its qualifier")
    void constructorInjectedInterceptor() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(PrefixLabel.class)
                .addBeanClass(Clock.class)
                .addBeanClass(CountingInterceptor.class)
                .addBeanClass(CountedService.class)
                .build()) {

            assertEquals("worked", container.select(CountedService.class).work());
            assertEquals(List.of("prefix@now:work"), List.copyOf(CALLS));
        }
    }

    @Test
    @DisplayName("an interceptor that cannot be created as a bean is created by the container, constructor injected")
    void interceptorCreatedByTheContainer() {
        COUNTING_REFUSALS.set(1);
        try (var container = VaubanContainer.builder()
                .addBeanClass(PrefixLabel.class)
                .addBeanClass(Clock.class)
                .addBeanClass(CountingInterceptor.class)
                .addBeanClass(CountedService.class)
                .build()) {

            assertEquals("worked", container.select(CountedService.class).work());
            assertEquals(List.of("prefix@now:work"), List.copyOf(CALLS));
        }
    }

    @Test
    @DisplayName("an interceptor whose constructor throws fails the creation of the bean it intercepts")
    void interceptorThatCannotBeCreated() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(BrokenInterceptor.class)
                .addBeanClass(BrokenService.class)
                .build()) {

            var thrown = assertThrows(RuntimeException.class, () -> container.select(BrokenService.class));

            assertTrue(causeChain(thrown).contains("interceptor cannot be built"), causeChain(thrown));
        }
    }

    @Test
    @DisplayName("an interceptor injecting an intercepted bean gets it intercepted by its own interceptor")
    void nestedInterceptor() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(WatchInterceptor.class)
                .addBeanClass(Watchman.class)
                .addBeanClass(GuardInterceptor.class)
                .addBeanClass(GuardedService.class)
                .build()) {

            assertEquals("entered", container.select(GuardedService.class).enter());
            assertTrue(CALLS.contains("watched:look"), CALLS::toString);
            assertTrue(CALLS.contains("guard:looked"), CALLS::toString);
        }
    }

    @Test
    @DisplayName("an interceptor created while another one is created is injected and intercepts its bean")
    void nestedInterceptorInAChain() {
        STURDY_REFUSALS.set(1);
        TRUSTING_REFUSALS.set(1);
        try (var container = VaubanContainer.builder()
                .addBeanClass(PrefixLabel.class)
                .addBeanClass(Clock.class)
                .addBeanClass(CountingInterceptor.class)
                .addBeanClass(SturdyInterceptor.class)
                .addBeanClass(SturdyHelper.class)
                .addBeanClass(TrustingInterceptor.class)
                .addBeanClass(TrustingService.class)
                .build()) {

            assertEquals("trusted", container.select(TrustingService.class).work());
            assertTrue(CALLS.contains("sturdy:help"), CALLS::toString);
            assertTrue(CALLS.contains("trusting:helped"), CALLS::toString);
            assertTrue(CALLS.contains("prefix@now:work"), CALLS::toString);
        }
    }

    @Test
    @DisplayName("a nested interceptor that cannot be built fails with a CreationException")
    void nestedInterceptorThatCannotBeCreated() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(PrefixLabel.class)
                .addBeanClass(Clock.class)
                .addBeanClass(CountingInterceptor.class)
                .addBeanClass(FragileInterceptor.class)
                .addBeanClass(FragileHelper.class)
                .addBeanClass(CarefulInterceptor.class)
                .addBeanClass(CountedService.class)
                .build()) {

            var thrown = assertThrows(RuntimeException.class, () -> container.select(CountedService.class));

            assertTrue(causeChain(thrown).contains("nested interceptor cannot be built"), causeChain(thrown));
        }
    }

    @Test
    @DisplayName("a bean with its own @AroundInvoke is intercepted even when the deployment has no interceptor")
    void targetClassAroundInvoke() {
        try (var container = VaubanContainer.builder().addBeanClass(SelfIntercepted.class).build()) {

            assertEquals("self", container.select(SelfIntercepted.class).work());
            assertEquals(List.of("self:work"), List.copyOf(CALLS));
        }
    }

    @Test
    @DisplayName("the intercepted factory creates an instance with or without contexts, and rejects foreign arguments")
    void interceptedFactoryCalledDirectly() {
        try (var container = VaubanContainer.builder().addBeanClass(SelfIntercepted.class).build()) {
            var factory = container.findManagedBeanByExactClass(SelfIntercepted.class).factory();
            var tooManyArguments = VaubanInvocationContext.dummy(new Object[]{"one", "two"});

            var plain = assertInstanceOf(SelfIntercepted.class, factory.create());
            var withConstructContext = assertInstanceOf(SelfIntercepted.class,
                    ((io.vidocq.vauban.core.BeanFactory<Object>) factory).create(VaubanInvocationContext.dummy()));
            assertEquals("self", plain.work());
            assertNotNull(withConstructContext);
            assertThrows(RuntimeException.class,
                    () -> ((io.vidocq.vauban.core.BeanFactory<Object>) factory).create(tooManyArguments));
        }
    }

    @Test
    @DisplayName("an @AroundConstruct interceptor that throws a checked exception fails with a CreationException")
    void aroundConstructCheckedException() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(RefusingInterceptor.class)
                .addBeanClass(RefusedService.class)
                .build()) {

            var thrown = assertThrows(CreationException.class, () -> container.select(RefusedService.class));

            assertTrue(causeChain(thrown).contains("construction refused"), causeChain(thrown));
        }
    }

    @Test
    @DisplayName("an intercepted bean gets its @Inject constructor arguments")
    void interceptedConstructorInjection() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(PrefixLabel.class)
                .addBeanClass(Clock.class)
                .addBeanClass(CountingInterceptor.class)
                .addBeanClass(TimedService.class)
                .build()) {

            assertEquals("now", container.select(TimedService.class).time());
            assertEquals(List.of("prefix@now:time"), List.copyOf(CALLS));
        }
    }

    @Test
    @DisplayName("a bean with nothing to intercept, in a deployment without interceptors, is left as it is")
    void nothingToIntercept() {
        try (var container = VaubanContainer.builder().addBeanClass(PlainChild.class).build()) {
            var child = container.select(PlainChild.class);

            assertEquals("base", child.base());
            assertEquals(PlainChild.class, child.getClass());
        }
    }

    @Test
    @DisplayName("an intercepted bean with no interceptor binding calls its @PreDestroy directly")
    void preDestroyWithoutBindings() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(PrefixLabel.class)
                .addBeanClass(Clock.class)
                .addBeanClass(CountingInterceptor.class)
                .addBeanClass(SelfInterceptedFarewell.class)
                .build()) {
            var instance = container.getBeanManager().createInstance().select(SelfInterceptedFarewell.class);
            var bean = instance.get();

            assertEquals("self", bean.work());
            instance.destroy(bean);

            assertEquals(List.of("farewell"), List.copyOf(CALLS));
        }
    }

    private static String causeChain(Throwable thrown) {
        var text = new StringBuilder();
        for (var t = thrown; t != null; t = t.getCause()) {
            text.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        return text.toString();
    }
}
