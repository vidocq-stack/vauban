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
package io.vidocq.vauban.core.event;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which observed event type accepts which fired event type (CDI 4.1 §10.3.1): raw, primitive, parameterized,
 * type-variable and wildcard observed types against the runtime or selected type of the event, and the
 * unresolvable type variables a selected type may carry.
 */
class EventDispatcherTypeMatchingTest {

    private static final String MISSING = "com.acme.Missing";
    private static final TypeInfo STRING = classType("java.lang.String");
    private static final TypeInfo INTEGER = classType("java.lang.Integer");
    private static final TypeInfo NUMBER = classType("java.lang.Number");
    private static final TypeInfo OBJECT = classType("java.lang.Object");
    private static final TypeInfo MISSING_TYPE = classType(MISSING);
    private static final String LIST_OF_STRING = "listOfString";
    private static final String LIST_OF_INTEGER = "listOfInteger";
    private static final String LIST_OF_T = "listOfT";

    private static VaubanContainer container;

    @BeforeAll
    static void boot() {
        container = VaubanContainer.builder().build();
    }

    @AfterAll
    static void shutdown() {
        container.close();
    }

    /** Fired types, read off these fields' declarations. */
    @SuppressWarnings({"unused", "java:S1068"})
    private static final class Fired<T> {
        List<String> listOfString;
        List<Integer> listOfInteger;
        List<Number> listOfNumber;
        List<T> listOfT;
        List<? extends String> listOfWildcardString;
        List<List<String>> listOfListOfString;
        List<Set<String>> listOfSetOfString;
        Map<String, Integer> mapOfStringToInteger;
        ArrayList<T[]> arrayListOfTArray;
        ArrayList<? extends T> arrayListOfExtendsT;
        ArrayList<? super T> arrayListOfSuperT;
        ArrayList<? extends Number> arrayListOfExtendsNumber;
    }

    /** A class implementing the raw {@link Comparable}: no parameterization of it to compare with. */
    @SuppressWarnings({"rawtypes", "java:S3740"})
    private static final class RawComparable implements Comparable {
        @Override
        public int compareTo(Object other) {
            return 0;
        }
    }

