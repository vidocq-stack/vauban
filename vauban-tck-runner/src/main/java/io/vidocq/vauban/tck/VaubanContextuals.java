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
import jakarta.enterprise.context.spi.CreationalContext;
import org.jboss.cdi.tck.spi.Contextuals;

public class VaubanContextuals implements Contextuals {

    @Override
    public <T> Contextuals.Inspectable<T> create(T instance, Context context) {
        return new Contextuals.Inspectable<>() {
            private CreationalContext<T> creationalContextPassedToCreate;
            private T instancePassedToDestroy;
            private CreationalContext<T> creationalContextPassedToDestroy;

            @Override
            public T create(CreationalContext<T> creationalContext) {
                this.creationalContextPassedToCreate = creationalContext;
                return instance;
            }

            @Override
            public void destroy(T inst, CreationalContext<T> creationalContext) {
                this.instancePassedToDestroy = inst;
                this.creationalContextPassedToDestroy = creationalContext;
            }

            @Override
            public CreationalContext<T> getCreationalContextPassedToCreate() {
                return creationalContextPassedToCreate;
            }

            @Override
            public T getInstancePassedToDestroy() {
                return instancePassedToDestroy;
            }

            @Override
            public CreationalContext<T> getCreationalContextPassedToDestroy() {
                return creationalContextPassedToDestroy;
            }
        };
    }
}
