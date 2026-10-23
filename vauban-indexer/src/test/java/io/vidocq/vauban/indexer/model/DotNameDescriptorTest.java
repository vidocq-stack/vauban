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
package io.vidocq.vauban.indexer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A one-character descriptor names a primitive type or void; a descriptor that names no type is refused. */
class DotNameDescriptorTest {

    @ParameterizedTest
    @CsvSource({"Z, boolean", "B, byte", "C, char", "S, short", "I, int", "J, long", "F, float", "D, double",
        "V, void"})
    void aOneCharacterDescriptorIsAPrimitiveOrVoid(String descriptor, String name) {
        assertEquals(DotName.of(name), DotName.fromDescriptor(descriptor));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Q", "Ljava/lang/String", "java.lang.String"})
    void aDescriptorThatNamesNoTypeIsRefused(String descriptor) {
        var failure = assertThrows(IllegalArgumentException.class, () -> DotName.fromDescriptor(descriptor));

        assertEquals("Invalid class descriptor: " + descriptor, failure.getMessage());
    }
}
