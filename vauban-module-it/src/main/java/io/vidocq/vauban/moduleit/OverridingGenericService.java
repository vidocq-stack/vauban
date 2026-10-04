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
 * Overrides the generic {@link GenericBase#echo(Object)} with the type it binds: the override and
 * the inherited declaration are one member, {@code echo(String)}, which the processor's client
 * proxy must declare once (BUG-20261004-06).
 */
@ApplicationScoped
@Audited
public class OverridingGenericService extends GenericBase<String> {

    @Override
    public String echo(String value) {
        return "overridden " + value;
    }
}
