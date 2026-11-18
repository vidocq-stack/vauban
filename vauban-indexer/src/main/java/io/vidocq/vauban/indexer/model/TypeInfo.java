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
package io.vidocq.vauban.indexer.model;

import java.util.List;

public sealed interface TypeInfo {

    record VoidType() implements TypeInfo {}

    record PrimitiveType(Kind kind) implements TypeInfo {

        public enum Kind {
            BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE
        }

        public static PrimitiveType fromDescriptor(char desc) {
            return new PrimitiveType(switch (desc) {
                case 'Z' -> Kind.BOOLEAN;
                case 'B' -> Kind.BYTE;
                case 'C' -> Kind.CHAR;
                case 'S' -> Kind.SHORT;
                case 'I' -> Kind.INT;
                case 'J' -> Kind.LONG;
                case 'F' -> Kind.FLOAT;
                case 'D' -> Kind.DOUBLE;
                default -> throw new IllegalArgumentException("Unknown primitive descriptor: " + desc);
            });
        }
    }

    record ClassType(DotName name) implements TypeInfo {}

    record ArrayType(TypeInfo componentType, int dimensions) implements TypeInfo {}

    /**
     * A parameterized type: its class, its type arguments, and the type that encloses it when that type is
     * parameterized, {@code Outer<String>} of {@code Outer<String>.Inner<Integer>}, whose arguments the classes and
     * interfaces above it may name (BUG-20261117-21); {@code null} otherwise. The owner is part of the type, as
     * {@code java.lang.reflect.ParameterizedType.equals} counts it: {@code Outer<String>.Inner<Integer>} and
     * {@code Outer<Long>.Inner<Integer>} are two types. Every reader of the index gives the owner of a member type, so
     * one type is one value; the language model has no owner, and the resolution of an injection point ignores the
     * owner where one side has none ({@code AssignabilityRules}).
     *
     * @param rawType       the class
     * @param typeArguments its type arguments
     * @param owner         the parameterized type that encloses it, or {@code null}
     */
    record ParameterizedType(DotName rawType, List<TypeInfo> typeArguments, TypeInfo owner) implements TypeInfo {
        /**
         * @throws IllegalArgumentException for a type without arguments and without owner, which is a class type:
         *         only a member type of a parameterized type, {@code Outer<String>.Plain}, has none (BUG-20261118-47)
         */
        public ParameterizedType {
            typeArguments = List.copyOf(typeArguments);
            if (typeArguments.isEmpty() && owner == null) {
                throw new IllegalArgumentException(rawType + " has no type argument and no owner: it is a class type");
            }
        }

        /** A parameterized type that no parameterized type encloses. */
        public ParameterizedType(DotName rawType, List<TypeInfo> typeArguments) {
            this(rawType, typeArguments, null);
        }

        @Override
        public String toString() {
            return "ParameterizedType[rawType=" + rawType + ", typeArguments=" + typeArguments
                    + (owner == null ? "" : ", owner=" + owner) + "]";
        }
    }

    record TypeVariable(String name, List<TypeInfo> bounds) implements TypeInfo {
        public TypeVariable {
            bounds = List.copyOf(bounds);
        }
    }

    record WildcardType(TypeInfo upperBound, TypeInfo lowerBound) implements TypeInfo {}
}
