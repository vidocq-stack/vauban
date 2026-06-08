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
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.model.TypeInfo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AssignabilityRules - CDI 4.1 assignability rules")
class AssignabilityRulesTest {

    private AssignabilityRules rules;

    static ClassInfo makeClass(String name, String superName, String... interfaces) {
        return new ClassInfo(
                DotName.of(name),
                superName != null ? DotName.of(superName) : null,
                List.of(interfaces).stream().map(DotName::of).toList(),
                0x0001, List.of(), List.of(), List.of(),
                ClassKind.CLASS
        );
    }

    static ClassInfo makeInterface(String name, String... superInterfaces) {
        return new ClassInfo(
                DotName.of(name),
                null,
                List.of(superInterfaces).stream().map(DotName::of).toList(),
                0x0601, List.of(), List.of(), List.of(),
                ClassKind.INTERFACE
        );
    }

    @BeforeEach
    void setUp() {
        var builder = new IndexBuilder();
        builder.add(makeClass("java.lang.Object", null));
        builder.add(makeClass("java.lang.Number", "java.lang.Object"));
        builder.add(makeClass("java.lang.Integer", "java.lang.Number", "java.lang.Comparable"));
        builder.add(makeClass("java.lang.String", "java.lang.Object", "java.lang.Comparable", "java.io.Serializable"));
        builder.add(makeInterface("java.lang.Comparable"));
        builder.add(makeInterface("java.io.Serializable"));
        builder.add(makeInterface("java.util.Collection", "java.lang.Iterable"));
        builder.add(makeInterface("java.util.List", "java.util.Collection"));
        builder.add(makeInterface("java.lang.Iterable"));
        rules = new AssignabilityRules(builder.build());
    }

    @Nested
    @DisplayName("simple types (ClassType)")
    class SimpleTypes {

        @Test
        @DisplayName("a type is assignable to itself")
        void sameType() {
            var string = new ClassType(DotName.of("java.lang.String"));
            assertTrue(rules.isAssignable(string, string));
        }

        @Test
        @DisplayName("a subtype is assignable to its supertype")
        void subtypeAssignable() {
            var integer = new ClassType(DotName.of("java.lang.Integer"));
            var number = new ClassType(DotName.of("java.lang.Number"));
            assertTrue(rules.isAssignable(integer, number));
        }

        @Test
        @DisplayName("a supertype is NOT assignable to its subtype")
        void supertypeNotAssignable() {
            var number = new ClassType(DotName.of("java.lang.Number"));
            var integer = new ClassType(DotName.of("java.lang.Integer"));
            assertFalse(rules.isAssignable(number, integer));
        }

        @Test
        @DisplayName("a class is assignable to an implemented interface")
        void classToInterface() {
            var integer = new ClassType(DotName.of("java.lang.Integer"));
            var comparable = new ClassType(DotName.of("java.lang.Comparable"));
            assertTrue(rules.isAssignable(integer, comparable));
        }

        @Test
        @DisplayName("everything is assignable to Object")
        void assignableToObject() {
            var string = new ClassType(DotName.of("java.lang.String"));
            var object = new ClassType(DotName.of("java.lang.Object"));
            assertTrue(rules.isAssignable(string, object));
        }

        @Test
        @DisplayName("a sub-interface is assignable to a super-interface")
        void subInterfaceToSuperInterface() {
            var list = new ClassType(DotName.of("java.util.List"));
            var collection = new ClassType(DotName.of("java.util.Collection"));
            var iterable = new ClassType(DotName.of("java.lang.Iterable"));
            assertTrue(rules.isAssignable(list, collection));
            assertTrue(rules.isAssignable(list, iterable));
        }
    }

    @Nested
    @DisplayName("parameterized types (ParameterizedType)")
    class ParameterizedTypes {

        @Test
        @DisplayName("List<String> is assignable to List<String>")
        void sameParameterizedType() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            assertTrue(rules.isAssignable(listString, listString));
        }

        @Test
        @DisplayName("List<String> is NOT assignable to List<Integer>")
        void differentTypeArgs() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listInteger = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Integer"))));
            assertFalse(rules.isAssignable(listString, listInteger));
        }

        @Test
        @DisplayName("List<String> is NOT assignable to List<Object> (invariance)")
        void genericInvariance() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listObject = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Object"))));
            assertFalse(rules.isAssignable(listString, listObject));
        }
    }

    @Nested
    @DisplayName("wildcards")
    class Wildcards {

        @Test
        @DisplayName("List<String> is assignable to List<? extends Object>")
        void extendsWildcard() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listExtendsObject = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(
                            new ClassType(DotName.of("java.lang.Object")), null)));
            assertTrue(rules.isAssignable(listString, listExtendsObject));
        }

        @Test
        @DisplayName("List<Integer> is assignable to List<? extends Number>")
        void extendsWildcardNumber() {
            var listInteger = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Integer"))));
            var listExtendsNumber = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(
                            new ClassType(DotName.of("java.lang.Number")), null)));
            assertTrue(rules.isAssignable(listInteger, listExtendsNumber));
        }

        @Test
        @DisplayName("List<Number> is assignable to List<? super Integer>")
        void superWildcard() {
            var listNumber = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Number"))));
            var listSuperInteger = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(
                            null, new ClassType(DotName.of("java.lang.Integer")))));
            assertTrue(rules.isAssignable(listNumber, listSuperInteger));
        }

        @Test
        @DisplayName("List<String> is assignable to List<?>")
        void unboundedWildcard() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listWildcard = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(null, null)));
            assertTrue(rules.isAssignable(listString, listWildcard));
        }
    }

    @Nested
    @DisplayName("raw types and arrays")
    class RawAndArrayTypes {

        @Test
        @DisplayName("raw List is assignable to List<String> (allowed by CDI)")
        void rawToParameterized() {
            var rawList = new ClassType(DotName.of("java.util.List"));
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            assertTrue(rules.isAssignable(rawList, listString));
        }

        @Test
        @DisplayName("int[] is assignable to int[]")
        void sameArrayType() {
            var intArr = new ArrayType(new PrimitiveType(PrimitiveType.Kind.INT), 1);
            assertTrue(rules.isAssignable(intArr, intArr));
        }

        @Test
        @DisplayName("int[] is NOT assignable to long[]")
        void differentArrayComponentType() {
            var intArr = new ArrayType(new PrimitiveType(PrimitiveType.Kind.INT), 1);
            var longArr = new ArrayType(new PrimitiveType(PrimitiveType.Kind.LONG), 1);
            assertFalse(rules.isAssignable(intArr, longArr));
        }
    }

    @Nested
    @DisplayName("subtype hierarchy (isSubtypeOf)")
    class SubtypeHierarchy {

        @Test
        @DisplayName("Integer -> Number -> Object")
        void integerHierarchy() {
            assertTrue(rules.isSubtypeOf(DotName.of("java.lang.Integer"), DotName.of("java.lang.Number")));
            assertTrue(rules.isSubtypeOf(DotName.of("java.lang.Integer"), DotName.of("java.lang.Object")));
            assertTrue(rules.isSubtypeOf(DotName.of("java.lang.Integer"), DotName.of("java.lang.Comparable")));
        }

        @Test
        @DisplayName("List -> Collection -> Iterable")
        void listHierarchy() {
            assertTrue(rules.isSubtypeOf(DotName.of("java.util.List"), DotName.of("java.util.Collection")));
            assertTrue(rules.isSubtypeOf(DotName.of("java.util.List"), DotName.of("java.lang.Iterable")));
        }
    }
}
