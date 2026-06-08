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

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.List;

public final class BuiltAnnotationMember implements AnnotationMember {

    private final Kind kind;
    private final Object value;

    private BuiltAnnotationMember(Kind kind, Object value) {
        this.kind = kind;
        this.value = value;
    }

    public static BuiltAnnotationMember ofBoolean(boolean v) { return new BuiltAnnotationMember(Kind.BOOLEAN, v); }
    public static BuiltAnnotationMember ofByte(byte v) { return new BuiltAnnotationMember(Kind.BYTE, v); }
    public static BuiltAnnotationMember ofShort(short v) { return new BuiltAnnotationMember(Kind.SHORT, v); }
    public static BuiltAnnotationMember ofInt(int v) { return new BuiltAnnotationMember(Kind.INT, v); }
    public static BuiltAnnotationMember ofLong(long v) { return new BuiltAnnotationMember(Kind.LONG, v); }
    public static BuiltAnnotationMember ofFloat(float v) { return new BuiltAnnotationMember(Kind.FLOAT, v); }
    public static BuiltAnnotationMember ofDouble(double v) { return new BuiltAnnotationMember(Kind.DOUBLE, v); }
    public static BuiltAnnotationMember ofChar(char v) { return new BuiltAnnotationMember(Kind.CHAR, v); }
    public static BuiltAnnotationMember ofString(String v) { return new BuiltAnnotationMember(Kind.STRING, v); }
    public static BuiltAnnotationMember ofEnum(String enumTypeName, String constantName) {
        return new BuiltAnnotationMember(Kind.ENUM, new EnumValue(enumTypeName, constantName));
    }
    public static BuiltAnnotationMember ofClass(Type type) { return new BuiltAnnotationMember(Kind.CLASS, type); }
    public static BuiltAnnotationMember ofNestedAnnotation(AnnotationInfo info) { return new BuiltAnnotationMember(Kind.NESTED_ANNOTATION, info); }
    public static BuiltAnnotationMember ofArray(List<AnnotationMember> members) { return new BuiltAnnotationMember(Kind.ARRAY, List.copyOf(members)); }

    record EnumValue(String enumTypeName, String constantName) {}

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public boolean asBoolean() {
        checkKind(Kind.BOOLEAN);
        return (boolean) value;
    }

    @Override
    public byte asByte() {
        checkKind(Kind.BYTE);
        return (byte) value;
    }

    @Override
    public short asShort() {
        checkKind(Kind.SHORT);
        return (short) value;
    }

    @Override
    public int asInt() {
        checkKind(Kind.INT);
        return (int) value;
    }

    @Override
    public long asLong() {
        checkKind(Kind.LONG);
        return (long) value;
    }

    @Override
    public float asFloat() {
        checkKind(Kind.FLOAT);
        return (float) value;
    }

    @Override
    public double asDouble() {
        checkKind(Kind.DOUBLE);
        return (double) value;
    }

    @Override
    public char asChar() {
        checkKind(Kind.CHAR);
        return (char) value;
    }

    @Override
    public String asString() {
        checkKind(Kind.STRING);
        return (String) value;
    }

    @Override
    public <E extends Enum<E>> E asEnum(Class<E> enumType) {
        checkKind(Kind.ENUM);
        var ev = (EnumValue) value;
        return Enum.valueOf(enumType, ev.constantName());
    }

    @Override
    public ClassInfo asEnumClass() {
        throw new UnsupportedOperationException("asEnumClass not supported on built annotation members");
    }

    @Override
    public String asEnumConstant() {
        checkKind(Kind.ENUM);
        return ((EnumValue) value).constantName();
    }

    @Override
    public Type asType() {
        checkKind(Kind.CLASS);
        return (Type) value;
    }

    @Override
    public AnnotationInfo asNestedAnnotation() {
        checkKind(Kind.NESTED_ANNOTATION);
        return (AnnotationInfo) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<AnnotationMember> asArray() {
        checkKind(Kind.ARRAY);
        return (List<AnnotationMember>) value;
    }

    private void checkKind(Kind expected) {
        if (kind != expected) {
            throw new IllegalStateException("Not a " + expected + " value, but " + kind);
        }
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
