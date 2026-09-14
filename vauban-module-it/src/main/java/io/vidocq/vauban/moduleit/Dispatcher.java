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
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/** Holds the two injected facades whose qualifiers the container reads off the field. */
@Dependent
public class Dispatcher {

    @Inject
    @Any
    Instance<Payment> payments;

    @Inject
    @Channel("wire")
    Event<String> paid;

    /** Qualified by nothing: what it fires must not reach a qualified observer. */
    @Inject
    Event<String> anything;

    public String select(java.lang.annotation.Annotation qualifier) {
        return payments.select(qualifier).get().id();
    }

    public void fire(String message) {
        paid.fire(message);
    }

    public void fireAnything(String message) {
        anything.fire(message);
    }
}
