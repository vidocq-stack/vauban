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
package io.vidocq.vauban.core.container;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@code VaubanBeanManager.typesMatch(beanType, requiredType)} answers for each shape of
 * required type (class, parameterized type, generic array, anything else), so that splitting the
 * method cannot change a single answer.
 */
@DisplayName("VaubanBeanManager.typesMatch - characterization")
class TypesMatchCharacterizationTest {

    /** The generic types under test, read from the return types of these methods. */
    interface Types<T, N extends Number> {
        List<Object> listOfObject();
        List<T> listOfT();
        List<N> listOfN();
        List<String> listOfString();
        List<String> otherListOfString();
        List<Integer> listOfInteger();
        List<?> listOfAny();
        List<? extends Number> listOfExtendsNumber();
        List<? super Integer> listOfSuperInteger();
        ArrayList<String> arrayListOfString();
        Map<String, String> mapOfString();
        Class<?>[] arrayOfClassOfAny();
        List<String>[] arrayOfListOfString();
        List<Integer>[] arrayOfListOfInteger();
        T variable();
    }

    private static final Type LIST_OF_OBJECT = type("listOfObject");
    private static final Type LIST_OF_T = type("listOfT");
    private static final Type LIST_OF_N = type("listOfN");
    private static final Type LIST_OF_STRING = type("listOfString");
    private static final Type OTHER_LIST_OF_STRING = type("otherListOfString");
    private static final Type LIST_OF_INTEGER = type("listOfInteger");
    private static final Type LIST_OF_ANY = type("listOfAny");
    private static final Type LIST_OF_EXTENDS_NUMBER = type("listOfExtendsNumber");
    private static final Type LIST_OF_SUPER_INTEGER = type("listOfSuperInteger");
    private static final Type ARRAY_LIST_OF_STRING = type("arrayListOfString");
    private static final Type MAP_OF_STRING = type("mapOfString");
    private static final Type ARRAY_OF_CLASS_OF_ANY = type("arrayOfClassOfAny");
    private static final Type ARRAY_OF_LIST_OF_STRING = type("arrayOfListOfString");
    private static final Type ARRAY_OF_LIST_OF_INTEGER = type("arrayOfListOfInteger");
    private static final Type VARIABLE = type("variable");

    private static Type type(String method) {
        try {
            return Types.class.getMethod(method).getGenericReturnType();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean matches(Type beanType, Type requiredType) {
        return VaubanBeanManager.typesMatch(beanType, requiredType);
    }

    @Test
    @DisplayName("equal types match, primitives match their wrappers")
    void equalAndPrimitiveTypes() {
        assertTrue(matches(String.class, String.class));
        assertTrue(matches(LIST_OF_STRING, OTHER_LIST_OF_STRING));
        assertTrue(matches(Integer.class, int.class));
        assertTrue(matches(int.class, Integer.class));
        assertFalse(matches(Long.class, int.class));
    }

    @Nested
    @DisplayName("required type is a class")
    class RequiredClass {

        @Test
        @DisplayName("a bean class matches only the same class")
        void beanClass() {
            assertFalse(matches(String.class, Object.class));
            assertFalse(matches(ArrayList.class, List.class));
        }

        @Test
        @DisplayName("a parameterized bean type matches when its arguments are Object or unbounded variables")
        void parameterizedBeanType() {
            assertTrue(matches(LIST_OF_OBJECT, List.class));
            assertTrue(matches(LIST_OF_T, List.class));
            assertFalse(matches(LIST_OF_N, List.class));
            assertFalse(matches(LIST_OF_STRING, List.class));
            assertFalse(matches(MAP_OF_STRING, List.class));
        }

        @Test
        @DisplayName("any other bean type does not match")
        void otherBeanType() {
            assertFalse(matches(ARRAY_OF_LIST_OF_STRING, List.class));
            assertFalse(matches(VARIABLE, Object.class));
        }
    }

    @Nested
    @DisplayName("required type is parameterized")
    class RequiredParameterized {

        @Test
        @DisplayName("a parameterized bean type needs the same raw type and matching arguments")
        void parameterizedBeanType() {
            assertFalse(matches(LIST_OF_INTEGER, LIST_OF_STRING));
            assertFalse(matches(ARRAY_LIST_OF_STRING, LIST_OF_STRING));
            assertTrue(matches(LIST_OF_INTEGER, LIST_OF_EXTENDS_NUMBER));
            assertFalse(matches(LIST_OF_STRING, LIST_OF_EXTENDS_NUMBER));
        }

        @Test
        @DisplayName("a raw bean type matches when every required argument is unbounded")
        void rawBeanType() {
            assertTrue(matches(List.class, LIST_OF_OBJECT));
            assertTrue(matches(List.class, LIST_OF_ANY));
            assertTrue(matches(List.class, LIST_OF_T));
            assertFalse(matches(List.class, LIST_OF_N));
            assertFalse(matches(List.class, LIST_OF_EXTENDS_NUMBER));
            assertFalse(matches(List.class, LIST_OF_SUPER_INTEGER));
            assertFalse(matches(List.class, LIST_OF_STRING));
            assertFalse(matches(ArrayList.class, LIST_OF_OBJECT));
        }

        @Test
        @DisplayName("any other bean type does not match")
        void otherBeanType() {
            assertFalse(matches(ARRAY_OF_LIST_OF_STRING, LIST_OF_STRING));
            assertFalse(matches(VARIABLE, LIST_OF_OBJECT));
        }
    }

    @Nested
    @DisplayName("required type is a generic array")
    class RequiredGenericArray {

        @Test
        @DisplayName("an erased array class matches a component with unbounded arguments only")
        void arrayClass() {
            assertTrue(matches(Class[].class, ARRAY_OF_CLASS_OF_ANY));
            assertFalse(matches(String[].class, ARRAY_OF_CLASS_OF_ANY));
            assertFalse(matches(List[].class, ARRAY_OF_LIST_OF_STRING));
            assertFalse(matches(String.class, ARRAY_OF_CLASS_OF_ANY));
        }

        @Test
        @DisplayName("a generic array bean type matches by type name")
        void genericArrayBeanType() {
            assertFalse(matches(ARRAY_OF_LIST_OF_INTEGER, ARRAY_OF_LIST_OF_STRING));
            assertFalse(matches(LIST_OF_STRING, ARRAY_OF_LIST_OF_STRING));
        }
    }

    @Test
    @DisplayName("a type variable, a wildcard or no required type matches nothing but itself")
    void otherRequiredTypes() {
        assertTrue(matches(VARIABLE, VARIABLE));
        assertFalse(matches(Object.class, VARIABLE));
        assertFalse(matches(LIST_OF_STRING, ((java.lang.reflect.ParameterizedType) LIST_OF_ANY)
                .getActualTypeArguments()[0]));
        assertFalse(matches(String.class, null));
    }
}
