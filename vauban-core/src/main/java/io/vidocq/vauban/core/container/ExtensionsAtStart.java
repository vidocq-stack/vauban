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
package io.vidocq.vauban.core.container;

import java.util.List;

/**
 * What the start of a container did with the build compatible extensions of its deployment (VAU-PERF-005), for a
 * startup report or a test.
 *
 * @param frozen        whether the start loaded what the build froze instead of running the extensions
 * @param frozenAtBuild the extensions the start did not run, their result being in the build's record
 * @param ranAtStart    the extensions the start ran: all of them without a record, those that left work for the
 *                      start with one
 * @param note          why a record that exists was not used, {@code null} otherwise
 */
public record ExtensionsAtStart(boolean frozen, List<String> frozenAtBuild, List<String> ranAtStart, String note) {

    public ExtensionsAtStart {
        frozenAtBuild = List.copyOf(frozenAtBuild);
        ranAtStart = List.copyOf(ranAtStart);
    }
}
