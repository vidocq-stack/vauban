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
package io.vidocq.vauban.core.types;

import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.model.TypeInfo.ArrayType;
import io.vidocq.vauban.indexer.model.TypeInfo.ClassType;
import io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType;
import io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType;
import io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable;
import io.vidocq.vauban.indexer.model.TypeInfo.WildcardType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The corners of the CDI 4.1 assignability rules (§2.2.1, §5.2.4): primitives against their wrappers and
 * names, raw-equivalent arguments, type variable and wildcard arguments, arrays of parameterized types.
 */
class AssignabilityRulesEdgeCasesTest {

    private static final ClassType OBJECT = classType("java.lang.Object");
    private static final ClassType NUMBER = classType("java.lang.Number");
    private static final ClassType INTEGER = classType("java.lang.Integer");
    private static final ClassType STRING = classType("java.lang.String");
    private static final ClassType LIST = classType("java.util.List");
    private static final ClassType INT_NAME = classType("int");
    private static final ClassType LONG_NAME = classType("long");
    private static final PrimitiveType INT = new PrimitiveType(PrimitiveType.Kind.INT);
    private static final PrimitiveType LONG = new PrimitiveType(PrimitiveType.Kind.LONG);
    private static final TypeVariable FREE_T = new TypeVariable("T", List.of());
    private static final TypeVariable OBJECT_T = new TypeVariable("T", List.of(OBJECT));
    private static final TypeVariable NUMBER_T = new TypeVariable("T", List.of(NUMBER));
    private static final WildcardType ANY = new WildcardType(null, null);

    private final AssignabilityRules rules = new AssignabilityRules(new IndexBuilder()
            .add(AssignabilityRulesTest.makeClass("java.lang.Object", null))
            .add(AssignabilityRulesTest.makeClass("java.lang.Number", "java.lang.Object"))
            .add(AssignabilityRulesTest.makeClass("java.lang.Integer", "java.lang.Number", "java.lang.Comparable"))
            .add(AssignabilityRulesTest.makeClass("java.lang.String", "java.lang.Object", "java.lang.Comparable"))
            .add(AssignabilityRulesTest.makeInterface("java.lang.Comparable"))
            .add(AssignabilityRulesTest.makeInterface("java.util.Collection"))
            .add(AssignabilityRulesTest.makeInterface("java.util.List", "java.util.Collection"))
            .build());

    private static ClassType classType(String name) {
        return new ClassType(DotName.of(name));
    }

    private static ParameterizedType parameterized(ClassType raw, TypeInfo... arguments) {
        return new ParameterizedType(raw.name(), List.of(arguments));
    }

    private static ParameterizedType listOf(TypeInfo argument) {
        return parameterized(LIST, argument);
    }

    private static WildcardType extending(TypeInfo bound) {
        return new WildcardType(bound, null);
    }

    private static WildcardType superOf(TypeInfo bound) {
        return new WildcardType(null, bound);
    }

