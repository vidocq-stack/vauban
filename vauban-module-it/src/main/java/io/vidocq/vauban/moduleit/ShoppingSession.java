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

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import java.io.Serial;
import java.io.Serializable;

/**
 * A session bean that holds two client proxies the processor rendered as source: the class-typed one of
 * {@link Till} and the interface-typed one of {@link Clerk}. A session that holds it moves to another container on the
 * module path, with no {@code opens}.
 */
@SessionScoped
public class ShoppingSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Inject
    Till till;

    @Inject
    Clerk clerk;

    private String owner = "nobody";

    public void owner(String owner) {
        this.owner = owner;
    }

    public String owner() {
        return owner;
    }

    public Till till() {
        return till;
    }

    public Clerk clerk() {
        return clerk;
    }
}
