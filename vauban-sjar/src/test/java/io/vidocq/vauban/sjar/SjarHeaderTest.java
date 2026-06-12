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
package io.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SjarHeaderTest {

    @Test
    void roundTrip() throws Exception {
        var header = new SjarHeader("vidocq-prod");
        var out = new ByteArrayOutputStream();
        header.writeTo(out);

        var parsed = SjarHeader.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("vidocq-prod", parsed.keyAlias());
        assertEquals(2, parsed.version());
    }

    @Test
    void carriesNoClassName() throws Exception {
        var header = new SjarHeader("vidocq-prod");
        var out = new ByteArrayOutputStream();
        header.writeTo(out);
        var json = out.toString();

        // header must contain only bootstrap fields, never a package/class path
        assertFalse(json.contains("internal"));
        assertFalse(json.contains(".class"));
        assertFalse(json.contains("uuid"));
    }
}