    static Stream<Arguments> assignability() {
        return Stream.of(
                // A primitive and its wrapper, or its name, are identical types.
                Arguments.of(INT, INTEGER, true),
                Arguments.of(INTEGER, INT, true),
                Arguments.of(STRING, INT, false),
                Arguments.of(listOf(STRING), INT, false),
                Arguments.of(INT, STRING, false),
                Arguments.of(INT_NAME, INTEGER, true),
                Arguments.of(INTEGER, INT_NAME, true),
                Arguments.of(INT_NAME, LONG_NAME, false),
                Arguments.of(INT, INT_NAME, true),
                Arguments.of(INT, LONG_NAME, false),
                Arguments.of(INT_NAME, INT, true),
                Arguments.of(LONG_NAME, INT, false),
                Arguments.of(listOf(STRING), LONG, false),
                // A parameterized bean type to a raw required type.
                Arguments.of(listOf(OBJECT), LIST, true),
                Arguments.of(listOf(FREE_T), LIST, true),
                Arguments.of(listOf(OBJECT_T), LIST, true),
                Arguments.of(listOf(NUMBER_T), LIST, false),
                Arguments.of(listOf(new TypeVariable("T", List.of(FREE_T))), LIST, true),
                Arguments.of(listOf(STRING), LIST, false),
                Arguments.of(listOf(ANY), LIST, false),
                Arguments.of(parameterized(LIST, STRING), classType("java.util.Collection"), true),
                // Arguments of different counts never match.
                Arguments.of(listOf(STRING), parameterized(LIST, STRING, STRING), false),
                // A required type variable argument.
                // ... whose upper bound must be assignable to the bean's.
                Arguments.of(listOf(NUMBER_T), listOf(new TypeVariable("U", List.of(OBJECT))), false),
                Arguments.of(listOf(OBJECT_T), listOf(new TypeVariable("U", List.of(NUMBER))), true),
                Arguments.of(listOf(STRING), listOf(new TypeVariable("U", List.of(OBJECT))), false),
                // A required wildcard argument against a bean type variable, wildcard or actual type.
                Arguments.of(listOf(NUMBER_T), listOf(extending(OBJECT)), true),
                Arguments.of(listOf(NUMBER_T), listOf(extending(STRING)), false),
                Arguments.of(listOf(NUMBER_T), listOf(superOf(INTEGER)), true),
                Arguments.of(listOf(NUMBER_T), listOf(superOf(STRING)), false),
                Arguments.of(listOf(NUMBER_T), listOf(ANY), true),
                Arguments.of(listOf(FREE_T), listOf(extending(NUMBER)), false),
                Arguments.of(listOf(ANY), listOf(extending(OBJECT)), true),
                Arguments.of(listOf(extending(INTEGER)), listOf(extending(NUMBER)), true),
                Arguments.of(listOf(INTEGER), listOf(superOf(INTEGER)), true),
                Arguments.of(listOf(NUMBER), listOf(superOf(STRING)), false),
                Arguments.of(listOf(STRING), listOf(extending(NUMBER)), false),
                // A required actual type argument against a bean type variable or wildcard.
                Arguments.of(listOf(NUMBER_T), listOf(NUMBER), true),
                Arguments.of(listOf(NUMBER_T), listOf(INTEGER), true),
                Arguments.of(listOf(NUMBER_T), listOf(STRING), false),
                Arguments.of(listOf(extending(STRING)), listOf(NUMBER), false),
                Arguments.of(listOf(extending(NUMBER)), listOf(NUMBER), true),
                Arguments.of(listOf(ANY), listOf(NUMBER), false),
                // Arrays: same dimensions, then element rules.
                Arguments.of(STRING, new ArrayType(STRING, 1), false),
                Arguments.of(new ArrayType(STRING, 2), new ArrayType(STRING, 1), false),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(ANY), 1), true),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(extending(OBJECT)), 1), true),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(extending(STRING)), 1), false),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(superOf(STRING)), 1), false),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(OBJECT), 1), true),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(STRING), 1), false),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(FREE_T), 1), true),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(OBJECT_T), 1), true),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(NUMBER_T), 1), false),
                Arguments.of(new ArrayType(LIST, 1), new ArrayType(listOf(listOf(STRING)), 1), false),
                Arguments.of(new ArrayType(STRING, 1), new ArrayType(listOf(ANY), 1), false),
                Arguments.of(new ArrayType(listOf(ANY), 1), new ArrayType(LIST, 1), true),
                Arguments.of(new ArrayType(listOf(ANY), 1), new ArrayType(STRING, 1), false),
                Arguments.of(new ArrayType(INT, 1), new ArrayType(INT_NAME, 1), true),
                Arguments.of(new ArrayType(INT, 1), new ArrayType(listOf(ANY), 1), false),
                Arguments.of(new ArrayType(listOf(NUMBER_T), 1), new ArrayType(listOf(NUMBER), 1), true));
    }

    @ParameterizedTest
    @MethodSource("assignability")
    void aBeanTypeIsAssignableToARequiredTypeAsTheSpecificationRules(TypeInfo bean, TypeInfo required,
            boolean expected) {
        assertEquals(expected, rules.isAssignable(bean, required));
    }

    static Stream<Arguments> beanTypeMatching() {
        return Stream.of(
                Arguments.of(INT_NAME, INTEGER, true),
                Arguments.of(STRING, OBJECT, false),
                Arguments.of(listOf(OBJECT), LIST, true),
                // A primitive bean type is boxed before it gets here: it does not match a class by itself.
                Arguments.of(INT, INTEGER, false));
    }

    @ParameterizedTest
    @MethodSource("beanTypeMatching")
    void aBeanTypeMatchesARequiredClassOnlyByNameOrPrimitiveWrapping(TypeInfo bean, TypeInfo required,
            boolean expected) {
        assertEquals(expected, rules.beanTypeMatches(bean, required));
    }

    @Test
    void aClassNeitherIndexedNorLoadableIsNoSubtype() {
        assertFalse(rules.isSubtypeOf(DotName.of("com.acme.Missing"), NUMBER.name()));
    }
}
