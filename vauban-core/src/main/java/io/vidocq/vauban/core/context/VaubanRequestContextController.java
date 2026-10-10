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

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.control.RequestContextController;

/**
 * The built-in {@link RequestContextController} (CDI 4.1 §6.5.8), a {@code @Dependent} bean.
 * {@link #activate} activates the request context on the current thread unless it is already
 * active; {@link #deactivate} deactivates it only if this controller activated it. A servlet
 * container calls them from its request listener (Vidocq/vauban#147).
 */
public final class VaubanRequestContextController implements RequestContextController {

    private final RequestContext requestContext;
    private boolean activator;

    public VaubanRequestContextController(RequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    public boolean activate() {
        if (requestContext.isActive()) {
            return false;
        }
        requestContext.activate();
        activator = true;
        return true;
    }

    @Override
    public void deactivate() throws ContextNotActiveException {
        if (!requestContext.isActive()) {
            throw new ContextNotActiveException("RequestScope is not active");
        }
        if (activator) {
            activator = false;
            requestContext.deactivate();
        }
    }
}
