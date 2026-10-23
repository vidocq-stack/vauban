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
package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The erased JVM descriptor {@link InjectionPointInfo#methodDescriptor} builds from parameter types, which keeps
 * the descriptions of overloaded methods apart.
 */
@DisplayName("InjectionPointInfo: the method descriptor of parameter types")
class InjectionPointMethodDescriptorTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"BOOLEAN, Z", "BYTE, B", "CHAR, C", "SHORT, S", "INT, I", "LONG, J", "FLOAT, F", "DOUBLE, D"})
    @DisplayName("a primitive parameter has its one-letter descriptor")
    void primitiveParameter(TypeInfo.PrimitiveType.Kind kind, String descriptor) {
        assertEquals("(" + descriptor + ")V", InjectionPointInfo.methodDescriptor(List.of(new TypeInfo.PrimitiveType(kind))));
    }

    @Test
    @DisplayName("void, arrays and parameterized types erase to their JVM descriptors")
    void voidArraysAndParameterizedTypes() {
        var descriptor = InjectionPointInfo.methodDescriptor(List.of(
                new TypeInfo.VoidType(),
                new TypeInfo.ArrayType(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT), 2),
                new TypeInfo.ArrayType(new TypeInfo.ClassType(DotName.of("java.lang.String")), 1),
                new TypeInfo.ParameterizedType(DotName.of("java.util.List"),
                        List.of(new TypeInfo.ClassType(DotName.of("java.lang.String"))))));

        assertEquals("(V[[I[Ljava/lang/String;Ljava/util/List;)V", descriptor);
    }
}
