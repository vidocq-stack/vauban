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
package io.vidocq.vauban.webcontexts;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Two {@code @SessionScoped} producers: a wallet, and the session object itself, as Foy produces its HttpSession. */
@Dependent
public class SessionProducers {

    /** The ids of the sessions the disposer saw, in order. */
    static final List<String> DISPOSED = new CopyOnWriteArrayList<>();

    @Produces
    @SessionScoped
    Wallet wallet() {
        return new Wallet();
    }

    /** The very session the bound store adapts. */
    @Produces
    @SessionScoped
    WebSession session() {
        return ((WebSessionStore) SessionContext.CURRENT.get()).session();
    }

    void close(@Disposes WebSession session) {
        DISPOSED.add(session.id());
    }
}
