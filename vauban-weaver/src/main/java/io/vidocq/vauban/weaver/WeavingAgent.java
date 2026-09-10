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
import java.nio.file.Path;

/**
 * Entry points of the load-time weaving agent (Vidocq/vauban#24, IDE-build fallback).
 *
 * <p>The agent argument is the path to a {@link WeavingPlan} file. Each installation —
 * {@code -javaagent:vauban-weaver.jar=<plan>} at JVM start, or a dynamic attach performed
 * by {@link AttachBack} on behalf of the container — registers one plan-scoped
 * {@link WeavingTransformer}; repeated attaches (one container boot each) accumulate
 * independent, idempotent transformers.
 *
 * <p>The {@value #ATTACHED_PROPERTY} system property is the handshake read by the
 * container after a dynamic attach to confirm the agent is live in this JVM.
 */
public final class WeavingAgent {

    /** Set to {@code "true"} in the target JVM once at least one plan is installed. */
    public static final String ATTACHED_PROPERTY = "io.vidocq.vauban.weaver.attached";

    private WeavingAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    private static synchronized void install(String agentArgs, Instrumentation inst) {
        // Capture the Instrumentation first, so it is available even for an empty plan — an
        // empty-plan attach is exactly how vauban-core obtains it to open a third-party module's
        // package at boot (issue #42, Stage 3b) without weaving anything.
        AgentAccess.set(inst);
        if (agentArgs == null || agentArgs.isBlank()) {
            System.err.println("[vauban-weaver] missing agent argument (weaving plan path) — agent inactive");
            return;
        }
        var plan = WeavingPlan.load(Path.of(agentArgs.strip()));
        if (!plan.isEmpty()) {
            inst.addTransformer(new WeavingTransformer(plan));
        }
        System.setProperty(ATTACHED_PROPERTY, "true");
    }
}
