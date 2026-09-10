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
package it.beanb;

import it.liba.Foo;
import it.liba.Gadget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * The producer bean: a normal-scoped {@code @Produces} of a class type from a CDI-agnostic
 * module. The producer holder itself is a managed bean in {@code it.beanb} (its proxy and
 * provider are generated in-module by the APT); only the produced {@code Foo} proxy is the
 * cross-module case.
 */
@ApplicationScoped
public class FooProducer {

    @Produces
    @ApplicationScoped
    public Foo produceFoo() {
        return new Foo();
    }

    /**
     * An ineligible produced type (Gadget has a package-private method): its proxy cannot be built at
     * compile time in this package, so it falls to runtime generation. Stage 3b opens {@code it.liba}
     * to the container at boot so that fallback resolves with zero hand-written --add-opens.
     */
    @Produces
    @ApplicationScoped
    public Gadget produceGadget() {
        return new Gadget();
    }
}
