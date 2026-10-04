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

import io.vidocq.vauban.moduleit.foreign.HiddenArgumentBase;
import jakarta.enterprise.context.Dependent;

/**
 * Inherits a shadowed default whose member signature, {@code label(HiddenArgument)}, names a type
 * this package cannot: the processor leaves that method out of the rendered subclass rather than
 * breaking the build; {@link #own()} is intercepted as usual (BUG-20261004-08).
 */
@Dependent
@Audited
public class HiddenArgumentService extends HiddenArgumentBase {

    public String own() {
        return "own";
    }
}
