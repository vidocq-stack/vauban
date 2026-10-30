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
package io.vidocq.vauban.moduleit.internal;

import io.vidocq.vauban.moduleit.Guarded;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundConstruct;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An interceptor whose interceptor methods are package-private, in a package this module neither exports nor opens
 * (VAU-INT-011): the container calls them through the package's generated {@code _VaubanComponents}, so none needs
 * reflection, hence no {@code opens} of this package to {@code io.vidocq.vauban.core}.
 */
@Guarded
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class GuardInterceptor {

    /** What each interceptor method saw, in call order. */
    public static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @AroundConstruct
    Object construct(InvocationContext ctx) throws Exception {
        CALLS.add("construct");
        return ctx.proceed();
    }

    @PostConstruct
    void created(InvocationContext ctx) throws Exception {
        CALLS.add("postConstruct");
        ctx.proceed();
    }

    @AroundInvoke
    Object guard(InvocationContext ctx) throws Exception {
        CALLS.add("invoke " + ctx.getMethod().getName());
        return ctx.proceed();
    }
}
