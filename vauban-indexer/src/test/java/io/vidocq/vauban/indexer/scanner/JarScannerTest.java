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
package io.vidocq.vauban.indexer.scanner;

import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JarScanner")
class JarScannerTest {

    private static Path junitJarPath() throws URISyntaxException {
        var url = Test.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(url.toURI());
    }

    @Test
    @DisplayName("scans a JAR and finds classes")
    void shouldScanJar() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        assertFalse(classes.isEmpty(), "should find classes in the JUnit JAR");
    }

    @Test
    @DisplayName("finds a known class in the JAR")
    void shouldFindKnownClass() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        boolean found = classes.stream()
                .anyMatch(c -> c.name().equals(DotName.of("org.junit.jupiter.api.Test")));
        assertTrue(found, "should find org.junit.jupiter.api.Test");
    }

    @Test
    @DisplayName("returns multiple classes")
    void shouldReturnMultipleClasses() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        assertTrue(classes.size() > 10, "a JUnit JAR should contain more than 10 classes");
    }
}
