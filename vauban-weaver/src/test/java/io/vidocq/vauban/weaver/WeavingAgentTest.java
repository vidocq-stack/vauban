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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("WeavingAgent — installed at JVM start or by a dynamic attach")
class WeavingAgentTest {

    @AfterEach
    void forgetTheAgent() {
        System.clearProperty(WeavingAgent.ATTACHED_PROPERTY);
        System.clearProperty(WeavingAgent.STARTUP_PROPERTY);
        AgentAccess.set(null);
    }

    @Test
    @DisplayName("-javaagent:vauban-weaver.jar=<plan> says the agent came with the JVM")
    void premainMarksTheAgentGivenAtStart(@TempDir Path dir) throws IOException {
        WeavingAgent.premain(emptyPlan(dir), instrumentation());

        assertEquals("true", System.getProperty(WeavingAgent.ATTACHED_PROPERTY));
        assertEquals("true", System.getProperty(WeavingAgent.STARTUP_PROPERTY));
    }

    @Test
    @DisplayName("a dynamic attach is not an agent given at start")
    void agentmainDoesNotMarkTheStart(@TempDir Path dir) throws IOException {
        WeavingAgent.agentmain(emptyPlan(dir), instrumentation());

        assertEquals("true", System.getProperty(WeavingAgent.ATTACHED_PROPERTY));
        assertNull(System.getProperty(WeavingAgent.STARTUP_PROPERTY));
    }

    @Test
    @DisplayName("an agent given at start without a plan is inactive and marks nothing")
    void premainWithoutAPlanMarksNothing() {
        WeavingAgent.premain(" ", instrumentation());

        assertNull(System.getProperty(WeavingAgent.ATTACHED_PROPERTY));
        assertNull(System.getProperty(WeavingAgent.STARTUP_PROPERTY));
    }

    private static String emptyPlan(Path dir) throws IOException {
        return Files.writeString(dir.resolve("plan.txt"), "").toString();
    }

    /** An empty plan registers no transformer: the agent must not call its instrumentation. */
    private static Instrumentation instrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
