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
package io.vidocq.vauban.maven.generate.frozen;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;

import java.util.concurrent.atomic.AtomicInteger;

/** An extension whose work depends on the deployment only: the build freezes it. */
public class GreetingExtension implements BuildCompatibleExtension {

    /** How many times an extension was created: none at a start that loads what the build froze. */
    public static final AtomicInteger RUNS = new AtomicInteger();

    public GreetingExtension() {
        RUNS.incrementAndGet();
    }

    @Synthesis
    public void greeting(SyntheticComponents components) {
        components.addBean(Greeting.class).type(Greeting.class).scope(Dependent.class)
                .withParam("text", "hello from the build").createWith(GreetingCreator.class);
    }
}
