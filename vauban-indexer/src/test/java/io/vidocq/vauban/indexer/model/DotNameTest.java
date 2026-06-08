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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DotName")
class DotNameTest {

    @Test
    @DisplayName("creates from a fully qualified name")
    void shouldCreateFromFqcn() {
        var name = DotName.of("java.lang.String");
        assertEquals("java.lang.String", name.value());
    }

    @Test
    @DisplayName("converts from the internal form")
    void shouldConvertFromInternal() {
        var name = DotName.fromInternal("java/lang/String");
        assertEquals("java.lang.String", name.value());
    }

    @Test
    @DisplayName("converts to the internal form")
    void shouldConvertToInternal() {
        assertEquals("java/lang/String", DotName.of("java.lang.String").toInternal());
    }

    @Test
    @DisplayName("converts from a descriptor")
    void shouldConvertFromDescriptor() {
        var name = DotName.fromDescriptor("Ljava/lang/String;");
        assertEquals("java.lang.String", name.value());
    }

    @Test
    @DisplayName("converts to a descriptor")
    void shouldConvertToDescriptor() {
        assertEquals("Ljava/lang/String;", DotName.of("java.lang.String").toDescriptor());
    }

    @Test
    @DisplayName("returns the simple name")
    void shouldReturnSimpleName() {
        assertEquals("String", DotName.of("java.lang.String").simpleName());
    }

    @Test
    @DisplayName("returns the package name")
    void shouldReturnPackageName() {
        assertEquals("java.lang", DotName.of("java.lang.String").packageName());
    }

    @Test
    @DisplayName("returns an empty package for a class without a package")
    void shouldReturnEmptyPackageForDefaultPackage() {
        assertEquals("", DotName.of("MyClass").packageName());
        assertEquals("MyClass", DotName.of("MyClass").simpleName());
    }

    @Test
    @DisplayName("is comparable")
    void shouldBeComparable() {
        var a = DotName.of("a.B");
        var b = DotName.of("b.C");
        assertTrue(a.compareTo(b) < 0);
        assertEquals(0, a.compareTo(DotName.of("a.B")));
    }

    @Test
    @DisplayName("rejects null")
    void shouldRejectNull() {
        assertThrows(NullPointerException.class, () -> new DotName(null));
    }

    @Test
    @DisplayName("rejects an empty string")
    void shouldRejectEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new DotName(""));
    }

    @Test
    @DisplayName("toString returns the value")
    void toStringShouldReturnValue() {
        assertEquals("java.lang.String", DotName.of("java.lang.String").toString());
    }
}
