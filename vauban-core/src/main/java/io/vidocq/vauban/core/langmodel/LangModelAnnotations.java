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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.core.langmodel.types.VaubanArrayType;
import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.core.langmodel.types.VaubanPrimitiveType;
import io.vidocq.vauban.core.langmodel.types.VaubanVoidType;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;

import java.util.LinkedHashMap;

/**
 * Converts the annotations an extension writes — through {@code AnnotationBuilder}, or read back from
 * the lang model — into the index model, the one form Vauban compares.
 *
 * <p>An annotation an extension adds to a bean has to match an injection point that declares the same
 * annotation in source, so the conversion must record what the bytecode scan records: binary type
 * names, enum constants by their enum type, class literals as classes, nested annotations and arrays
 * element by element. An index-backed annotation is unwrapped, never rebuilt.
 */
public final class LangModelAnnotations {

    private LangModelAnnotations() {
    }

    /** The index form of a lang-model annotation. */
    public static AnnotationInfo toIndex(jakarta.enterprise.lang.model.AnnotationInfo annotation) {
        if (annotation instanceof VaubanAnnotationInfo indexed) {
            return indexed.indexAnnotation();
        }
        var members = new LinkedHashMap<String, AnnotationValue>();
        annotation.members().forEach((name, member) -> members.put(name, toIndex(member)));
        return new AnnotationInfo(DotName.of(annotation.name()), members);
    }

    /** The index form of one lang-model member value. */
    public static AnnotationValue toIndex(AnnotationMember member) {
        if (member instanceof VaubanAnnotationMember indexed) {
            return indexed.indexValue();
        }
        return switch (member.kind()) {
            case BOOLEAN -> new AnnotationValue.BooleanVal(member.asBoolean());
            case BYTE -> new AnnotationValue.ByteVal(member.asByte());
            case SHORT -> new AnnotationValue.ShortVal(member.asShort());
            case INT -> new AnnotationValue.IntVal(member.asInt());
            case LONG -> new AnnotationValue.LongVal(member.asLong());
            case FLOAT -> new AnnotationValue.FloatVal(member.asFloat());
            case DOUBLE -> new AnnotationValue.DoubleVal(member.asDouble());
            case CHAR -> new AnnotationValue.CharVal(member.asChar());
            case STRING -> new AnnotationValue.StringVal(member.asString());
            case ENUM -> new AnnotationValue.EnumVal(DotName.of(enumTypeName(member)), member.asEnumConstant());
            case CLASS -> new AnnotationValue.ClassVal(typeName(member.asType()));
            case NESTED_ANNOTATION -> new AnnotationValue.AnnotationVal(toIndex(member.asNestedAnnotation()));
            case ARRAY -> new AnnotationValue.ArrayVal(
                    member.asArray().stream().map(LangModelAnnotations::toIndex).toList());
        };
    }

    /**
     * The lang-model type of a class literal an extension passes as a {@code Class}. The types of the
     * lang model carry no index, so the resulting {@code ClassType} names its class without being able
     * to describe its declaration.
     */
    public static Type typeOf(Class<?> value) {
        if (value == void.class) return VaubanVoidType.INSTANCE;
        if (value.isPrimitive()) return new VaubanPrimitiveType(primitiveKind(value));
        if (value.isArray()) return new VaubanArrayType(typeOf(value.getComponentType()));
        return new VaubanClassType(DotName.of(value.getName()), null);
    }

    /** The lang-model type of a class named by an extension, as {@code Class#getName} spells it. */
    public static Type typeOf(String binaryName) {
        return new VaubanClassType(DotName.of(binaryName), null);
    }

    private static String enumTypeName(AnnotationMember member) {
        if (member instanceof BuiltAnnotationMember built) {
            return built.enumTypeName();
        }
        return member.asEnumClass().name();
    }

    /** The name a class literal has in a class file: the keyword of a primitive, the descriptor of an array. */
    private static DotName typeName(Type type) {
        if (type instanceof VaubanClassType named) return named.dotName();
        if (type.isVoid()) return DotName.of("void");
        if (type.isPrimitive()) return DotName.of(type.asPrimitive().name());
        if (type.isArray()) return DotName.of(descriptor(type));
        if (type.isClass()) return DotName.of(type.asClass().declaration().name());
        if (type.isParameterizedType()) return DotName.of(type.asParameterizedType().declaration().name());
        return DotName.of("java.lang.Object");
    }

    private static String descriptor(Type type) {
        if (type.isArray()) return "[" + descriptor(type.asArray().componentType());
        if (type.isPrimitive()) {
            return switch (type.asPrimitive().primitiveKind()) {
                case BOOLEAN -> "Z";
                case BYTE -> "B";
                case CHAR -> "C";
                case SHORT -> "S";
                case INT -> "I";
                case LONG -> "J";
                case FLOAT -> "F";
                case DOUBLE -> "D";
            };
        }
        return "L" + typeName(type).value() + ";";
    }

    private static PrimitiveType.PrimitiveKind primitiveKind(Class<?> value) {
        if (value == boolean.class) return PrimitiveType.PrimitiveKind.BOOLEAN;
        if (value == byte.class) return PrimitiveType.PrimitiveKind.BYTE;
        if (value == short.class) return PrimitiveType.PrimitiveKind.SHORT;
        if (value == int.class) return PrimitiveType.PrimitiveKind.INT;
        if (value == long.class) return PrimitiveType.PrimitiveKind.LONG;
        if (value == float.class) return PrimitiveType.PrimitiveKind.FLOAT;
        if (value == double.class) return PrimitiveType.PrimitiveKind.DOUBLE;
        if (value == char.class) return PrimitiveType.PrimitiveKind.CHAR;
        throw new IllegalArgumentException(value + " is not a primitive type");
    }
}
