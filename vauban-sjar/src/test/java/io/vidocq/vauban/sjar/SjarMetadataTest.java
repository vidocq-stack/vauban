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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SjarMetadataTest {

    @Test
    void roundTripSerialization() throws Exception {
        var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        var entries = Map.of(
                "com/example/internal/Impl.class.enc",
                new SjarMetadata.EntryMetadata(iv, 1024, "com/example/internal/Impl.class")
        );
        var clearPackages = Set.of("com/example/api");
        var metadata = new SjarMetadata("my-key", entries, clearPackages, "com.example.mylib");

        var out = new ByteArrayOutputStream();
        metadata.writeTo(out);

        var parsed = SjarMetadata.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("my-key", parsed.keyAlias());
        assertEquals("com.example.mylib", parsed.moduleName());
        assertTrue(parsed.clearPackages().contains("com/example/api"));
        assertEquals(1, parsed.entries().size());

        var entry = parsed.entries().get("com/example/internal/Impl.class.enc");
        assertNotNull(entry);
        assertEquals(1024, entry.originalSize());
        assertEquals("com/example/internal/Impl.class", entry.originalEntry());
        assertArrayEquals(iv, entry.iv());
    }
}
