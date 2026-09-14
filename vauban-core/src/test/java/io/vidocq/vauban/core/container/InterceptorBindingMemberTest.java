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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.util.Nonbinding;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Safety net for vauban#70: interceptor binding members. Each scenario boots only its own
 * interceptor and worker, and every business method returns its own name, so an interceptor that
 * bound too broadly or too narrowly shows up in {@link #CALLS}. A test disabled with a BUG id
 * reproduces a defect logged in {@code BUG.md}.
 */
@DisplayName("vauban#70 safety net: interceptor binding members")
class InterceptorBindingMemberTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Audited {
        String value();
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Traced {
        String value();

        @Nonbinding String note() default "";
    }

    /** An interceptor binding only through {@link MeteredBce}, which also makes {@code value} non-binding. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Metered {
        String value();

        String group();
    }

    @Audited("a") @Interceptor @Priority(Interceptor.Priority.APPLICATION)
    public static class AuditedInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("audited:" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @Dependent
    public static class AuditedWorker {
        @Audited("a") public String a() { return "a"; }

        @Audited("b") public String b() { return "b"; }
    }

    @Traced(value = "t", note = "interceptor") @Interceptor @Priority(Interceptor.Priority.APPLICATION)
    public static class TracedInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("traced:" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @Dependent
    public static class TracedWorker {
        @Traced(value = "t", note = "method") public String traced() { return "traced"; }
    }

    public static class MeteredBce implements BuildCompatibleExtension {
        @Discovery
        public void register(MetaAnnotations meta) {
            meta.addInterceptorBinding(Metered.class).methods().stream()
                    .filter(method -> method.info().name().equals("value"))
                    .forEach(method -> method.addAnnotation(Nonbinding.class));
        }
    }

    @Metered(value = "interceptor", group = "g") @Interceptor @Priority(Interceptor.Priority.APPLICATION)
    public static class MeteredInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.add("metered:" + context.getMethod().getName());
            return context.proceed();
        }
    }

    @Dependent
    public static class MeteredWorker {
        @Metered(value = "method", group = "g") public String metered() { return "metered"; }
    }

    /** Same non-binding {@code value} as the interceptor would accept, but a different binding {@code group}. */
    @Dependent
    public static class MeteredOtherGroupWorker {
        @Metered(value = "method", group = "other") public String metered() { return "metered"; }
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    private static VaubanContainer boot(Class<?>... classes) {
        var builder = VaubanContainer.builder();
        for (var type : classes) {
            builder.addBeanClass(type);
        }
        return builder.build();
    }

    @Test
    @DisplayName("a binding member value selects the interceptor")
    void memberValue() {
        try (var container = boot(AuditedInterceptor.class, AuditedWorker.class)) {
            var worker = container.select(AuditedWorker.class);
            assertEquals("a", worker.a());
            assertEquals("b", worker.b());
        }
        assertEquals(List.of("audited:a"), CALLS);
    }

    @Test
    @DisplayName("a @Nonbinding member does not take part in interceptor resolution")
    void nonbindingMember() {
        try (var container = boot(TracedInterceptor.class, TracedWorker.class)) {
            assertEquals("traced", container.select(TracedWorker.class).traced());
        }
        assertEquals(List.of("traced:traced"), CALLS);
    }

    /**
     * Green today only because no member of an extension-declared binding is compared at all
     * (BUG-20260914-08, proven by {@link #extensionBindingMemberValue()}). It guards the non-binding rule
     * once member values are compared.
     */
    @Test
    @DisplayName("a member made non-binding by an extension does not take part in interceptor resolution")
    void extensionNonbindingMember() {
        try (var container = boot(MeteredBce.class, MeteredInterceptor.class, MeteredWorker.class)) {
            assertEquals("metered", container.select(MeteredWorker.class).metered());
        }
        assertEquals(List.of("metered:metered"), CALLS);
    }

    @Test
    @DisplayName("control: the binding members an extension leaves binding still select the interceptor")
    void extensionBindingMemberValue() {
        try (var container = boot(MeteredBce.class, MeteredInterceptor.class, MeteredOtherGroupWorker.class)) {
            assertEquals("metered", container.select(MeteredOtherGroupWorker.class).metered());
        }
        assertEquals(List.of(), CALLS, "group is still binding, so group \"other\" must not bind the interceptor");
    }
}
