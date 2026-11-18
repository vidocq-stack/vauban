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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.indexer.model.DotName;

import java.util.Objects;
import java.util.Set;

/**
 * The classes beyond the bean classes whose content the language model read for an extension while a build freezes
 * the extensions (BUG-20261118-29): the annotation types whose default values it gave, and the {@code package-info}
 * classes whose annotations it gave. The record of the frozen extensions keeps their checksums, so that a start on
 * another version of them runs the extensions again. Nothing is noted outside {@link #recordingInto}.
 */
public final class ReadClasses {

    private static final ScopedValue<Set<DotName>> NOTED = ScopedValue.newInstance();

    private ReadClasses() {
    }

    /** Runs {@code operation}, noting into {@code into} the classes the language model reads for it. */
    public static <R, X extends Throwable> R recordingInto(Set<DotName> into,
            ScopedValue.CallableOp<? extends R, X> operation) throws X {
        return ScopedValue.where(NOTED, Objects.requireNonNull(into)).call(operation);
    }

    /** Notes that the language model read {@code name}, found or not, when a freeze records it. */
    public static void note(DotName name) {
        if (NOTED.isBound()) {
            NOTED.get().add(name);
        }
    }
}
