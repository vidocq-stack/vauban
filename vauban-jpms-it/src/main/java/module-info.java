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
 * Module-path regression vehicle for Vauban: a strict named module with <strong>zero
 * {@code opens}</strong> whose beans cover the historical JPMS bug surface —
 * overloaded intercepted methods (VAU-INT-001), primitive-array parameters (VAU-INT-002),
 * checked exceptions through the source-rendered subclass (VAU-INT-003), {@code @Interceptor}
 * beans instantiated in-module (VAU-INT-004), marker interceptor bindings detected by name
 * (VAU-INT-005) and nested-class types in proxy descriptors (VAU-PRX-003).
 *
 * <p>Everything is wired at build time by the Vauban APT: the {@code $$Intercepted}
 * subclass, the {@code _ClientProxy} and the {@code _VaubanComponents} provider declared
 * below. Surefire runs the test on the MODULE PATH because this module-info exists — the
 * class-path test suite cannot see these bugs (opens/exports are not enforced there).</p>
 */
module io.vidocq.vauban.jpmsit {
    requires io.vidocq.vauban.core;

    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.vauban.jpmsit;

    // Build-time, in-module instantiation + field injection of every bean and interceptor —
    // the container needs no `opens … to io.vidocq.vauban.core`.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.vauban.jpmsit._VaubanComponents;
}
