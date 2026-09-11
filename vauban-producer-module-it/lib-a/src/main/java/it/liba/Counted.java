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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The vauban#24 reporter's shape, as a third-party produced type: a constructor with a side
 * effect (here, a counter — in the wild, a pool, a migration, a realm import). A client proxy that
 * chains this constructor runs the side effect on a throwaway object; the placed proxy, retargeted
 * onto the entry constructor the loader weaves in, must not. The package-private member is what
 * puts this type on the placement path in the first place.
 */
public class Counted {

    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    public Counted() {
        CONSTRUCTIONS.incrementAndGet();
    }

    public static int constructions() {
        return CONSTRUCTIONS.get();
    }

    public String value() {
        return "counted";
    }

    String internalOnly() {
        return "internal";
    }
}
