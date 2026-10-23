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
package io.vidocq.vauban.processor.codegen.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The literal class the processor writes for an annotation type: each kind of member default as a Java expression,
 * and {@code equals}, {@code hashCode} and {@code toString} as {@code Annotation} specifies them per member type.
 */
class AnnotationLiteralRenderingTest {

    private static String literals;

    private static ClassInfo indexed(Class<?> type) throws IOException {
        try (var in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return ClassFileScanner.scan(in.readAllBytes());
        }
    }

    @BeforeAll
    static void render() throws IOException {
        literals = AnnotationArtefacts.render(List.of(indexed(EveryMemberKind.class), indexed(Shade.class),
                indexed(ClassRetained.class))).literals();
    }

    static Stream<String> defaults() {
        return Stream.of(
                "this.flag$ = members.containsKey(\"flag\") ? (Boolean) members.get(\"flag\") : true;",
                "this.small$ = members.containsKey(\"small\") ? (Byte) members.get(\"small\") : (byte) 1;",
                "this.letter$ = members.containsKey(\"letter\") ? (Character) members.get(\"letter\") : 'x';",
                "this.medium$ = members.containsKey(\"medium\") ? (Short) members.get(\"medium\") : (short) 2;",
                "this.count$ = members.containsKey(\"count\") ? (Integer) members.get(\"count\") : 3;",
                "this.big$ = members.containsKey(\"big\") ? (Long) members.get(\"big\") : 4L;",
                "this.ratio$ = members.containsKey(\"ratio\") ? (Float) members.get(\"ratio\") : 1.5f;",
                "this.precise$ = members.containsKey(\"precise\") ? (Double) members.get(\"precise\") : 2.5;",
                "this.bigs$ = members.containsKey(\"bigs\") ? ((long[]) members.get(\"bigs\")).clone()"
                        + " : new long[] {5L};",
                "this.required$ = members.containsKey(\"required\") ? (Integer) members.get(\"required\")"
                        + " : incomplete(\"required\");");
    }

    @ParameterizedTest
    @MethodSource("defaults")
    void eachDefaultIsWrittenAsAJavaExpressionOfItsType(String assignment) {
        assertTrue(literals.contains(assignment), assignment);
    }

    static Stream<String> comparisons() {
        return Stream.of(
                "flag$ == that.flag()",
                "count$ == that.count()",
                "Float.valueOf(ratio$).equals(that.ratio())",
                "Double.valueOf(precise$).equals(that.precise())",
                "java.util.Arrays.equals(bigs$, that.bigs())",
                "text$.equals(that.text())");
    }

    @ParameterizedTest
    @MethodSource("comparisons")
    void equalsComparesFloatsAndDoublesByValueAndArraysByElement(String comparison) {
        assertTrue(literals.contains(comparison), comparison);
    }

    static Stream<String> hashes() {
        return Stream.of("Boolean.hashCode(flag$)", "Byte.hashCode(small$)", "Character.hashCode(letter$)",
                "Short.hashCode(medium$)", "Integer.hashCode(count$)", "Long.hashCode(big$)", "Float.hashCode(ratio$)",
                "Double.hashCode(precise$)", "java.util.Arrays.hashCode(texts$)", "text$.hashCode()");
    }

    @ParameterizedTest
    @MethodSource("hashes")
    void hashCodeUsesTheHashOfEachMemberType(String hash) {
        assertTrue(literals.contains("^ " + hash + ")"), hash);
    }

    static Stream<String> renderings() {
        return Stream.of(
                "+ \", text=\" + \"\\\"\" + text$ + \"\\\"\"",
                "+ \", type=\" + type$.getName() + \".class\"",
                "+ \", target=\" + String.valueOf(target$)",
                "+ \", texts=\" + render$texts(texts$)",
                "for (var value : values) items.add(value.getName() + \".class\");");
    }

    @ParameterizedTest
    @MethodSource("renderings")
    void toStringQuotesStringsAndWritesClassesAsLiterals(String rendering) {
        assertTrue(literals.contains(rendering), rendering);
    }

    @Test
    void aTypeKeptInTheClassFileOnlyIsNotRendered() throws IOException {
        assertFalse(literals.contains("ClassRetained"));
        assertTrue(AnnotationArtefacts.render(List.of(indexed(ClassRetained.class))).isEmpty());
    }
}
