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
package it.liba;

/**
 * A produced type with a package-private member that a sibling of its own package
 * ({@link PooledPool}) invokes on instances handed to it. Its proxy must therefore live in
 * {@code it.liba} and forward that member; in a Vauban layer the loader places it there.
 *
 * <p>State, on purpose: both methods derive their answer from it. A proxy that inherits a member
 * instead of forwarding it runs against its own default-initialised field and answers
 * {@code null}, so a test asserting the value actually discriminates.
 */
public class Pooled {

    private final String token;

    public Pooled(String token) {
        this.token = token;
    }

    public String run() {
        return "pooled:" + token;
    }

    String internalOnly() {
        return "internal:" + token;
    }
}
