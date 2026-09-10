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
/**
 * Bean archive B: declares {@code @Produces @ApplicationScoped it.liba.Foo}. Compiled with the
 * Vauban APT, which generates {@code it.beanb._VaubanComponents} (declared via {@code provides}
 * below) for its own managed beans. The produced type {@code Foo} lives in {@code it.liba},
 * a module that opens nothing: today its client proxy falls to runtime generation and demands
 * {@code opens it.liba to io.vidocq.vauban.core}. Stage 1 (#42) makes that proxy build-time.
 */
module it.beanb {
    requires io.vidocq.vauban.core;
    requires it.liba;

    requires jakarta.cdi;
    requires jakarta.inject;

    exports it.beanb;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with it.beanb._VaubanComponents;
}
