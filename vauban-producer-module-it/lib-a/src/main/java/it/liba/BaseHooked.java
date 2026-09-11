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
 * A superclass carrying the package-private member, so that the produced type ({@code Hooked})
 * only <em>inherits</em> it. A proxy built from the produced type's declared members alone misses
 * it, and a sibling calling it on the proxy ({@code HookCaller}) then reads the proxy's own empty
 * state. The member derives its answer from state for exactly that reason.
 */
public class BaseHooked {

    private final String token;

    private static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The only constructor is a business one: no usable no-arg constructor, so the Vauban loader
     * has to weave this superclass as well for the placed proxy of {@code Hooked} to have a
     * side-effect-free chain.
     */
    protected BaseHooked(String token) {
        CONSTRUCTIONS.incrementAndGet();
        this.token = token;
    }

    /** How many times the business constructor ran — a placed proxy must not add to it (#24). */
    public static int constructions() {
        return CONSTRUCTIONS.get();
    }

    String hook() {
        return "hook:" + token;
    }
}
