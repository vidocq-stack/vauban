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
package io.vidocq.vauban.processor.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A record compares an array component by reference: two generated classes with the same bytes
 * would not be equal. {@link GeneratedClass} compares, hashes and prints the bytes by content.
 */
@DisplayName("GeneratedClass compares its class file bytes by content")
class GeneratedClassTest {

    private static final String NAME = "app.Bean_Factory";

    @Test
    @DisplayName("the same name and the same bytes in two arrays are equal, with the same hash code")
    void sameContentIsEqual() {
        var one = new GeneratedClass(NAME, new byte[] {1, 2, 3});
        var other = new GeneratedClass(NAME, new byte[] {1, 2, 3});

        assertEquals(one, other);
        assertEquals(one.hashCode(), other.hashCode());
    }

    @Test
    @DisplayName("other bytes or another name are not equal")
    void otherContentIsNotEqual() {
        var one = new GeneratedClass(NAME, new byte[] {1, 2, 3});

        assertNotEquals(one, new GeneratedClass(NAME, new byte[] {1, 2, 4}));
        assertNotEquals(one, new GeneratedClass("app.Other_Factory", new byte[] {1, 2, 3}));
    }

    @Test
    @DisplayName("toString gives the name and the size of the class file, not its bytes")
    void toStringGivesTheSize() {
        assertEquals("GeneratedClass[className=app.Bean_Factory, bytecode=3 bytes]",
                new GeneratedClass(NAME, new byte[] {1, 2, 3}).toString());
    }
}
