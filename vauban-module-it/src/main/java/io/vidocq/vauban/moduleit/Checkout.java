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

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

/**
 * The three ways a bean receives an injected value, each qualified by {@link Channel}: a field, a
 * constructor parameter and an initializer-method parameter. The container must resolve all three
 * without reading the qualifier back — under {@code -Dvauban.annotations.reflection=forbid} it
 * throws if it does.
 */
@Dependent
public class Checkout {

    /** Writes every binding member out, plus a {@code @Nonbinding} one the bean does not share. */
    @Inject
    @Channel(value = "wire", tier = Tier.PRIORITY, note = "at the injection point")
    Payment viaField;

    private final Payment viaConstructor;

    private Payment viaInitializer;

    /** Qualified by the members' defaults alone, written nowhere. */
    @Inject
    public Checkout(@Channel Payment viaConstructor) {
        this.viaConstructor = viaConstructor;
    }

    @Inject
    void setLate(@Channel(value = "wire", tier = Tier.PRIORITY) Payment payment) {
        this.viaInitializer = payment;
    }

    public String fromField() {
        return viaField.id();
    }

    public String fromConstructor() {
        return viaConstructor.id();
    }

    public String fromInitializer() {
        return viaInitializer.id();
    }
}
