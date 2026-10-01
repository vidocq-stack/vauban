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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extension that checks the deployment's environment needs to know whether it runs while the
 * application compiles or while the container starts (ravel#21).
 */
class ExtensionPhaseTest {

    @Test
    void anExtensionRunsAtContainerStartUnlessToldOtherwise() {
        assertFalse(ExtensionPhase.isBuildTime());
    }

    @Test
    void anExtensionRunAtBuildTimeKnowsIt() {
        var seen = new AtomicBoolean();

        var result = ExtensionPhase.atBuildTime(() -> {
            seen.set(ExtensionPhase.isBuildTime());
            return "done";
        });

        assertTrue(seen.get());
        assertEquals("done", result);
    }

    @Test
    void theBuildPhaseEndsWithTheCallThatOpenedIt() {
        ExtensionPhase.atBuildTime(() -> null);

        assertFalse(ExtensionPhase.isBuildTime());
    }
}
