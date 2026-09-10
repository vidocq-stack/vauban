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
package it.liba;

/**
 * An ineligible produced type: a public class with a package-private method, so a build-time proxy
 * placed in the producer's package cannot forward every client-visible method across the boundary
 * (PACKAGE_PRIVATE_VIRTUALS). Its proxy therefore falls to runtime generation, and Stage 3b opens
 * {@code it.liba} to the container at boot so that fallback works with zero hand-written --add-opens.
 */
public class Gadget {

    public String run() {
        return "gadget";
    }

    // Package-private, non-final: makes Gadget ineligible for a cross-package build-time proxy.
    String internalOnly() {
        return "internal";
    }
}
