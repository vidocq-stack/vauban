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
 * A CDI SE application, on the module path, with <strong>zero {@code opens}</strong>. It produces
 * {@code PaymentGateway} (a class) and {@code AuditLog} (an interface) from the CDI-agnostic
 * {@code io.vidocq.vauban.example.cdi1015.lib} module — the jakartaee/cdi#1015 case. Vauban's APT
 * has generated the client proxies in <em>this</em> module's package at compile time, so the
 * container resolves everything without any {@code --add-opens} and without runtime reflection. The
 * {@code provides} line hands the container the generated in-module component provider.
 */
module io.vidocq.vauban.example.cdi1015.app {
    requires io.vidocq.vauban.core;
    requires io.vidocq.vauban.example.cdi1015.lib;

    requires jakarta.cdi;
    requires jakarta.inject;

    exports io.vidocq.vauban.example.cdi1015.app;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.vauban.example.cdi1015.app._VaubanComponents;
}
