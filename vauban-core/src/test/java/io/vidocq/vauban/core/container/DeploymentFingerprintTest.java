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
package io.vidocq.vauban.core.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The fingerprint of a deployment")
class DeploymentFingerprintTest {

    private static final byte[] ONE = {1, 2, 3};
    private static final byte[] OTHER = {1, 2, 4};

    private static String of(String settings, Object... namesAndFiles) {
        var fingerprint = new DeploymentFingerprint(settings);
        for (int i = 0; i < namesAndFiles.length; i += 2) {
            fingerprint.add((String) namesAndFiles[i], (byte[]) namesAndFiles[i + 1]);
        }
        return fingerprint.value();
    }

    @Test
    @DisplayName("the same classes give the same fingerprint, whatever their order")
    void theOrderDoesNotCount() {
        assertEquals(of("s", "a.A", ONE, "b.B", OTHER), of("s", "b.B", OTHER, "a.A", ONE));
    }

    @Test
    @DisplayName("a class added, removed, recompiled or renamed gives another fingerprint")
    void anyChangeCounts() {
        String reference = of("s", "a.A", ONE, "b.B", OTHER);

        assertNotEquals(reference, of("s", "a.A", ONE, "b.B", OTHER, "c.C", ONE), "added");
        assertNotEquals(reference, of("s", "a.A", ONE), "removed");
        assertNotEquals(reference, of("s", "a.A", OTHER, "b.B", OTHER), "recompiled");
        assertNotEquals(reference, of("s", "a.A2", ONE, "b.B", OTHER), "renamed");
    }

    @Test
    @DisplayName("a class without a class file counts by its name")
    void aClassWithoutClassFile() {
        byte[] none = new byte[0];

        assertEquals(of("s", "a.A", none), of("s", "a.A", none));
        assertNotEquals(of("s", "a.A", none), of("s", "b.B", none), "another name");
        assertNotEquals(of("s", "a.A", none), of("s"), "no class at all");
        assertNotEquals(of("s", "a.A", none), of("s", "a.A", ONE), "the same class with a class file");
    }

    @Test
    @DisplayName("other discovery settings give another fingerprint")
    void theSettingsCount() {
        assertNotEquals(of("beanArchive=true", "a.A", ONE), of("beanArchive=false", "a.A", ONE));
    }
}
