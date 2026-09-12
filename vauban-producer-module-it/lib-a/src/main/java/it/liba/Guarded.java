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
 * A produced type whose overridable member is {@code protected}. A sibling of its own package
 * ({@link GuardDesk}) invokes it on instances handed to it — legal inside the package, forbidden
 * from outside by
 * <a href="https://docs.oracle.com/javase/specs/jls/se25/html/jls-6.html#jls-6.6.2">JLS §6.6.2</a>,
 * which is why the proxy must live in {@code it.liba} and forward it rather than merely inherit it.
 *
 * <p>State, on purpose: the answer derives from it. A proxy that inherits the member instead of
 * forwarding it runs against its own default-initialised field and answers {@code clearance:null},
 * so the assertion discriminates.
 */
public class Guarded {

    private final String token;

    public Guarded(String token) {
        this.token = token;
    }

    public String run() {
        return "guarded:" + token;
    }

    protected String clearance() {
        return "clearance:" + token;
    }
}
