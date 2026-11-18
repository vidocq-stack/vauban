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

import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.core.langmodel.types.VaubanParameterizedType;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import jakarta.enterprise.lang.model.types.WildcardType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts a CDI Lite lang-model {@link Type} (the type representation handed to
 * BCE methods) back into Vauban's index {@link TypeInfo}. This is the inverse of
 * {@link io.vidocq.vauban.core.langmodel.types.TypeMapper}, used by
 * {@link VaubanSyntheticBeanBuilder#type(Type)} so synthetic beans declared via
 * the lang-model API expose their full bean-type set (including parameterized
 * types like {@code Optional<String>} or {@code List<T>}) to the resolver.
 *
 * <p>Cf. VAU-BCE-001 — the previous {@code type(Type)} overload was a no-op
 * which silently dropped parameterized types, so any synthetic bean built via
 * the lang-model {@code Type} API only exposed {@code Object} as a bean type.</p>
 */
final class LangModelTypeMapper {

    private static final TypeInfo OBJECT = new TypeInfo.ClassType(DotName.of("java.lang.Object"));

    private LangModelTypeMapper() {}

    static TypeInfo toIndexType(Type type) {
        return toIndexType(type, Set.of());
    }

    /** The index type of {@code type}, inside the bounds of the type variables {@code reading} names. */
    private static TypeInfo toIndexType(Type type, Set<String> reading) {
        return switch (type) {
            case PrimitiveType p -> new TypeInfo.PrimitiveType(switch (p.primitiveKind()) {
                case BOOLEAN -> TypeInfo.PrimitiveType.Kind.BOOLEAN;
                case BYTE -> TypeInfo.PrimitiveType.Kind.BYTE;
                case SHORT -> TypeInfo.PrimitiveType.Kind.SHORT;
                case INT -> TypeInfo.PrimitiveType.Kind.INT;
                case LONG -> TypeInfo.PrimitiveType.Kind.LONG;
                case FLOAT -> TypeInfo.PrimitiveType.Kind.FLOAT;
                case DOUBLE -> TypeInfo.PrimitiveType.Kind.DOUBLE;
                case CHAR -> TypeInfo.PrimitiveType.Kind.CHAR;
            });
            case ClassType ct -> classTypeToIndex(ct);
            case ParameterizedType pt -> {
                var rawName = classTypeName(pt.genericClass());
                List<TypeInfo> args = pt.typeArguments().stream()
                        .map(argument -> toIndexType(argument, reading))
                        .toList();
                // Outer<String>.Inner<Integer> keeps its owner, which the language model does not give
                // (BUG-20261118-49)
                yield new TypeInfo.ParameterizedType(rawName, args,
                        pt instanceof VaubanParameterizedType indexed ? indexed.indexOwner() : null);
            }
            case ArrayType at -> {
                var elem = toIndexType(at.componentType(), reading);
                if (elem instanceof TypeInfo.ArrayType(var innerComponent, var innerDimensions)) {
                    yield new TypeInfo.ArrayType(innerComponent, innerDimensions + 1);
                }
                yield new TypeInfo.ArrayType(elem, 1);
            }
            case WildcardType wt -> wildcard(wt, reading);
            case TypeVariable tv -> typeVariable(tv, reading);
            default -> new TypeInfo.ClassType(DotName.of(type.toString()));
        };
    }

    /**
     * {@code ? extends Object}, as the language model represents {@code ?} and as {@code Types.wildcardUnbounded()}
     * gives it, is the wildcard without bounds the index holds for {@code ?}, as the class files and reflection read
     * it: a type an extension takes from the language model maps back to the type the index holds
     * (BUG-20261117-34).
     */
    private static TypeInfo wildcard(WildcardType wildcard, Set<String> reading) {
        var upper = wildcard.upperBound() != null ? toIndexType(wildcard.upperBound(), reading) : null;
        var lower = wildcard.lowerBound() != null ? toIndexType(wildcard.lowerBound(), reading) : null;
        return new TypeInfo.WildcardType(OBJECT.equals(upper) && lower == null ? null : upper, lower);
    }

    /**
     * A type variable as the index records it: without the {@code java.lang.Object} bound the language model gives one
     * that declares none, and without bounds when met again inside its own, {@code T} in
     * {@code <T extends Comparable<T>>}, which the language model reads when asked (BUG-20261117-33).
     */
    private static TypeInfo typeVariable(TypeVariable variable, Set<String> reading) {
        if (reading.contains(variable.name())) {
            return new TypeInfo.TypeVariable(variable.name(), List.of());
        }
        var inner = new HashSet<>(reading);
        inner.add(variable.name());
        var bounds = variable.bounds().stream().map(bound -> toIndexType(bound, Set.copyOf(inner))).toList();
        return new TypeInfo.TypeVariable(variable.name(), bounds.equals(List.of(OBJECT)) ? List.of() : bounds);
    }

    /**
     * {@code ct} in the index; {@code Outer<String>.Plain} keeps its owner, which the language model does not give
     * (BUG-20261118-56).
     */
    private static TypeInfo classTypeToIndex(ClassType ct) {
        if (ct instanceof VaubanClassType indexed && indexed.indexOwner() != null) {
            return new TypeInfo.ParameterizedType(indexed.dotName(), List.of(), indexed.indexOwner());
        }
        return new TypeInfo.ClassType(classTypeName(ct));
    }

    private static DotName classTypeName(ClassType ct) {
        // Prefer the cheap dot name from VaubanClassType (no index lookup needed).
        if (ct instanceof VaubanClassType vct) return vct.dotName();
        return DotName.of(ct.declaration().name());
    }
}
