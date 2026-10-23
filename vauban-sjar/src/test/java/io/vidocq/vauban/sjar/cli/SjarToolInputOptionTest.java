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
package io.vidocq.vauban.sjar.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.sjar.SjarKeyProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Both spellings of {@code --input} name the jar; without a key in the environment nothing is encrypted. */
class SjarToolInputOptionTest {

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void captureStandardError() {
        originalErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStandardError() {
        System.setErr(originalErr);
    }

    @Test
    void theInputIsAcceptedInBothSpellingsAndTheKeyIsRequired() throws Exception {
        Assumptions.assumeTrue(System.getenv(SjarKeyProvider.ENV_KEY) == null, "no key in this environment");

        SjarTool.main(new String[] {"encrypt", "--input", "first.jar", "-i", "lib.jar"});

        var errors = err.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("Error: Set " + SjarKeyProvider.ENV_KEY + " environment variable"), errors);
        assertFalse(errors.contains("--input is required") || errors.contains("Unknown option"), errors);
    }
}
