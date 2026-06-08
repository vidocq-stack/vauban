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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanIndex")
class VaubanIndexTest {

    private VaubanIndex index;

    private static final DotName DEPRECATED = DotName.of("java.lang.Deprecated");
    private static final DotName MY_INTERFACE = DotName.of("com.example.MyInterface");
    private static final DotName BASE_CLASS = DotName.of("com.example.BaseClass");

    private static ClassInfo classWithAnnotation(String name, DotName annotationName) {
        return new ClassInfo(
                DotName.of(name),
                DotName.of("java.lang.Object"),
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(new AnnotationInfo(annotationName, Map.of())),
                ClassKind.CLASS
        );
    }

    private static ClassInfo classImplementing(String name, DotName interfaceName) {
        return new ClassInfo(
                DotName.of(name),
                DotName.of("java.lang.Object"),
                List.of(interfaceName),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                ClassKind.CLASS
        );
    }

    private static ClassInfo classExtending(String name, DotName superName) {
        return new ClassInfo(
                DotName.of(name),
                superName,
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                ClassKind.CLASS
        );
    }

    @BeforeEach
    void setUp() {
        var builder = new IndexBuilder();
        builder.add(classWithAnnotation("com.example.AnnotatedFoo", DEPRECATED));
        builder.add(classImplementing("com.example.ImplA", MY_INTERFACE));
        builder.add(classImplementing("com.example.ImplB", MY_INTERFACE));
        builder.add(classExtending("com.example.ChildA", BASE_CLASS));
        builder.add(classExtending("com.example.ChildB", BASE_CLASS));
        index = builder.build();
    }

    @Test
    @DisplayName("finds a class by name")
    void shouldFindClassByName() {
        var result = index.getClassByName(DotName.of("com.example.AnnotatedFoo"));
        assertTrue(result.isPresent());
        assertEquals("com.example.AnnotatedFoo", result.get().name().value());
    }

    @Test
    @DisplayName("returns empty for an unknown class")
    void shouldReturnEmptyForUnknown() {
        assertTrue(index.getClassByName(DotName.of("com.example.Unknown")).isEmpty());
    }

    @Test
    @DisplayName("finds the classes with an annotation")
    void shouldFindClassesWithAnnotation() {
        var result = index.getClassesWithAnnotation(DEPRECATED);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("finds the direct implementors")
    void shouldFindDirectImplementors() {
        var result = index.getImplementors(MY_INTERFACE);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("finds the direct subclasses")
    void shouldFindDirectSubclasses() {
        var result = index.getSubclasses(BASE_CLASS);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("returns all known classes")
    void shouldReturnKnownClasses() {
        assertEquals(5, index.getKnownClasses().size());
    }

    @Test
    @DisplayName("returns the size")
    void shouldReturnSize() {
        assertEquals(5, index.size());
    }

    @Test
    @DisplayName("checks the presence")
    void shouldCheckContains() {
        assertTrue(index.containsClass(DotName.of("com.example.ImplA")));
        assertFalse(index.containsClass(DotName.of("com.example.Unknown")));
    }
}
