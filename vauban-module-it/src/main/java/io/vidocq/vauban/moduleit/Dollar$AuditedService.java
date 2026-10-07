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
 * An intercepted, normal-scoped bean whose own name contains {@code $}, with a method naming
 * another such type: its rendered subclass and client proxy name both by their canonical names
 * (BUG-20261007-01).
 */
@ApplicationScoped
@Audited
public class Dollar$AuditedService {

    public Dollar$Note echo(Dollar$Note note) {
        return new Dollar$Note(note.text() + "@" + getClass().getSimpleName());
    }
}
