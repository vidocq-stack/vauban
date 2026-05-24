package io.vidocq.vauban.core.interceptor;

import io.vidocq.vauban.core.container.VaubanBeanManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for custom interceptor bindings registered via BCE
 * ({@code MetaAnnotations.addInterceptorBinding(...)}).
 *
 * <p>Verifies that all runtime decision paths that determine "is this
 * annotation an interceptor binding" consult the custom registry
 * ({@link VaubanBeanManager#isCustomInterceptorBinding(Class)}) and
 * not only {@code @InterceptorBinding} meta-annotation presence.</p>
 *
 * <p>Prior bug: a custom binding declared via BCE was honored when
 * triggering interception (proxy creation, chain resolution) but the
 * resulting {@link jakarta.interceptor.InvocationContext#getInterceptorBindings()}
 * returned an empty set, breaking interceptors that need to read their
 * binding members (e.g. {@code @Retry(maxRetries = 3)}).</p>
 */
@DisplayName("Custom interceptor binding — runtime cross-cutting regression")
class CustomInterceptorBindingRegressionTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface AuditBinding {
        // No @InterceptorBinding meta-annotation: declared as binding via BCE only
    }

    @AuditBinding
    static class AuditedBean {
        public void doWork() { /* no-op */ }
    }

    @AfterEach
    void resetRegistry() {
        VaubanBeanManager.setCustomInterceptorBindingTypes(Set.of());
    }

    @Test
    @DisplayName("getInterceptorBindings() includes custom bindings registered via BCE")
    void getInterceptorBindings_includesCustomBindings() throws Exception {
        var bean = new AuditedBean();
        var method = AuditedBean.class.getMethod("doWork");

        // Before registration: custom binding is NOT recognized
        var ctxBefore = new VaubanInvocationContext(bean, method, new Object[0], List.of());
        assertTrue(ctxBefore.getInterceptorBindings().isEmpty(),
                "Sanity check: without registration, AuditBinding must not be detected");

        // After registration: custom binding IS recognized
        VaubanBeanManager.setCustomInterceptorBindingTypes(Set.of(AuditBinding.class));

        var ctxAfter = new VaubanInvocationContext(bean, method, new Object[0], List.of());
        var bindings = ctxAfter.getInterceptorBindings();
        assertEquals(1, bindings.size(),
                "Custom binding should be exposed via InvocationContext.getInterceptorBindings()");
        assertTrue(bindings.stream().anyMatch(a -> a.annotationType() == AuditBinding.class),
                "Returned binding set must contain @AuditBinding");
    }

    @Test
    @DisplayName("Custom registry is consulted by VaubanBeanManager.isCustomInterceptorBinding")
    void registryRoundTrip() {
        assertFalse(VaubanBeanManager.isCustomInterceptorBinding(AuditBinding.class));
        VaubanBeanManager.setCustomInterceptorBindingTypes(Set.of(AuditBinding.class));
        assertTrue(VaubanBeanManager.isCustomInterceptorBinding(AuditBinding.class));
        // Null-safe reset
        VaubanBeanManager.setCustomInterceptorBindingTypes(null);
        assertFalse(VaubanBeanManager.isCustomInterceptorBinding(AuditBinding.class));
    }

    @Test
    @DisplayName("explicit setInterceptorBindings still wins over derivation")
    void explicitBindingsTakePrecedence() throws Exception {
        VaubanBeanManager.setCustomInterceptorBindingTypes(Set.of(AuditBinding.class));
        var bean = new AuditedBean();
        var method = AuditedBean.class.getMethod("doWork");
        var ctx = new VaubanInvocationContext(bean, method, new Object[0], List.of());
        ctx.setInterceptorBindings(Collections.emptySet());
        assertTrue(ctx.getInterceptorBindings().isEmpty(),
                "Explicit empty set must short-circuit derivation");
    }
}

