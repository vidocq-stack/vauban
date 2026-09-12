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
package io.vidocq.vauban.maven.modularize;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModularizeMojoTest {

    @Test
    void unsetModeMeansDerived() throws Exception {
        assertEquals(Modularizer.Mode.DERIVED, ModularizeMojo.modeOf(null));
        assertEquals(Modularizer.Mode.DERIVED, ModularizeMojo.modeOf("  "));
    }

    @Test
    void bothModesAreAccepted() throws Exception {
        assertEquals(Modularizer.Mode.DERIVED, ModularizeMojo.modeOf("derived"));
        assertEquals(Modularizer.Mode.ALL_AUTOMATIC, ModularizeMojo.modeOf("all-automatic"));
        assertEquals(Modularizer.Mode.ALL_AUTOMATIC, ModularizeMojo.modeOf(" ALL-Automatic "));
    }

    /**
     * A typo used to fall through to {@code derived}: the build then quietly left every
     * {@code Automatic-Module-Name} jar unpatched and only failed later, in jlink, blaming the jars.
     */
    @Test
    void unknownModeFailsTheBuildNamingTheValue() {
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> ModularizeMojo.modeOf("all-automtic"));

        assertEquals("vauban:modularize: unknown mode 'all-automtic'"
                + " (expected derived | all-automatic)", e.getMessage());
    }
}
