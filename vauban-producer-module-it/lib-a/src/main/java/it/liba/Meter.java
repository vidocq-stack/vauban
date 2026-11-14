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

import java.util.function.Supplier;

/**
 * A public abstract class of a CDI-agnostic module: one abstract method of its own, one an interface leaves to it,
 * and a concrete one. CDI 4.1 §3.10 does not count an abstract class as unproxyable, so a normal-scoped producer of
 * it gets a build-time client proxy in its own package, as for {@link Foo} (BUG-20261114-02).
 */
public abstract class Meter implements Supplier<String> {

    protected Meter() {
    }

    public abstract int reading();

    public String unit() {
        return "kWh";
    }
}
