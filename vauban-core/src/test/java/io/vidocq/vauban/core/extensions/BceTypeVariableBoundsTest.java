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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-33: a type variable the language model reaches through a type has the bounds its type parameter
 * declares, {@code java.lang.Object} when it declares none: "never {@code null} or empty". As the bound of another
 * type variable, as the type of a field, a parameter or a return, a method's own or its class's; and inside its own
 * bound, where it is the same type variable, with the same bounds, read when asked.
 */
@DisplayName("BUG-20261117-33 - a type variable reached through a type has its bounds")
class BceTypeVariableBoundsTest {

    public static class Bounds<A, B extends A, S extends Comparable<S>> {
        B chained;
        S selfBounded;

        <T> T method(T value) {
            return value;
        }
    }

    @Test
    @DisplayName("a bound naming another type parameter, a method's own type variable, a self-bounded one")
    void typeVariablesReachedThroughATypeHaveTheirBounds() {
        assertEquals(List.of("chained B extends A extends java.lang.Object",
                        "method T extends java.lang.Object (T extends java.lang.Object)",
                        "selfBounded S extends java.lang.Comparable<S ...>"),
                BceEnumMembersTest.seenAtStart(Bounds.class, BceTypeVariableBoundsTest::described));
    }

    @Test
    @DisplayName("a self-bounded type variable inside its own bound is the same type variable")
    void aSelfBoundedTypeVariableInsideItsBound() {
        var seen = new java.util.concurrent.CopyOnWriteArrayList<TypeVariable>();
        BceEnumMembersTest.seenAtStart(Bounds.class, type -> {
            var outer = type.fields().stream().filter(field -> field.name().equals("selfBounded")).findFirst()
                    .orElseThrow().type().asTypeVariable();
            seen.add(outer);
            seen.add(argumentOfBound(outer));
            return List.of();
        });
        var outer = seen.get(0);
        var inner = seen.get(1);
        var again = argumentOfBound(inner);
        assertAll(() -> assertEquals(outer, inner), () -> assertEquals(inner, outer),
                () -> assertEquals(inner, again), () -> assertEquals(outer.hashCode(), inner.hashCode()),
                () -> assertEquals("S", inner.toString()), () -> assertTrue(inner.annotations().isEmpty()),
                () -> assertEquals("S extends java.lang.Comparable<S>", outer.toString()),
                () -> assertEquals(outer.bounds(), inner.bounds()),
                () -> assertTrue(outer.bounds().getFirst().isParameterizedType()));
    }

    @Test
    @DisplayName("an extension's type variable maps back to the index's, without the bounds the index leaves out")
    void aTypeVariableMapsBackToTheIndex() {
        var seen = new java.util.concurrent.CopyOnWriteArrayList<TypeInfo>();
        BceEnumMembersTest.seenAtStart(Bounds.class, type -> {
            type.fields().stream().filter(field -> field.declaringClass().name().equals(type.name()))
                    .forEach(field -> seen.add(LangModelTypeMapper.toIndexType(field.type())));
            return List.of();
        });
        var a = new TypeInfo.TypeVariable("A", List.of());
        var s = new TypeInfo.TypeVariable("S", List.of());
        assertEquals(List.of(new TypeInfo.TypeVariable("B", List.of(a)), new TypeInfo.TypeVariable("S",
                List.of(new TypeInfo.ParameterizedType(DotName.of("java.lang.Comparable"), List.of(s))))), seen);
    }

    /** The first type argument of the first bound of {@code variable}: {@code S} in {@code S extends Comparable<S>}. */
    private static TypeVariable argumentOfBound(TypeVariable variable) {
        return variable.bounds().getFirst().asParameterizedType().typeArguments().getFirst().asTypeVariable();
    }

    private static List<String> described(ClassInfo type) {
        var lines = type.fields().stream().filter(field -> field.declaringClass().name().equals(type.name()))
                .map(field -> field.name() + " " + of(field.type(), new HashSet<>()))
                .collect(Collectors.toList());
        type.methods().stream().filter(method -> method.name().equals("method"))
                .forEach(method -> lines.add(method.name() + " " + of(method.returnType(), new HashSet<>()) + " ("
                        + of(method.parameters().getFirst().type(), new HashSet<>()) + ")"));
        lines.sort(null);
        return lines;
    }

    /** A type, a type variable with its bounds, but those of one met again inside them, written {@code ...}. */
    private static String of(Type type, Set<String> reading) {
        return switch (type.kind()) {
            case TYPE_VARIABLE -> {
                var variable = type.asTypeVariable();
                if (!reading.add(variable.name())) {
                    yield variable.name() + " ...";
                }
                var bounds = variable.bounds().stream().map(bound -> of(bound, reading))
                        .collect(Collectors.joining(" & "));
                reading.remove(variable.name());
                yield variable.name() + " extends " + bounds;
            }
            case PARAMETERIZED_TYPE -> type.asParameterizedType().genericClass().declaration().name()
                    + type.asParameterizedType().typeArguments().stream().map(argument -> of(argument, reading))
                            .collect(Collectors.joining(", ", "<", ">"));
            case CLASS -> type.asClass().declaration().name();
            default -> type.kind().name();
        };
    }
}
