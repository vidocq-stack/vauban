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
package io.vidocq.vauban.maven.generate.abstractmeter;

import java.util.function.Supplier;

/**
 * A produced abstract class of a dependency: one abstract method of its own, one an interface leaves to it, and a
 * concrete one (BUG-20261114-02).
 */
public abstract class Meter implements Supplier<String> {

    protected Meter() {
    }

    public abstract int reading();

    public String unit() {
        return "kWh";
    }

    /** The instance a producer returns. */
    public static Meter fixed() {
        return new Meter() {
            @Override
            public int reading() {
                return 42;
            }

            @Override
            public String get() {
                return "meter";
            }

            @Override
            public String unit() {
                return "MWh";
            }
        };
    }
}