    private static TypeInfo classType(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    private static TypeInfo parameterized(Class<?> raw, TypeInfo... arguments) {
        return new TypeInfo.ParameterizedType(DotName.of(raw.getName()), List.of(arguments));
    }

    private static TypeInfo listOf(TypeInfo argument) {
        return parameterized(List.class, argument);
    }

    private static Type fired(String field) {
        try {
            return Fired.class.getDeclaredField(field).getGenericType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(field, e);
        }
    }

    private static boolean observes(TypeInfo observed, Type event) {
        var observer = new ObserverDescriptor(DotName.of("com.acme.Observer"), "on", observed, List.of(), false, 0);
        var dispatcher = new EventDispatcher(List.of(observer), container);
        return !dispatcher.findMatchingObservers(event, false, Set.of()).isEmpty();
    }

    static Stream<Arguments> observedAgainstFired() {
        var wildcardOfNumber = new TypeInfo.WildcardType(NUMBER, null);
        return Stream.of(
                Arguments.of(classType("int"), int.class, true),
                Arguments.of(MISSING_TYPE, String.class, false),
                Arguments.of(INTEGER, String.class, false),
                Arguments.of(new TypeInfo.ParameterizedType(DotName.of(MISSING), List.of()), String.class, false),
                Arguments.of(new TypeInfo.ArrayType(MISSING_TYPE, 1), String[].class, false),
                Arguments.of(new TypeInfo.TypeVariable("T", List.of(new TypeInfo.TypeVariable("U", List.of()))),
                        String.class, true),
                Arguments.of(new TypeInfo.TypeVariable("T", List.of(new TypeInfo.TypeVariable("U", List.of(NUMBER)))),
                        String.class, false),
                Arguments.of(new TypeInfo.WildcardType(null, null), String.class, true),
                Arguments.of(wildcardOfNumber, String.class, false),
                Arguments.of(wildcardOfNumber, Integer.class, true),
                Arguments.of(parameterized(Comparable.class, STRING), RawComparable.class, true),
                Arguments.of(parameterized(Map.class, STRING), fired("mapOfStringToInteger"), false),
                // A type variable argument: within its bounds, or unknown.
                Arguments.of(listOf(new TypeInfo.TypeVariable("T", List.of(NUMBER))), fired(LIST_OF_INTEGER), true),
                Arguments.of(listOf(new TypeInfo.TypeVariable("T", List.of(NUMBER))), fired(LIST_OF_STRING), false),
                Arguments.of(listOf(new TypeInfo.TypeVariable("T", List.of(NUMBER))), fired(LIST_OF_T), true),
                // A wildcard argument: its upper bound, Object or not, then its lower bound.
                Arguments.of(listOf(wildcardOfNumber), fired(LIST_OF_STRING), false),
                Arguments.of(listOf(wildcardOfNumber), fired(LIST_OF_INTEGER), true),
                Arguments.of(listOf(wildcardOfNumber), fired(LIST_OF_T), true),
                Arguments.of(listOf(new TypeInfo.WildcardType(OBJECT, null)), fired(LIST_OF_STRING), true),
                Arguments.of(listOf(new TypeInfo.WildcardType(parameterized(Comparable.class, STRING), null)),
                        fired(LIST_OF_STRING), true),
                Arguments.of(listOf(new TypeInfo.WildcardType(null, INTEGER)), fired("listOfNumber"), true),
                Arguments.of(listOf(new TypeInfo.WildcardType(null, INTEGER)), fired(LIST_OF_STRING), false),
                Arguments.of(listOf(new TypeInfo.WildcardType(null, MISSING_TYPE)), fired(LIST_OF_STRING), true),
                Arguments.of(listOf(new TypeInfo.WildcardType(null, INTEGER)), fired(LIST_OF_T), true),
                // A class argument, matched by name.
                Arguments.of(listOf(STRING), fired(LIST_OF_STRING), true),
                Arguments.of(listOf(STRING), fired(LIST_OF_INTEGER), false),
                Arguments.of(listOf(STRING), fired("listOfWildcardString"), true),
                // A parameterized argument, matched by raw name then arguments.
                Arguments.of(listOf(listOf(STRING)), fired("listOfListOfString"), true),
                Arguments.of(listOf(listOf(STRING)), fired("listOfSetOfString"), false),
                Arguments.of(listOf(listOf(STRING)), fired(LIST_OF_STRING), false),
                // Any other argument shape is not narrowed.
                Arguments.of(listOf(new TypeInfo.ArrayType(STRING, 1)), fired(LIST_OF_STRING), true));
    }

    @ParameterizedTest
    @MethodSource("observedAgainstFired")
    void anObservedTypeAcceptsOnlyTheEventTypesAssignableToIt(TypeInfo observed, Type event, boolean expected) {
        assertEquals(expected, observes(observed, event));
    }

    @ParameterizedTest
    @EnumSource(TypeInfo.PrimitiveType.Kind.class)
    void aPrimitiveObservedTypeAcceptsItsOwnPrimitive(TypeInfo.PrimitiveType.Kind kind) {
        var primitive = switch (kind) {
            case BOOLEAN -> boolean.class;
            case BYTE -> byte.class;
            case CHAR -> char.class;
            case SHORT -> short.class;
            case INT -> int.class;
            case LONG -> long.class;
            case FLOAT -> float.class;
            case DOUBLE -> double.class;
        };

        assertTrue(observes(new TypeInfo.PrimitiveType(kind), primitive));
    }

    @Test
    void aFiredTypeWhoseArgumentsCannotBeReadIsNotNarrowed() {
        Type unreadable = new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                throw new IllegalStateException("unreadable arguments");
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };

        assertTrue(observes(listOf(STRING), unreadable));
    }

    static Stream<String> unresolvableSelectedTypes() {
        return Stream.of("arrayListOfTArray", "arrayListOfExtendsT", "arrayListOfSuperT");
    }

    @ParameterizedTest
    @MethodSource("unresolvableSelectedTypes")
    void aSelectedTypeWithAnUnresolvableTypeVariableIsRefused(String field) {
        var dispatcher = new EventDispatcher(List.of(), container);
        var selected = fired(field);
        var event = new ArrayList<String>();

        var failure = assertThrows(IllegalArgumentException.class, () -> dispatcher.fire(event, selected, null));

        assertTrue(failure.getMessage().startsWith("Event type contains unresolvable type variable"),
                failure.getMessage());
    }

    @Test
    void aSelectedTypeWithResolvedArgumentsIsAccepted() {
        var dispatcher = new EventDispatcher(List.of(), container);
        var selected = fired("arrayListOfExtendsNumber");
        var event = new ArrayList<Integer>();

        assertDoesNotThrow(() -> dispatcher.fire(event, selected, null));
    }
}
