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
package io.vidocq.vauban.indexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Smoke test - infrastructure verification")
class SmokeTest {

    sealed interface Shape permits Circle, Square {}
    record Circle(double radius) implements Shape {}
    record Square(double side) implements Shape {}

    @Test
    @DisplayName("JUnit 6 works")
    void junitWorks() {
        assertTrue(true);
    }

    @Test
    @DisplayName("JDK 25 Class-File API is available")
    void classFileApiAvailable() throws Exception {
        var clazz = Class.forName("java.lang.classfile.ClassFile");
        assertEquals("java.lang.classfile.ClassFile", clazz.getName());
    }

    @Test
    @DisplayName("Java records work")
    void recordsWork() {
        record Point(int x, int y) {}
        var p = new Point(1, 2);
        assertEquals(1, p.x());
        assertEquals(2, p.y());
    }

    @Test
    @DisplayName("sealed interfaces work with pattern matching")
    void sealedInterfacesWork() {
        Shape shape = new Circle(5.0);
        var result = switch (shape) {
            case Circle c -> "circle: " + c.radius();
            case Square s -> "square: " + s.side();
        };
        assertEquals("circle: 5.0", result);
    }
}
