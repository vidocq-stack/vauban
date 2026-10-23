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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ClassInfo type parameters (vauban#207)")
class ClassInfoTest {

    private static final DotName NAME = DotName.of("app.Outer$Box");
    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final List<TypeInfo.TypeVariable> TYPE_PARAMETERS = List.of(
            new TypeInfo.TypeVariable("T", List.of(new TypeInfo.ClassType(DotName.of("java.lang.Number")))));

    @Test
    @DisplayName("a class info built without type parameters declares none")
    void builtWithoutTypeParameters() {
        var info = new ClassInfo(NAME, OBJECT, List.of(), 0, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "Box");
        assertEquals("Box", info.simpleName());
        assertEquals(List.of(), List.copyOf(info.typeParameters()));
    }

    @Test
    @DisplayName("another set of annotations keeps the type parameters")
    void withAnnotationsKeepsTypeParameters() {
        var info = new ClassInfo(NAME, OBJECT, List.of(), 0, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "Box", TYPE_PARAMETERS);
        var annotated = info.withAnnotations(List.of(new AnnotationInfo(DotName.of("app.Marker"), java.util.Map.of())));
        assertEquals(TYPE_PARAMETERS, annotated.typeParameters());
        assertEquals(1, annotated.annotations().size());
    }
}
