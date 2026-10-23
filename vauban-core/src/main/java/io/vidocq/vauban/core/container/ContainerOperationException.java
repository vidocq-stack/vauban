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

import java.io.Serial;

/**
 * A container operation that failed for a reason the CDI API has no exception for: a member the
 * container cannot reach, a class path it cannot scan, an initializer method that threw. It stays
 * a {@link RuntimeException}, as the generic exception it replaces was, so every caller that
 * catches one still catches it.
 */
final class ContainerOperationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    ContainerOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
