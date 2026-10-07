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

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A top-level, normal-scoped bean whose own name contains {@code $}, so that the
 * processor cannot find it by rewriting each {@code $} of its binary name to {@code .}
 * (BUG-20261004-11). Its client proxy has to forward the method it inherits from its superclass
 * and the interface default method it inherits.
 */
@ApplicationScoped
public class Dollar$SelfNamingService extends SelfNamingBase implements SelfNaming {

    public String own() {
        return getClass().getName();
    }
}
