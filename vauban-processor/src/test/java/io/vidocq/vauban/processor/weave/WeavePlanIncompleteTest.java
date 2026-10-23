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
package io.vidocq.vauban.processor.weave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A plan naming a class not compiled yet stays registered, to be executed when the class exists. */
class WeavePlanIncompleteTest {

    @TempDir
    Path output;

    @Test
    void aPlanWithAMissingClassIsKeptForTheNextRound() throws IOException {
        var plan = output.resolve(WeavePlan.PLAN_RESOURCE);
        Files.createDirectories(plan.getParent());
        Files.writeString(plan, "\nproxy app.NotYetCompiled_ClientProxy\n");
        var property = "vauban.weave.plan." + output;
        System.setProperty(property, output.toString());
        try {
            WeavePlan.executeRegisteredPlans();

            assertTrue(Files.isRegularFile(plan), "the plan is not complete, so it is not deleted");
            assertEquals(output.toString(), System.getProperty(property), "and it stays registered");
        } finally {
            System.clearProperty(property);
        }
    }
}
