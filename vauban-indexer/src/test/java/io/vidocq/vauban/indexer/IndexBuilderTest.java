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

import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IndexBuilder")
class IndexBuilderTest {

    private static ClassInfo dummyClass(String name) {
        return new ClassInfo(
                DotName.of(name),
                DotName.of("java.lang.Object"),
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                ClassKind.CLASS
        );
    }

    @Test
    @DisplayName("builds an empty index")
    void shouldBuildEmptyIndex() {
        var index = new IndexBuilder().build();
        assertEquals(0, index.size());
    }

    @Test
    @DisplayName("adds a class")
    void shouldAddClassInfo() {
        var builder = new IndexBuilder();
        builder.add(dummyClass("com.example.Foo"));
        assertEquals(1, builder.size());
    }

    @Test
    @DisplayName("replaces a duplicate class")
    void shouldReplaceDuplicate() {
        var builder = new IndexBuilder();
        builder.add(dummyClass("com.example.Foo"));
        builder.add(dummyClass("com.example.Foo"));
        assertEquals(1, builder.size());
    }

    @Test
    @DisplayName("checks the presence of a class")
    void shouldCheckContains() {
        var builder = new IndexBuilder();
        builder.add(dummyClass("com.example.Foo"));
        assertTrue(builder.contains(DotName.of("com.example.Foo")));
        assertFalse(builder.contains(DotName.of("com.example.Bar")));
    }

    @Test
    @DisplayName("adds multiple classes")
    void shouldAddAll() {
        var builder = new IndexBuilder();
        builder.addAll(List.of(dummyClass("com.example.A"), dummyClass("com.example.B")));
        assertEquals(2, builder.size());
    }
}
