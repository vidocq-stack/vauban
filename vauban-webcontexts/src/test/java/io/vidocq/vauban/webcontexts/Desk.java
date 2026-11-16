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

import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.io.Serial;
import java.io.Serializable;

/**
 * A session bean that injects the built-in beans, as real session beans do: an {@code Event}, an {@code Instance}
 * and the {@code BeanManager} in fields, and a qualified {@code Event} through an initializer method. Each moves with
 * the session to the importing container.
 */
@SessionScoped
public class Desk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Inject
    Event<Signal> signals;

    @Inject
    Instance<Catalog> catalogs;

    @Inject
    BeanManager beanManager;

    private Event<Signal> urgent;

    private String owner = "nobody";

    @Inject
    void urgentSignals(@Urgent Event<Signal> urgent) {
        this.urgent = urgent;
    }

    public void owner(String owner) {
        this.owner = owner;
    }

    public String owner() {
        return owner;
    }

    public Event<Signal> signals() {
        return signals;
    }

    public Event<Signal> urgent() {
        return urgent;
    }

    public Instance<Catalog> catalogs() {
        return catalogs;
    }

    public BeanManager beanManager() {
        return beanManager;
    }
}
