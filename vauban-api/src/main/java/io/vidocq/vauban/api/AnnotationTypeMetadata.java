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
package io.vidocq.vauban.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an annotation type declares, read at build time so the container never has to read it back:
 * the members it has, the default of each member that has one, and the members annotated
 * {@code @Nonbinding}.
 *
 * <p>CDI resolution needs exactly this to compare two qualifiers, or two interceptor bindings: a
 * member a declaration leaves out counts as its default, and a {@code @Nonbinding} member takes no
 * part. The values are ordinary Java values — a {@code String}, an enum constant, a {@code Class}, an
 * array, a nested annotation — as the annotation's own members return them.
 *
 * @param members    every member the type declares, in declaration order
 * @param types      the declared type of each member, so a value can be built for it without reading
 *                   the declaration back — an empty {@code int[]} and an empty {@code String[]} are
 *                   the same value until the type says otherwise
 * @param defaults   the default of each member that has one
 * @param nonbinding the members annotated {@code @Nonbinding}
 */
public record AnnotationTypeMetadata(List<String> members, Map<String, Class<?>> types,
        Map<String, Object> defaults, Set<String> nonbinding) {

    public AnnotationTypeMetadata {
        members = List.copyOf(members);
        types = Map.copyOf(types);
        defaults = Map.copyOf(defaults);
        nonbinding = Set.copyOf(nonbinding);
    }
}
