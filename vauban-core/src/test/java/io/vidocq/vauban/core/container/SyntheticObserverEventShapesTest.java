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

import io.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder;
import io.vidocq.vauban.core.extensions.VaubanTypes;
import io.vidocq.vauban.core.langmodel.types.VaubanTypeVariable;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Synthetic observers an extension adds in {@code @Synthesis} for the observed event types CDI allows beyond classes
 * and parameterized types of classes: a parameterized type with a wildcard argument, generic array types (of a
 * parameterized type, of a type variable) and an array of a class (CDI 4.1, sections 10.1 and 10.3.1; VAU-OBS-003).
 */
@DisplayName("Synthetic observers of wildcard-parameterized and array event types (VAU-OBS-003)")
class SyntheticObserverEventShapesTest {

    static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

    public static class RecordingObserver implements SyntheticObserver<Object> {
        @Override
        public void observe(EventContext<Object> event, Parameters params) {
            var payload = event.getEvent();
            OBSERVED.add(payload instanceof Object[] array ? Arrays.toString(array) : String.valueOf(payload));
        }
    }

    public static class WildcardObservers implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components, Types types) {
            var listOfNumbers = types.parameterized(List.class, types.wildcardWithUpperBound(types.of(Number.class)));
            components.addObserver(listOfNumbers).observeWith(RecordingObserver.class);
        }
    }

    public static class GenericArrayObservers implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components, Types types) {
            var arrayOfStringLists = types.ofArray(types.parameterized(List.class, String.class), 1);
            components.addObserver(arrayOfStringLists).observeWith(RecordingObserver.class);
        }
    }

    public static class ClassArrayObservers implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components, Types types) {
            components.addObserver(types.ofArray(types.of(String.class), 1)).observeWith(RecordingObserver.class);
        }
    }

    public static class TypeVariableArrayObservers implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components, Types types) {
            var typeVariable = new VaubanTypeVariable("N", List.of(types.of(Number.class)));
            components.addObserver(types.ofArray(typeVariable, 1)).observeWith(RecordingObserver.class);
        }
    }

    @BeforeEach
    void reset() {
        OBSERVED.clear();
    }

    @Test
    @DisplayName("an observer of List<? extends Number> receives a List<Integer> and not a List<String>")
    void wildcardParameterizedObserver() {
        try (var container = VaubanContainer.builder().addBeanClass(WildcardObservers.class).build()) {
            var event = container.getBeanManager().getEvent();
            event.select(new TypeLiteral<List<Integer>>() { }).fire(List.of(1));
            event.select(new TypeLiteral<List<String>>() { }).fire(List.of("a"));

            assertEquals(List.of("[1]"), List.copyOf(OBSERVED));
        }
    }

    @Test
    @DisplayName("an observer of the generic array type List<String>[] receives an array of string lists")
    void genericArrayObserver() {
        try (var container = VaubanContainer.builder().addBeanClass(GenericArrayObservers.class).build()) {
            @SuppressWarnings("unchecked")
            List<String>[] lists = new List[]{List.of("a")};
            container.getBeanManager().getEvent().select(new TypeLiteral<List<String>[]>() { }).fire(lists);

            assertEquals(List.of("[[a]]"), List.copyOf(OBSERVED));
        }
    }

    @Test
    @DisplayName("an observer of String[] receives an array of strings")
    void classArrayObserver() {
        try (var container = VaubanContainer.builder().addBeanClass(ClassArrayObservers.class).build()) {
            container.getBeanManager().getEvent().select(String[].class).fire(new String[]{"a", "b"});

            assertEquals(List.of("[a, b]"), List.copyOf(OBSERVED));
        }
    }

    @Test
    @DisplayName("an observer of N[], N extends Number, receives an Integer[] and not a String[]")
    void typeVariableArrayObserver() {
        try (var container = VaubanContainer.builder().addBeanClass(TypeVariableArrayObservers.class).build()) {
            var event = container.getBeanManager().getEvent();
            event.select(Integer[].class).fire(new Integer[]{1});
            event.select(String[].class).fire(new String[]{"a"});

            assertEquals(List.of("[1]"), List.copyOf(OBSERVED));
        }
    }

    @Test
    @DisplayName("the reflective form of a wildcard argument keeps its bounds, and that of a generic array its component")
    void reflectiveFormsOfWildcardsAndGenericArrays() {
        var types = new VaubanTypes(null);
        var superInteger = wildcardArgument(types.parameterized(List.class,
                types.wildcardWithLowerBound(types.of(Integer.class))));
        var unbounded = wildcardArgument(types.parameterized(List.class, types.wildcardUnbounded()));
        var array = (GenericArrayType) new VaubanSyntheticObserverBuilder<>(
                types.ofArray(types.parameterized(List.class, String.class), 1)).getEventType();

        assertEquals(List.of(Object.class), List.of(superInteger.getUpperBounds()));
        assertEquals(List.of(Integer.class), List.of(superInteger.getLowerBounds()));
        assertEquals(List.of(), List.of(unbounded.getLowerBounds()));
        assertEquals("java.util.List<java.lang.String>", array.getGenericComponentType().getTypeName());
    }

    private static WildcardType wildcardArgument(jakarta.enterprise.lang.model.types.Type parameterized) {
        var eventType = (ParameterizedType) new VaubanSyntheticObserverBuilder<>(parameterized).getEventType();
        return (WildcardType) eventType.getActualTypeArguments()[0];
    }
}
