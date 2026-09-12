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
package io.vidocq.vauban.example.cdi1015.app;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * The interceptor case. An intercepted normal-scoped bean carries <em>two</em> generated classes:
 * the client proxy the container hands out, and a {@code $$Intercepted} subclass — siblings, both
 * extending the bean — that runs the chain before the bean's own method. Both are emitted at build
 * time by the APT, in the bean's own package, so this costs no {@code opens} either.
 */
@Interceptor
@Audited
@Priority(Interceptor.Priority.APPLICATION)
public class AuditTrail {

    /** Marks the return value so a test can prove the chain really ran. */
    @AroundInvoke
    public Object around(InvocationContext context) throws Exception {
        Object result = context.proceed();
        return result instanceof String text ? "[audited] " + text : result;
    }
}
