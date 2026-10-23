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
package io.vidocq.vauban.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.naming.InvalidNameException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A name has no empty segment, and a prefix is compared segment by segment, in one namespace. */
class JndiNameSegmentTest {

    @ParameterizedTest
    @ValueSource(strings = {"/env", "env/", "env//jdbc"})
    void aPathWithAnEmptySegmentIsRefused(String path) {
        var failure = assertThrows(IllegalArgumentException.class, () -> new JndiName(Namespace.COMP, path));

        assertEquals("A path has no empty segment: " + path, failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"java:comp//", "java:comp/env//jdbc", "java:app//env"})
    void aParsedNameWithAnEmptySegmentIsInvalid(String name) {
        var failure = assertThrows(InvalidNameException.class, () -> JndiName.parse(name));

        assertEquals(name + " has an empty segment", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "java:comp/env/jdbc, java:comp",
        "java:comp/env/jdbc, java:comp/env/jdbc",
        "java:comp/env/jdbc, java:comp/env"})
    void aNameStartsWithItsRootItselfAndItsAncestors(String name, String prefix) throws InvalidNameException {
        assertTrue(JndiName.parse(name).startsWith(JndiName.parse(prefix)));
    }

    @ParameterizedTest
    @CsvSource({
        "java:comp/env/jdbc, java:app",
        "java:comp/env/jdbc, java:comp/en",
        "java:comp/env, java:comp/env/jdbc"})
    void aNameDoesNotStartWithAnotherNamespaceAPartialSegmentOrADescendant(String name, String prefix)
            throws InvalidNameException {
        assertFalse(JndiName.parse(name).startsWith(JndiName.parse(prefix)));
    }
}
