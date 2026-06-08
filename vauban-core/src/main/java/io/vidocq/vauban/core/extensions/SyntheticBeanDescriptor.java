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
package io.vidocq.vauban.core.extensions;

import java.util.List;
import java.util.Map;

/**
 * Serializable descriptor for a synthetic bean created by a {@code @Synthesis} BCE phase.
 * Used to persist synthetic bean metadata at compile time and reload it at runtime,
 * avoiding re-execution of the BCE.
 */
public record SyntheticBeanDescriptor(
        String beanClassName,
        String creatorClassName,
        String disposerClassName,
        String scopeAnnotation,
        List<String> types,
        List<String> qualifiers,
        Map<String, String> params,
        boolean alternative,
        int priority,
        String name
) {
    public SyntheticBeanDescriptor {
        types = types != null ? List.copyOf(types) : List.of();
        qualifiers = qualifiers != null ? List.copyOf(qualifiers) : List.of();
        params = params != null ? Map.copyOf(params) : Map.of();
    }
}
