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
package io.vidocq.vauban.maven.generate.paths;

import io.vidocq.vauban.maven.enhance.fixture.Widget;
import io.vidocq.vauban.maven.generate.intercepted.Logged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import java.util.List;

/**
 * A normal-scoped, intercepted bean of the project whose producers cover each shape the plugin tells apart: a
 * proxyable class (twice, by a field and a method), an interface, a parameterized interface, a type the class loader cannot load, a type only
 * an in-package proxy can extend, and a {@code @Dependent} value.
 */
@Logged
@ApplicationScoped
public class Workshop {

    @Produces
    @RequestScoped
    Gadget spare = new Gadget();

    @Produces
    @ApplicationScoped
    Gadget gadget() {
        return new Gadget();
    }

    @Produces
    @ApplicationScoped
    Runnable task() {
        return this::open;
    }

    @Produces
    @ApplicationScoped
    Hidden hidden() {
        return new Hidden();
    }

    @Produces
    @ApplicationScoped
    Widget widget() {
        return new Widget();
    }

    @Produces
    @ApplicationScoped
    List<String> names() {
        return List.of("workshop");
    }

    @Produces
    String label() {
        return "workshop";
    }

    public String open() {
        return "open";
    }

    /** A nested bean: it keeps the reflective path, so no provider names it. */
    @Dependent
    public static class Bench {
    }
}
