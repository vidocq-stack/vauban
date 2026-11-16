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

import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.Serial;
import java.io.Serializable;

/**
 * A session bean that keeps, besides its injected {@code Instance}, one it obtained by {@code select(...)}: that one
 * has no injection point of its own to move by.
 */
@SessionScoped
public class Picker implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Inject
    Instance<Catalog> catalogs;

    private Instance<Catalog> picked;

    public void pick() {
        picked = catalogs.select(Default.Literal.INSTANCE);
    }

    public Instance<Catalog> picked() {
        return picked;
    }
}
