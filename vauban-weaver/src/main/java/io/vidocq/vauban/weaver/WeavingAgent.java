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

    /**
     * Set to {@code "true"} once a plan given with {@code -javaagent:vauban-weaver.jar=<plan>} is installed at JVM
     * start: that agent weaves what its plan lists, so the container leaves those beans to it.
     */
    public static final String STARTUP_PROPERTY = "io.vidocq.vauban.weaver.startup";

    private WeavingAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        if (install(agentArgs, inst)) {
            System.setProperty(STARTUP_PROPERTY, "true");
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    /** Installs the plan named by {@code agentArgs}; {@code false} when there is none and the agent stays inactive. */
    private static synchronized boolean install(String agentArgs, Instrumentation inst) {
        // Capture the Instrumentation first, so it is available even for an empty plan — an
        // empty-plan attach is exactly how vauban-core obtains it to open a third-party module's
        // package at boot (issue #42, Stage 3b) without weaving anything.
        AgentAccess.set(inst);
        if (agentArgs == null || agentArgs.isBlank()) {
            System.err.println("[vauban-weaver] missing agent argument (weaving plan path) — agent inactive");
            return false;
        }
        var plan = WeavingPlan.load(Path.of(agentArgs.strip()));
        if (!plan.isEmpty()) {
            inst.addTransformer(new WeavingTransformer(plan));
        }
        System.setProperty(ATTACHED_PROPERTY, "true");
        return true;
    }
}
