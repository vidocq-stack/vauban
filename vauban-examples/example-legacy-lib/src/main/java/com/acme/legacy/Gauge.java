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
package com.acme.legacy;

/**
 * An ordinary library type, in a jar that has <strong>no</strong> {@code module-info}. On the module
 * path such a jar is an <em>automatic</em> module: its name is guessed from the file name, it reads
 * every other module, exports every package, and {@code jlink} refuses to link it.
 *
 * <p>Nothing here knows about CDI or about Vauban. That is the point: this is the shape of most of
 * the ecosystem, and {@code vauban:modularize} is what gives it a real descriptor.
 */
public class Gauge {

    private final String unit;

    public Gauge(String unit) {
        this.unit = unit;
    }

    public String describe(int value) {
        return value + " " + unit;
    }
}
