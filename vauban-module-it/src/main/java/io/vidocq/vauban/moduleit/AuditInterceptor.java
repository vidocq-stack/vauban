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
package io.vidocq.vauban.moduleit;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@code @Audited} interceptor: VAU-INT-004 regression — an {@code @Interceptor} bean
 * must be emitted into {@code _VaubanComponents} so the container instantiates it
 * in-module, with no {@code opens} for its package.
 *
 * <p>Records {@code "<method>/<paramCount>"} for each intercepted call so the test can
 * assert that every overload was routed through its own glue (VAU-INT-001).</p>
 */
@Audited
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class AuditInterceptor {

    /** Visible trace of intercepted calls, as {@code "<method>/<paramCount>"}. */
    public static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @AroundInvoke
    public Object audit(InvocationContext ctx) throws Exception {
        CALLS.add(ctx.getMethod().getName() + "/" + ctx.getParameters().length);
        return ctx.proceed();
    }
}
