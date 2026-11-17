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

import io.vidocq.vauban.core.langmodel.types.VaubanPrimitiveType;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.model.TypeInfo.ArrayType;
import io.vidocq.vauban.indexer.model.TypeInfo.ClassType;
import io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType;
import io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType;
import io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable;
import io.vidocq.vauban.indexer.model.TypeInfo.WildcardType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final ClassType COLLECTION = classType("java.util.Collection");
    private static final ClassType INT_NAME = classType("int");
    private static final ClassType LONG_NAME = classType("long");
    private static final PrimitiveType INT = new PrimitiveType(PrimitiveType.Kind.INT);
    private static final PrimitiveType LONG = new PrimitiveType(PrimitiveType.Kind.LONG);
    private static final TypeVariable FREE_T = new TypeVariable("T", List.of());
    private static final TypeVariable OBJECT_T = new TypeVariable("T", List.of(OBJECT));
    private static final TypeVariable NUMBER_T = new TypeVariable("T", List.of(NUMBER));
    private static final WildcardType ANY = new WildcardType(null, null);
    /** {@code T extends U}, {@code U} unbounded: bounded, as Java and Weld see it. */
    private static final TypeVariable T_OF_FREE_U = new TypeVariable("T", List.of(new TypeVariable("U", List.of())));
    /** {@code T extends U}, {@code U extends Number}: its uppermost bound is {@code Number}. */
    private static final TypeVariable T_OF_NUMBER_U = new TypeVariable("T",
            List.of(new TypeVariable("U", List.of(NUMBER))));
    /** {@code T extends Comparable<T>}: the index gives the type variable no bound where it meets it again. */
    private static final TypeVariable COMPARABLE_T = new TypeVariable("T",
            List.of(new ParameterizedType(DotName.of("java.lang.Comparable"), List.of(FREE_T))));

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
                // T extends U is bounded, U unbounded or not: a type variable is unbounded when it declares no bound
                // but Object (JLS 4.4; Weld's isUnboundedTypeVariable; BUG-20261117-16).
                Arguments.of(listOf(T_OF_FREE_U), LIST, false),
                Arguments.of(listOf(new TypeVariable("T", List.of(NUMBER_T))), LIST, false),
                Arguments.of(listOf(COMPARABLE_T), LIST, false),
                Arguments.of(listOf(STRING), LIST, false),
                Arguments.of(listOf(ANY), LIST, false),
                // A raw bean type to a parameterized required type: each argument Object or an unbounded type
                // variable, never a wildcard (CDI 4.1, 5.2.4; BUG-20261117-16).
                Arguments.of(LIST, listOf(OBJECT), true),
                Arguments.of(LIST, listOf(FREE_T), true),
                Arguments.of(LIST, listOf(OBJECT_T), true),
                Arguments.of(LIST, listOf(T_OF_FREE_U), false),
                Arguments.of(LIST, listOf(NUMBER_T), false),
                Arguments.of(LIST, listOf(COMPARABLE_T), false),
                Arguments.of(LIST, listOf(STRING), false),
                Arguments.of(LIST, listOf(ANY), false),
                Arguments.of(LIST, listOf(extending(OBJECT)), false),
                Arguments.of(LIST, listOf(extending(NUMBER)), false),
                Arguments.of(LIST, listOf(superOf(INTEGER)), false),
                Arguments.of(classType("java.util.Collection"), listOf(OBJECT), false),
                Arguments.of(parameterized(LIST, STRING), classType("java.util.Collection"), true),
                // Arguments of different counts never match.
                Arguments.of(listOf(STRING), parameterized(LIST, STRING, STRING), false),
                // A required type variable argument.
                // ... whose upper bound must be assignable to the bean's.
                Arguments.of(listOf(NUMBER_T), listOf(new TypeVariable("U", List.of(OBJECT))), false),
                Arguments.of(listOf(OBJECT_T), listOf(new TypeVariable("U", List.of(NUMBER))), true),
                Arguments.of(listOf(STRING), listOf(new TypeVariable("U", List.of(OBJECT))), false),
                // ... Object for no bound on either side: (T extends java.lang.Object), as a synthetic bean's type
                // reads back, against T (BUG-20261117-16).
                Arguments.of(listOf(OBJECT_T), listOf(FREE_T), true),
                Arguments.of(listOf(FREE_T), listOf(OBJECT_T), true),
                Arguments.of(listOf(NUMBER_T), listOf(FREE_T), false),
                // A required wildcard argument against a bean type variable, wildcard or actual type.
                Arguments.of(listOf(NUMBER_T), listOf(extending(OBJECT)), true),
                Arguments.of(listOf(NUMBER_T), listOf(extending(STRING)), false),
                Arguments.of(listOf(NUMBER_T), listOf(superOf(INTEGER)), true),
                Arguments.of(listOf(NUMBER_T), listOf(superOf(STRING)), false),
                Arguments.of(listOf(NUMBER_T), listOf(ANY), true),
                // ... the type variable's upper bound assignable to or from the wildcard's (rules da and db), Object
                // when it has none (BUG-20261117-24).
                Arguments.of(listOf(FREE_T), listOf(extending(NUMBER)), true),
                Arguments.of(listOf(FREE_T), listOf(extending(OBJECT)), true),
                Arguments.of(listOf(FREE_T), listOf(superOf(INTEGER)), true),
                Arguments.of(listOf(NUMBER_T), listOf(extending(INTEGER)), true),
                Arguments.of(listOf(NUMBER_T), listOf(superOf(OBJECT)), false),
                Arguments.of(listOf(COMPARABLE_T), listOf(extending(INTEGER)), true),
                Arguments.of(listOf(COMPARABLE_T), listOf(superOf(INTEGER)), true),
                Arguments.of(listOf(COMPARABLE_T), listOf(superOf(NUMBER)), false),
                // ... the uppermost bound of a type variable bounded by another (getUppermostTypeVariableBounds).
                Arguments.of(listOf(T_OF_NUMBER_U), listOf(extending(INTEGER)), true),
                Arguments.of(listOf(T_OF_NUMBER_U), listOf(superOf(INTEGER)), true),
                Arguments.of(listOf(T_OF_NUMBER_U), listOf(superOf(STRING)), false),
                // ... an actual type within the wildcard's bound as Java sees it: a List<String> is a List.
                Arguments.of(listOf(listOf(STRING)), listOf(extending(LIST)), true),
                Arguments.of(listOf(listOf(STRING)), listOf(extending(COLLECTION)), true),
                Arguments.of(listOf(LIST), listOf(superOf(listOf(STRING))), true),
                Arguments.of(listOf(ANY), listOf(extending(OBJECT)), true),
                Arguments.of(listOf(extending(INTEGER)), listOf(extending(NUMBER)), true),
                Arguments.of(listOf(INTEGER), listOf(superOf(INTEGER)), true),
                Arguments.of(listOf(NUMBER), listOf(superOf(STRING)), false),
                Arguments.of(listOf(STRING), listOf(extending(NUMBER)), false),
                // A required actual type argument against a bean type variable or wildcard.
                Arguments.of(listOf(NUMBER_T), listOf(NUMBER), true),
                Arguments.of(listOf(NUMBER_T), listOf(INTEGER), true),
                Arguments.of(listOf(NUMBER_T), listOf(STRING), false),
                // ... the actual type assignable to a parameterized bound by its class, as BeanManager.getBeans and
                // the Java compiler see it: Integer is a Comparable<Integer> (BUG-20261117-24).
                Arguments.of(listOf(COMPARABLE_T), listOf(INTEGER), true),
                Arguments.of(listOf(COMPARABLE_T), listOf(NUMBER), false),
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
                Arguments.of(new ArrayType(listOf(NUMBER_T), 1), new ArrayType(listOf(NUMBER), 1), true),
                // A required actual type argument against the uppermost bound of a type variable bounded by another
                // (rule e, BUG-20261117-16).
                Arguments.of(listOf(T_OF_NUMBER_U), listOf(INTEGER), true),
                Arguments.of(listOf(T_OF_NUMBER_U), listOf(STRING), false),
                // ... a parameterized type within a class bound, by its class.
                Arguments.of(listOf(new TypeVariable("T", List.of(COLLECTION))), listOf(listOf(STRING)), true),
                Arguments.of(listOf(new TypeVariable("T", List.of(NUMBER))), listOf(listOf(STRING)), false),
                // An actual type argument that is parameterized: assignable by these same rules (rule b).
                Arguments.of(listOf(listOf(NUMBER_T)), listOf(listOf(INTEGER)), true),
                Arguments.of(listOf(listOf(NUMBER_T)), listOf(listOf(STRING)), false),
                Arguments.of(listOf(listOf(STRING)), listOf(listOf(INTEGER)), false));
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
                Arguments.of(listOf(COMPARABLE_T), LIST, false),
                // A raw bean type and a parameterized required type: as isAssignable (BUG-20261117-16).
                Arguments.of(LIST, listOf(STRING), false),
                Arguments.of(LIST, listOf(ANY), false),
                Arguments.of(LIST, listOf(OBJECT), true),
                Arguments.of(LIST, listOf(FREE_T), true),
                // A primitive bean type is boxed before it gets here: it does not match a class by itself.
                Arguments.of(INT, INTEGER, false));
    }

    @ParameterizedTest
    @MethodSource("beanTypeMatching")
    void aBeanTypeMatchesARequiredClassOnlyByNameOrPrimitiveWrapping(TypeInfo bean, TypeInfo required,
            boolean expected) {
        assertEquals(expected, rules.beanTypeMatches(bean, required));
    }

    /**
     * A primitive type and the class type named after it match whatever the default locale: under a Turkish one,
     * {@code "INT".toLowerCase()} is {@code "ınt"}, with a dotless i, and {@code PrimitiveType(INT)} no longer
     * matched {@code ClassType(int)}, nor named itself {@code int} to an extension (BUG-20261117-20).
     */
    @Test
    @ResourceLock(Resources.LOCALE)
    @DisplayName("a primitive type matches the class type named after it under a Turkish default locale")
    void aPrimitiveTypeMatchesTheClassTypeNamedAfterItUnderATurkishDefaultLocale() {
        var defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            assertAll(() -> assertTrue(rules.isAssignable(INT, INT_NAME), "PrimitiveType(INT) to ClassType(int)"),
                    () -> assertTrue(rules.isAssignable(INT_NAME, INT), "ClassType(int) to PrimitiveType(INT)"),
                    () -> assertTrue(rules.isAssignable(new ArrayType(INT, 1), new ArrayType(INT_NAME, 1)),
                            "int[] to an array of ClassType(int)"),
                    () -> assertEquals("int", new VaubanPrimitiveType(INT).name(), "the lang model's name"));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void aClassNeitherIndexedNorLoadableIsNoSubtype() {
        assertFalse(rules.isSubtypeOf(DotName.of("com.acme.Missing"), NUMBER.name()));
    }
}
