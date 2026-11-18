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
     * interfaces above it may name (BUG-20261117-21); {@code null} otherwise. The owner tells where the type comes
     * from, not which type it is: two parameterized types of the same class and arguments are equal whatever their
     * owners, as the resolution of the injection points always compared them.
     *
     * @param rawType       the class
     * @param typeArguments its type arguments
     * @param owner         the parameterized type that encloses it, or {@code null}
     */
    record ParameterizedType(DotName rawType, List<TypeInfo> typeArguments, TypeInfo owner) implements TypeInfo {
        public ParameterizedType {
            typeArguments = List.copyOf(typeArguments);
        }

        /** A parameterized type that no parameterized type encloses. */
        public ParameterizedType(DotName rawType, List<TypeInfo> typeArguments) {
            this(rawType, typeArguments, null);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType that && rawType.equals(that.rawType)
                    && typeArguments.equals(that.typeArguments);
        }

        @Override
        public int hashCode() {
            return 31 * rawType.hashCode() + typeArguments.hashCode();
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
