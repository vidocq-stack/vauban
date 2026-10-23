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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the synthetic metadata writes the observed type of a synthetic observer: the index form, written as Java writes
 * a type, with binary class names (VAU-OBS-005).
 *
 * <pre>
 * com.example.Event                          a class (or a class name such as [Ljava.lang.String;)
 * java.util.List&lt;java.lang.String&gt;           a parameterized type, arguments separated by ", "
 * java.util.List&lt;? extends java.lang.Number&gt; wildcards: ?, ? extends U, ? super L
 * java.util.List&lt;java.lang.String&gt;[]         an array, one [] per dimension; int[] for primitives
 * (N extends java.lang.Number &amp; A)[]         a type variable with its bounds, in parentheses
 * </pre>
 *
 * <p>A class and a parameterized type of classes are written as earlier versions wrote them, so their metadata reads
 * the same. A whole value that is a primitive name reads as a class of that name, as {@code addObserver(int.class)}
 * records it.</p>
 */
final class ObservedTypeNotation {

    private static final String EXTENDS = " extends ";
    private static final String SUPER = " super ";
    private static final String BOUND_SEPARATOR = " & ";
    private static final String DIMENSION = "[]";
    private static final String OBJECT_CLASS_NAME = "java.lang.Object";
    private static final TypeInfo OBJECT = new TypeInfo.ClassType(DotName.of(OBJECT_CLASS_NAME));
    /** The characters that end a class or type variable name. */
    private static final String NAME_END = "<>,[]()& ";
    private static final Map<String, TypeInfo.PrimitiveType.Kind> PRIMITIVES = Map.of(
            "boolean", TypeInfo.PrimitiveType.Kind.BOOLEAN, "byte", TypeInfo.PrimitiveType.Kind.BYTE,
            "char", TypeInfo.PrimitiveType.Kind.CHAR, "short", TypeInfo.PrimitiveType.Kind.SHORT,
            "int", TypeInfo.PrimitiveType.Kind.INT, "long", TypeInfo.PrimitiveType.Kind.LONG,
            "float", TypeInfo.PrimitiveType.Kind.FLOAT, "double", TypeInfo.PrimitiveType.Kind.DOUBLE);

    private ObservedTypeNotation() {
    }

    /** @throws IllegalArgumentException for {@code void}, which no event has */
    static String write(TypeInfo type) {
        var out = new StringBuilder();
        append(out, type);
        return out.toString();
    }

    /** @throws IllegalArgumentException when {@code notation} is not an observed type in this notation */
    static TypeInfo read(String notation) {
        var reader = new Reader(notation);
        var type = reader.type(true);
        reader.expectEnd();
        return type;
    }

    private static void append(StringBuilder out, TypeInfo type) {
        switch (type) {
            case TypeInfo.ClassType(DotName(var name)) -> out.append(name);
            case TypeInfo.PrimitiveType(var kind) -> out.append(kind.name().toLowerCase(Locale.ROOT));
            case TypeInfo.ParameterizedType(DotName(var rawName), var arguments) -> {
                out.append(rawName).append('<');
                appendAll(out, arguments, ", ");
                out.append('>');
            }
            case TypeInfo.ArrayType(var component, var dimensions) -> {
                append(out, component);
                out.append(DIMENSION.repeat(dimensions));
            }
            case TypeInfo.WildcardType(var upperBound, var lowerBound) -> appendWildcard(out, upperBound, lowerBound);
            case TypeInfo.TypeVariable(var name, var bounds) -> {
                out.append('(').append(name).append(EXTENDS);
                appendAll(out, bounds.isEmpty() ? List.of(OBJECT) : bounds, BOUND_SEPARATOR);
                out.append(')');
            }
            case TypeInfo.VoidType _ -> throw new IllegalArgumentException("void is the type of no event");
        }
    }

    private static void appendAll(StringBuilder out, List<TypeInfo> types, String separator) {
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) out.append(separator);
            append(out, types.get(i));
        }
    }

    private static void appendWildcard(StringBuilder out, TypeInfo upperBound, TypeInfo lowerBound) {
        out.append('?');
        if (lowerBound != null) {
            append(out.append(SUPER), lowerBound);
        } else if (upperBound != null && !OBJECT.equals(upperBound)) {
            append(out.append(EXTENDS), upperBound);
        }
    }

    /** Reads one notation from left to right. */
    private static final class Reader {
        private final String notation;
        private int position;

        Reader(String notation) {
            this.notation = notation;
        }

        TypeInfo type(boolean whole) {
            var base = switch (peek()) {
                case '[' -> new TypeInfo.ClassType(DotName.of(classNameDescriptor()));
                case '?' -> wildcard();
                case '(' -> typeVariable();
                default -> named(whole);
            };
            int dimensions = 0;
            while (skip(DIMENSION)) dimensions++;
            return dimensions == 0 ? base : new TypeInfo.ArrayType(base, dimensions);
        }

        /** The name {@code Class.getName()} gives an array class, {@code [Ljava.lang.String;} or {@code [I}. */
        private String classNameDescriptor() {
            int start = position;
            while (peek() == '[') position++;
            if (peek() == 'L') {
                int end = notation.indexOf(';', position);
                if (end < 0) throw malformed();
                position = end;
            }
            position++;
            return notation.substring(start, position);
        }

        private TypeInfo wildcard() {
            position++;
            if (skip(EXTENDS)) return new TypeInfo.WildcardType(type(false), null);
            if (skip(SUPER)) return new TypeInfo.WildcardType(null, type(false));
            return new TypeInfo.WildcardType(OBJECT, null);
        }

        private TypeInfo typeVariable() {
            position++;
            var name = name();
            expect(EXTENDS);
            var bounds = new ArrayList<TypeInfo>();
            do {
                bounds.add(type(false));
            } while (skip(BOUND_SEPARATOR));
            expect(")");
            return new TypeInfo.TypeVariable(name, bounds);
        }

        private TypeInfo named(boolean whole) {
            var name = name();
            if (skip("<")) {
                var arguments = new ArrayList<TypeInfo>();
                do {
                    skipSpaces();
                    arguments.add(type(false));
                } while (skip(","));
                expect(">");
                return new TypeInfo.ParameterizedType(DotName.of(name), arguments);
            }
            var primitive = PRIMITIVES.get(name);
            if (primitive != null && !(whole && atEnd())) {
                return new TypeInfo.PrimitiveType(primitive);
            }
            return new TypeInfo.ClassType(DotName.of(name));
        }

        private String name() {
            int start = position;
            while (!atEnd() && NAME_END.indexOf(notation.charAt(position)) < 0) position++;
            if (position == start) throw malformed();
            return notation.substring(start, position);
        }

        /** The spaces after the comma between two type arguments. */
        private void skipSpaces() {
            while (!atEnd() && notation.charAt(position) == ' ') position++;
        }

        private boolean skip(String expected) {
            if (!notation.startsWith(expected, position)) return false;
            position += expected.length();
            return true;
        }

        private void expect(String expected) {
            if (!skip(expected)) throw malformed();
        }

        void expectEnd() {
            if (!atEnd()) throw malformed();
        }

        private boolean atEnd() {
            return position >= notation.length();
        }

        private char peek() {
            if (atEnd()) throw malformed();
            return notation.charAt(position);
        }

        private IllegalArgumentException malformed() {
            return new IllegalArgumentException("not an observed type in the synthetic metadata notation, at character "
                    + position);
        }
    }
}
