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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@code sjar encrypt} reads its options, before it looks for a key. */
class SjarToolTest {

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

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void anOptionTakesTheNextArgumentAsItsValue() throws Exception {
        SjarTool.main(new String[] {"encrypt", "--weird", "-k", "alias", "-o", "out.sjar"});

        assertTrue(errors().contains("Unknown option: --weird"), errors());
        assertFalse(errors().contains("Unknown option: alias"), errors());
        assertFalse(errors().contains("Unknown option: out.sjar"), errors());
        assertTrue(errors().contains("Error: --input is required"), errors());
    }

    @Test
    void anUnknownOptionDoesNotConsumeTheNextArgument() throws Exception {
        SjarTool.main(new String[] {"encrypt", "--weird", "--other", "-k", "alias"});

        assertTrue(errors().contains("Unknown option: --weird"), errors());
        assertTrue(errors().contains("Unknown option: --other"), errors());
        assertFalse(errors().contains("Unknown option: alias"), errors());
    }

    @Test
    void anOptionWithoutItsValueFails() {
        String[] args = {"encrypt", "-k"};

        assertThrows(ArrayIndexOutOfBoundsException.class, () -> SjarTool.main(args));
    }
}
