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
package io.vidocq.vauban.core.provider;

import io.vidocq.vauban.core.provider.ComponentProviderClassGenerator.Generated;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** A {@link Generated} class compares by name and bytes content, and prints its size rather than its bytes. */
class ComponentProviderGeneratedValueTest {

    private static final String NAME = "com.acme._VaubanProvider";

    @Test
    void equalNamesAndBytesMakeEqualValues() {
        var generated = new Generated(NAME, new byte[] {1, 2, 3});

        assertEquals(new Generated(NAME, new byte[] {1, 2, 3}), generated);
        assertEquals(new Generated(NAME, new byte[] {1, 2, 3}).hashCode(), generated.hashCode());
        assertNotEquals(new Generated(NAME, new byte[] {1, 2}), generated);
        assertNotEquals(new Generated("com.acme.Other", new byte[] {1, 2, 3}), generated);
        assertNotEquals(generated, NAME);
    }

    @Test
    void itPrintsItsNameAndSize() {
        assertEquals("Generated[className=" + NAME + ", bytecode=3 bytes]",
                new Generated(NAME, new byte[] {1, 2, 3}).toString());
    }
}
