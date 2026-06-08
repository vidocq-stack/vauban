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
package io.vidocq.vauban.tck;

import jakarta.enterprise.context.spi.Context;
import org.jboss.cdi.tck.spi.Contexts;

public class VaubanContexts implements Contexts<Context> {
    @Override
    public void setActive(Context context) {
        // Activate the context if it's our RequestContext
        if (context instanceof io.vidocq.vauban.core.context.RequestContext rc) {
            rc.activate();
        }
    }

    @Override
    public void setInactive(Context context) {
        if (context instanceof io.vidocq.vauban.core.context.RequestContext rc) {
            rc.deactivate();
        }
    }

    @Override
    public Context getRequestContext() {
        var container = io.vidocq.vauban.core.container.VaubanContainer.current();
        if (container != null) {
            return container.requestContext();
        }
        // Fallback to ContainerHolder
        var holder = ContainerHolder.get();
        if (holder != null) {
            return holder.requestContext();
        }
        throw new IllegalStateException("No Vauban container is running");
    }

    @Override
    public Context getDependentContext() {
        return new io.vidocq.vauban.core.context.DependentContext();
    }

    @Override
    public void destroyContext(Context context) {
        if (context instanceof io.vidocq.vauban.core.context.ApplicationContext ac) {
            ac.deactivate();
        } else if (context instanceof io.vidocq.vauban.core.context.RequestContext rc) {
            rc.deactivate();
        }
    }
}
