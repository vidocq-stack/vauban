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
package io.vidocq.vauban.moduleit.foreign;

/**
 * Class methods whose descriptors name {@link HiddenArgument}, package-private here: a bean of
 * another package inherits {@code take(HiddenArgument)} and {@code give()}, which no Java source of
 * its package can declare (BUG-20261004-09, {@code n3b}, {@code n3d}). A class file can: the JVM
 * checks no access to the classes a method descriptor names.
 */
public class HiddenTaker {

    public String take(HiddenArgument argument) {
        return getClass().getSimpleName() + " took " + argument;
    }

    public HiddenArgument give() {
        return new HiddenArgument();
    }

    /** {@code taker.take(…)} as this package writes it, with an argument only it can make. */
    public static String callTake(HiddenTaker taker) {
        return taker.take(new HiddenArgument());
    }

    /** {@code taker.give()}, as this package writes it. */
    public static String callGive(HiddenTaker taker) {
        return String.valueOf(taker.give());
    }
}
