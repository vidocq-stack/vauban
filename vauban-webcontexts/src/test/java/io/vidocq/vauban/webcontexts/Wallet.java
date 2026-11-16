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

import java.io.Serial;
import java.io.Serializable;
import java.util.function.Supplier;

/**
 * Produced, never discovered: a {@code @SessionScoped} producer method of {@link SessionProducers} makes it, so its
 * bean class is the producer's class, not this one. One of its bean types is parameterized ({@code Supplier<Integer>}).
 */
public class Wallet implements Serializable, Supplier<Integer> {

    @Serial
    private static final long serialVersionUID = 1L;

    private int coins;

    public int add(int more) {
        coins += more;
        return coins;
    }

    @Override
    public Integer get() {
        return coins;
    }
}
