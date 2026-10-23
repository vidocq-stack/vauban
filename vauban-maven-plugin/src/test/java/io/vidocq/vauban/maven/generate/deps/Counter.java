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
package io.vidocq.vauban.maven.generate.deps;

import io.vidocq.vauban.maven.generate.paths.Gadget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

/** A normal-scoped bean of a scanned jar, with producers and a nested bean, none of which gets a provider. */
@ApplicationScoped
public class Counter {

    public int next() {
        return 1;
    }

    @Produces
    @Dependent
    Integer start() {
        return 0;
    }

    @Produces
    @RequestScoped
    Gadget gadget() {
        return new Gadget();
    }

    /** A nested bean of the jar. */
    @Dependent
    public static class Part {
    }
}
