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
package io.vidocq.vauban.weaver;

import java.lang.instrument.Instrumentation;

/**
 * Holds the {@link Instrumentation} captured when the load-time weaving agent installs, so other
 * Vauban components can reuse it (issue #42, Stage 3b: opening a third-party module's package to the
 * container at boot via {@link Instrumentation#redefineModule}, without a hand-written
 * {@code --add-opens}). Set once by {@link WeavingAgent}; read by {@code vauban-core}.
 */
public final class AgentAccess {

    private static volatile Instrumentation instrumentation;

    private AgentAccess() {}

    /** Called by {@link WeavingAgent} on install. */
    static void set(Instrumentation inst) {
        instrumentation = inst;
    }

    /** The captured {@link Instrumentation}, or {@code null} if no agent has installed. */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    /** Whether an agent is present and its {@link Instrumentation} is available. */
    public static boolean available() {
        return instrumentation != null;
    }
}
