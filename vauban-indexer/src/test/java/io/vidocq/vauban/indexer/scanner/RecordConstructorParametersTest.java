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

import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261118-32, as the review of batch C found it: javac marks every parameter of a record's compact canonical
 * constructor mandated, which the source declares implicitly and its {@code Signature} names. They are the
 * constructor's own: with their generic types and their type annotations, numbered from 0, not taken for an outer
 * instance. Javac 25 writes the component's annotation on the type argument into a compact constructor with a
 * {@code FIELD} target, which names no parameter: the scanner reads the components' instead, each on its parameter,
 * path and all, the one on the parameter's type once (BUG-20261118-34).
 */
@DisplayName("BUG-20261118-32 - a record's canonical constructor")
class RecordConstructorParametersTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.TYPE_USE})
    @interface Marked {
    }

    record Compact(@Marked List<@Marked String> names, int size) {
        Compact {
            names = List.copyOf(names);
        }
    }

    record Explicit(List<String> names) {
        Explicit(@Marked List<@Marked String> names) {
            this.names = List.copyOf(names);
        }
    }

    @Test
    @DisplayName("a compact canonical constructor has its generic parameters and their type annotations")
    void aCompactConstructor() throws IOException {
        assertEquals("[ParameterizedType[rawType=java.util.List, typeArguments=[ClassType[name=java.lang.String]]]"
                + " [Marked], PrimitiveType[kind=INT] []] [PARAMETER 0 [] Marked, PARAMETER 0 [TYPE_ARGUMENT] Marked]",
                described(constructor("RecordConstructorParametersTest$Compact.class")));
    }

    @Test
    @DisplayName("an explicit canonical constructor has its generic parameters and their type annotations")
    void anExplicitConstructor() throws IOException {
        assertEquals("[ParameterizedType[rawType=java.util.List, typeArguments=[ClassType[name=java.lang.String]]]"
                + " [Marked]] [PARAMETER 0 [] Marked, PARAMETER 0 [TYPE_ARGUMENT] Marked]",
                described(constructor("RecordConstructorParametersTest$Explicit.class")));
    }

    private static MethodInfo constructor(String classFile) throws IOException {
        try (var in = RecordConstructorParametersTest.class.getResourceAsStream(classFile)) {
            return ClassFileScanner.scan(in.readAllBytes()).methods().stream().filter(MethodInfo::isConstructor)
                    .findFirst().orElseThrow();
        }
    }

    private static String described(MethodInfo constructor) {
        return constructor.parameters().stream().map(RecordConstructorParametersTest::described).toList() + " "
                + constructor.typeAnnotations().stream().map(RecordConstructorParametersTest::described).toList();
    }

    private static String described(ParameterInfo parameter) {
        return parameter.type() + " " + parameter.annotations().stream().map(annotation -> simple(annotation.name()
                .toString())).toList();
    }

    private static String described(TypeAnnotationInfo annotation) {
        return annotation.target() + " " + annotation.index() + " " + annotation.path().stream()
                .map(step -> step.kind().name()).toList() + " " + simple(annotation.annotation().name().toString());
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
