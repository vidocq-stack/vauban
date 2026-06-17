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
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Singleton;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: the proxyability constraints (final class / final method / private no-arg
 * constructor) apply ONLY to beans that are actually intercepted. A {@code final},
 * non-intercepted bean that merely coexists with interceptors elsewhere in the deployment
 * must boot.
 *
 * <p>This is exactly the shape of a generated {@code @Named @Singleton} DataSource holder in a
 * Mansart app that also enables interceptors: the holder carries no interceptor binding and is a
 * pseudo-scoped (so unproxied) {@code @Singleton}, hence being {@code final} is perfectly legal
 * CDI. Before the fix, Vauban's interceptor wrapper rejected it at boot with
 * {@code DefinitionException("... with interceptor bindings must not be final")} simply because the
 * deployment contained interceptors.</p>
 */
@DisplayName("Interceptors: proxyability constraints bind to intercepted beans only")
class InterceptedDeploymentFinalBeanTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Logged {
    }

    @Logged
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class LoggingInterceptor {
        static final List<String> calls = new ArrayList<>();

        @AroundInvoke
        public Object intercept(InvocationContext ctx) throws Exception {
            calls.add(ctx.getMethod().getName());
            return ctx.proceed();
        }
    }

    /** Genuinely intercepted: makes the deployment "have interceptors" and exercises wrapping. */
    @Logged
    @ApplicationScoped
    public static class InterceptedService {
        public String doWork() {
            return "done";
        }
    }

    /**
     * FINAL, {@code @Singleton}, NO interceptor binding — the shape of a generated DataSource
     * holder. A {@code @Singleton} is a pseudo-scope, so it is never client-proxied; being final is
     * legal. Pre-fix this threw {@code DefinitionException} only because the deployment had
     * interceptors.
     */
    @Singleton
    public static final class FinalUnrelatedSingleton {
        public String ping() {
            return "pong";
        }
    }

    @BeforeEach
    void reset() {
        LoggingInterceptor.calls.clear();
    }

    @Test
    @DisplayName("A final non-intercepted @Singleton boots alongside interceptors and stays usable")
    void finalNonInterceptedBeanBootsAlongsideInterceptors() {
        var initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(LoggingInterceptor.class, InterceptedService.class, FinalUnrelatedSingleton.class);

        try (SeContainer container = initializer.initialize()) {
            // The final, non-intercepted bean must be resolvable and usable (not rejected, not wrapped).
            var holderLike = container.select(FinalUnrelatedSingleton.class).get();
            assertEquals("pong", holderLike.ping(), "final non-intercepted @Singleton must stay usable");

            // The genuinely intercepted bean must STILL be intercepted (no regression).
            var service = container.select(InterceptedService.class).get();
            assertEquals("done", service.doWork());
            assertTrue(LoggingInterceptor.calls.contains("doWork"),
                    "the genuinely intercepted bean must still be wrapped; calls=" + LoggingInterceptor.calls);
        }
    }
}
