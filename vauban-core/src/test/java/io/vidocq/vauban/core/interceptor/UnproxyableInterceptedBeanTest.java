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

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An intercepted bean must be proxyable; when it is not, the deployment fails (CDI 4.1 §3.10: a
 * deployment problem) and says why the bean is intercepted. A bean bound only through a method it
 * inherits — from a superclass or as an interface default method — carries no binding in its own
 * source, so without that the error would point nowhere.
 */
@DisplayName("Unproxyable intercepted bean — deployment problem that says why it is intercepted")
class UnproxyableInterceptedBeanTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Marked {
    }

    @Marked
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class MarkingInterceptor {
        @AroundInvoke
        public Object mark(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    public interface MarkedDefault {
        @Marked
        default String marked() {
            return "marked";
        }
    }

    /** Final, and bound only through the default method it inherits. */
    @Dependent
    public static final class FinalBoundByADefaultMethod implements MarkedDefault {
    }

    public static class MarkedBase {
        @Marked
        public String marked() {
            return "marked";
        }
    }

    /** A final method, and bound only through the superclass method it inherits. */
    @Dependent
    public static class FinalMethodBoundBySuperclass extends MarkedBase {
        public final String pinned() {
            return "pinned";
        }
    }

    private static DeploymentException deploymentFailure(Class<?> beanClass) {
        return assertThrows(DeploymentException.class, () -> {
            try (var container = VaubanContainer.builder()
                    .addBeanClass(MarkingInterceptor.class)
                    .addBeanClass(beanClass)
                    .build()) {
                // the deployment must not succeed
            }
        });
    }

    private static void assertNames(String message, String... parts) {
        for (var part : parts) {
            assertTrue(message.contains(part), "the message must name " + part + ", got: " + message);
        }
    }

    @Test
    @DisplayName("a final class bound through an inherited default method")
    void finalClass() {
        var message = String.valueOf(deploymentFailure(FinalBoundByADefaultMethod.class).getMessage());
        assertNames(message, FinalBoundByADefaultMethod.class.getName(), "final",
                MarkedDefault.class.getName() + ".marked()", "@" + Marked.class.getName());
    }

    @Test
    @DisplayName("a final method on a bean bound through an inherited superclass method")
    void finalMethod() {
        var message = String.valueOf(deploymentFailure(FinalMethodBoundBySuperclass.class).getMessage());
        assertNames(message, FinalMethodBoundBySuperclass.class.getName(), "pinned",
                MarkedBase.class.getName() + ".marked()", "@" + Marked.class.getName());
    }
}
