package io.vidocq.vauban.core.interceptor;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for custom interceptor bindings declared via BCE
 * ({@code MetaAnnotations.addInterceptorBinding(...)}).
 *
 * <p>Boots a full {@link SeContainer} with:</p>
 * <ul>
 *   <li>An annotation {@link AuditBinding} that does <strong>not</strong> carry the
 *       {@code @InterceptorBinding} meta-annotation.</li>
 *   <li>A BCE {@link AuditBindingBce} that declares {@code AuditBinding} as an
 *       interceptor binding through {@link MetaAnnotations#addInterceptorBinding}.</li>
 *   <li>An interceptor {@link AuditInterceptor} bound by {@code @AuditBinding}.</li>
 *   <li>A bean {@link AuditedService} annotated with {@code @AuditBinding} that
 *       calls a business method.</li>
 * </ul>
 *
 * <p>Asserts: interception happens AND {@link InvocationContext#getInterceptorBindings()}
 * exposes the custom binding to the interceptor (the bug this regression hardens).</p>
 */
@DisplayName("Custom interceptor binding — end-to-end via BCE @Discovery")
class CustomInterceptorBindingE2ETest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface AuditBinding {
        // Intentionally NOT annotated with @InterceptorBinding —
        // declared as interceptor binding via BCE only.
    }

    /** BCE: declares {@code AuditBinding} as an interceptor binding. */
    public static class AuditBindingBce implements BuildCompatibleExtension {
        @Discovery
        public void registerBindings(MetaAnnotations meta) {
            meta.addInterceptorBinding(AuditBinding.class);
        }
    }

    @AuditBinding
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class AuditInterceptor {
        static final List<String> calls = new ArrayList<>();
        static final AtomicReference<List<String>> bindingNamesSeen = new AtomicReference<>(List.of());

        @AroundInvoke
        public Object intercept(InvocationContext ctx) throws Exception {
            calls.add(ctx.getMethod().getName());
            var seen = new ArrayList<String>();
            for (var ann : ctx.getInterceptorBindings()) {
                seen.add(ann.annotationType().getName());
            }
            bindingNamesSeen.set(List.copyOf(seen));
            return ctx.proceed();
        }
    }

    @AuditBinding
    @ApplicationScoped
    public static class AuditedService {
        public String doWork() {
            return "done";
        }
    }

    @BeforeEach
    void reset() {
        AuditInterceptor.calls.clear();
        AuditInterceptor.bindingNamesSeen.set(List.of());
    }

    @Test
    @DisplayName("Custom binding declared via BCE triggers interception and exposes its annotation")
    void customBindingFromBceIsHonoredEndToEnd() {
        var initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(AuditedService.class, AuditInterceptor.class, AuditBindingBce.class);

        try (SeContainer container = initializer.initialize()) {
            var service = container.select(AuditedService.class).get();
            assertEquals("done", service.doWork(), "Target method must return its normal value");
        }

        assertFalse(AuditInterceptor.calls.isEmpty(),
                "Interceptor must be invoked for a bean annotated with a BCE-declared custom binding");
        assertTrue(AuditInterceptor.calls.contains("doWork"),
                "doWork() invocation must be intercepted, got: " + AuditInterceptor.calls);

        var bindingNames = AuditInterceptor.bindingNamesSeen.get();
        assertTrue(bindingNames.contains(AuditBinding.class.getName()),
                "InvocationContext.getInterceptorBindings() must expose @AuditBinding to the interceptor; got: " + bindingNames);
    }
}

