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
package io.vidocq.vauban.core.context;

import io.vidocq.vauban.core.container.VaubanBeanManager;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * The built-in interceptor of {@code @ActivateRequestContext} (CDI 4.1 §6.5.9), at priority
 * {@code PLATFORM_BEFORE + 100}: activates the request context around the intercepted call when it
 * is not active, with the context events, and deactivates it after. An active context, a request
 * served by Cassini for one, is left as it is. The container registers it only when a bean uses
 * the binding (Vidocq/vauban#147).
 *
 * <p>It works on the {@link RequestContext} directly, not through a {@code RequestContextController}:
 * one interceptor instance serves every call of its bean, from any thread, and the context keeps
 * one request per thread.</p>
 */
public final class ActivateRequestContextInterceptor {

    public static final int PRIORITY = Interceptor.Priority.PLATFORM_BEFORE + 100;

    /** The name of the {@code @AroundInvoke} method, for the interceptor's descriptor. */
    public static final String AROUND_INVOKE = "activateRequestContext";

    @Inject
    BeanManager beanManager;

    /** Built through its no-arg bean constructor (CDI 4.1 "Declaring a bean constructor"); the container injects the field. */
    public ActivateRequestContextInterceptor() {
        // Nothing to set up: the container injects the bean manager after construction.
    }

    @AroundInvoke
    public Object activateRequestContext(InvocationContext invocation) throws Exception {
        RequestContext requestContext = ((VaubanBeanManager) beanManager).requestContext();
        if (requestContext.isActive()) {
            return invocation.proceed();
        }
        requestContext.activate();
        try {
            return invocation.proceed();
        } finally {
            requestContext.deactivate();
        }
    }
}
