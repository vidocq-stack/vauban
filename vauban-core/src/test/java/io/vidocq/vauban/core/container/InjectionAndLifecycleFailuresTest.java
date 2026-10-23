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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Injection and lifecycle edges: static members are never injected, an overridden initializer runs only as the
 * subclass declares it, a failing initializer or lifecycle interceptor says so, and a failing {@code @PreDestroy}
 * interceptor is suppressed. Also the qualifiers an event takes from a set of annotations.
 */
@DisplayName("BeanInjector, BeanLifecycle, ManagedBean and QualifierHelper: injection and lifecycle edges")
class InjectionAndLifecycleFailuresTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @Dependent
    public static class Clock {
    }

    @Dependent
    public static class StaticMembers {
        @Inject
        static Clock sharedClock;

        @Inject
        Clock clock;

        @Inject
        static void initShared(Clock clock) {
            CALLS.add("static initializer");
        }
    }

    @Dependent
    public static class Parent {
        @Inject
        public void init(Clock clock) {
            CALLS.add("parent init");
        }
    }

    /** Overrides the parent's initializer without {@code @Inject}: neither runs. */
    @Dependent
    public static class Child extends Parent {
        @Override
        public void init(Clock clock) {
            CALLS.add("child init");
        }
    }

    @Dependent
    public static class FailingInitializer {
        @Inject
        void init(Clock clock) {
            throw new IllegalStateException("initializer failure");
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Tracked {
    }

    @Tracked
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class LifecycleInterceptor {
        @PostConstruct
        public Object postConstruct(InvocationContext context) throws Exception {
            throw new IOException("post construct interception failed");
        }
    }

    @Tracked
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class DestroyInterceptor {
        @PreDestroy
        public Object preDestroy(InvocationContext context) throws Exception {
            CALLS.add("pre destroy interceptor");
            throw new IOException("pre destroy interception failed");
        }
    }

    @Tracked
    @Dependent
    public static class TrackedBean {
    }

    @Dependent
    public static class Untracked {
        @PreDestroy
        void bye() {
            CALLS.add("untracked bye");
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    public @interface Urgent {
    }

    public static class AnnotationSource {
        @Inject
        @Urgent
        @Deprecated
        public Object qualified;

        @Inject
        public Object plain;
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    @Test
    @DisplayName("static fields and methods are never injected")
    void staticMembersAreNotInjected() {
        try (var container = VaubanContainer.builder().addBeanClass(Clock.class).addBeanClass(StaticMembers.class).build()) {
            var bean = container.select(StaticMembers.class);

            assertNull(StaticMembers.sharedClock);
            assertTrue(bean.clock != null);
            assertEquals(List.of(), CALLS);
        }
    }

    @Test
    @DisplayName("an initializer the subclass overrides without @Inject is not called")
    void overriddenInitializer() {
        try (var container = VaubanContainer.builder().addBeanClass(Clock.class).addBeanClass(Child.class).build()) {
            container.select(Child.class);

            assertEquals(List.of(), CALLS);
        }
    }

    @Test
    @DisplayName("an initializer that throws fails the creation, naming the method")
    void failingInitializer() {
        try (var container = VaubanContainer.builder().addBeanClass(Clock.class)
                .addBeanClass(FailingInitializer.class).build()) {
            var thrown = assertThrows(RuntimeException.class, () -> container.select(FailingInitializer.class));

            assertTrue(causeChain(thrown).contains("Failed to call initializer method: init"), causeChain(thrown));
            assertTrue(causeChain(thrown).contains("initializer failure"), causeChain(thrown));
        }
    }

    @Test
    @DisplayName("a @PostConstruct interceptor that throws a checked exception fails the creation")
    void failingPostConstructInterceptor() {
        try (var container = VaubanContainer.builder().addBeanClass(LifecycleInterceptor.class)
                .addBeanClass(TrackedBean.class).build()) {
            var thrown = assertThrows(RuntimeException.class, () -> container.select(TrackedBean.class));

            assertTrue(causeChain(thrown).contains("post construct interception failed"), causeChain(thrown));
        }
    }

    @Test
    @DisplayName("a @PreDestroy interceptor that throws is suppressed; an unbound bean's @PreDestroy runs as usual")
    void failingPreDestroyInterceptor() {
        try (var container = VaubanContainer.builder().addBeanClass(DestroyInterceptor.class)
                .addBeanClass(TrackedBean.class).addBeanClass(Untracked.class).build()) {
            var tracked = container.getBeanManager().createInstance().select(TrackedBean.class);
            var untracked = container.getBeanManager().createInstance().select(Untracked.class);

            tracked.destroy(tracked.get());
            untracked.destroy(untracked.get());

            assertEquals(List.of("pre destroy interceptor", "untracked bye"), CALLS);
        }
    }

    @Test
    @DisplayName("event qualifiers keep @Default, @Any and qualifiers, and drop @Inject and other annotations")
    void eventQualifiers() throws NoSuchFieldException {
        var qualified = AnnotationSource.class.getField("qualified").getAnnotations();
        var plain = AnnotationSource.class.getField("plain").getAnnotations();
        var withDefault = new Annotation[]{Default.Literal.INSTANCE};

        var fromQualified = QualifierHelper.collectEventQualifiers(qualified);
        var fromAny = QualifierHelper.collectEventQualifiers(new Annotation[]{Any.Literal.INSTANCE});

        var qualifiedTypes = java.util.Arrays.stream(fromQualified).map(Annotation::annotationType).toList();
        assertTrue(qualifiedTypes.contains(Urgent.class), qualifiedTypes::toString);
        assertTrue(qualifiedTypes.stream().noneMatch(t -> t == Inject.class || t == Deprecated.class),
                qualifiedTypes::toString);
        assertArrayEquals(new Annotation[]{Any.Literal.INSTANCE}, fromAny);
        assertTrue(java.util.Arrays.stream(QualifierHelper.collectEventQualifiers(plain))
                .noneMatch(a -> a.annotationType() == Inject.class));
        assertTrue(java.util.Arrays.asList(QualifierHelper.collectEventQualifiers(withDefault))
                .contains(Default.Literal.INSTANCE));
    }

    private static String causeChain(Throwable thrown) {
        var text = new StringBuilder();
        for (var t = thrown; t != null; t = t.getCause()) {
            text.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        return text.toString();
    }
}
