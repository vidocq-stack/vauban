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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code InvocationContext.getInterceptorBindings()} on a business method is worked out once per class and method:
 * an interceptor that asks for it (Mansart's {@code @Transactional}) used to read the class's and the method's
 * annotations again on every intercepted call (VAU-PERF-004).
 */
class InterceptorBindingsCacheTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Audited {
    }

    @Audited
    static class Service {
        @Audited
        void work() { /* no behaviour needed */ }
    }

    @Test
    void theBindingsOfAMethodAreWorkedOutOnce() throws Exception {
        var method = Service.class.getDeclaredMethod("work");
        var target = new Service();

        var first = new VaubanInvocationContext(target, method, new Object[0], List.of()).getInterceptorBindings();
        var second = new VaubanInvocationContext(target, method, new Object[0], List.of()).getInterceptorBindings();

        assertSame(first, second);
        assertEquals(1, first.size());
        assertEquals(Audited.class, first.iterator().next().annotationType());
    }
}
