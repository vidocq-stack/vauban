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
package io.vidocq.vauban.indexer.codegen;

import java.util.List;

/**
 * Describes a managed class the generated {@code _VaubanComponents} provider can instantiate
 * in-module: either via a no-arg constructor ({@code new X()}) or an injected constructor
 * ({@code new X((T0) args[0], …)}) where every parameter is a nameable reference type.
 *
 * @param fqn            fully-qualified class name of the component
 * @param ctorParamTypes erased, nameable types of the selected constructor's parameters, in
 *                       declared order (empty for a no-arg constructor)
 */
public record Component(String fqn, List<String> ctorParamTypes) {

    public Component {
        ctorParamTypes = List.copyOf(ctorParamTypes);
    }

    /** {@code true} when the provider uses a no-arg constructor ({@code new X()}). */
    public boolean noArg() {
        return ctorParamTypes.isEmpty();
    }
}
