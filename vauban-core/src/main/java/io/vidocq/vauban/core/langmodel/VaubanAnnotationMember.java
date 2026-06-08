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

import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.types.Type;

import java.util.List;

public final class VaubanAnnotationMember implements AnnotationMember {

    private final io.vidocq.vauban.indexer.model.AnnotationValue indexValue;
    private final IndexLookup lookup;

    public VaubanAnnotationMember(io.vidocq.vauban.indexer.model.AnnotationValue indexValue, IndexLookup lookup) {
        this.indexValue = indexValue;
        this.lookup = lookup;
    }

    @Override
    public Kind kind() {
        return switch (indexValue) {
            case io.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal _ -> Kind.BOOLEAN;
            case io.vidocq.vauban.indexer.model.AnnotationValue.ByteVal _ -> Kind.BYTE;
            case io.vidocq.vauban.indexer.model.AnnotationValue.ShortVal _ -> Kind.SHORT;
            case io.vidocq.vauban.indexer.model.AnnotationValue.IntVal _ -> Kind.INT;
            case io.vidocq.vauban.indexer.model.AnnotationValue.LongVal _ -> Kind.LONG;
            case io.vidocq.vauban.indexer.model.AnnotationValue.FloatVal _ -> Kind.FLOAT;
            case io.vidocq.vauban.indexer.model.AnnotationValue.DoubleVal _ -> Kind.DOUBLE;
            case io.vidocq.vauban.indexer.model.AnnotationValue.CharVal _ -> Kind.CHAR;
            case io.vidocq.vauban.indexer.model.AnnotationValue.StringVal _ -> Kind.STRING;
            case io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal _ -> Kind.ENUM;
            case io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal _ -> Kind.CLASS;
            case io.vidocq.vauban.indexer.model.AnnotationValue.AnnotationVal _ -> Kind.NESTED_ANNOTATION;
            case io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal _ -> Kind.ARRAY;
        };
    }

    @Override
    public boolean asBoolean() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a boolean value, but " + kind());
    }

    @Override
    public byte asByte() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.ByteVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a byte value, but " + kind());
    }

    @Override
    public short asShort() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.ShortVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a short value, but " + kind());
    }

    @Override
    public int asInt() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.IntVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not an int value, but " + kind());
    }

    @Override
    public long asLong() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.LongVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a long value, but " + kind());
    }

    @Override
    public float asFloat() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.FloatVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a float value, but " + kind());
    }

    @Override
    public double asDouble() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.DoubleVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a double value, but " + kind());
    }

    @Override
    public char asChar() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.CharVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a char value, but " + kind());
    }

    @Override
    public String asString() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.StringVal v) {
            return v.value();
        }
        throw new IllegalStateException("Not a string value, but " + kind());
    }

    @Override
    public <E extends Enum<E>> E asEnum(Class<E> enumType) {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal v) {
            return Enum.valueOf(enumType, v.constantName());
        }
        throw new IllegalStateException("Not an enum value, but " + kind());
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo asEnumClass() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal v) {
            return new VaubanClassInfo(lookup.requireClass(v.enumType()), lookup);
        }
        throw new IllegalStateException("Not an enum value, but " + kind());
    }

    @Override
    public String asEnumConstant() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal v) {
            return v.constantName();
        }
        throw new IllegalStateException("Not an enum value, but " + kind());
    }

    @Override
    public Type asType() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal v) {
            return TypeMapper.map(new TypeInfo.ClassType(v.className()), lookup);
        }
        throw new IllegalStateException("Not a class value, but " + kind());
    }

    @Override
    public jakarta.enterprise.lang.model.AnnotationInfo asNestedAnnotation() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.AnnotationVal v) {
            return new VaubanAnnotationInfo(v.annotation(), lookup);
        }
        throw new IllegalStateException("Not a nested annotation value, but " + kind());
    }

    @Override
    public List<AnnotationMember> asArray() {
        if (indexValue instanceof io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal v) {
            return v.values().stream()
                    .map(item -> (AnnotationMember) new VaubanAnnotationMember(item, lookup))
                    .toList();
        }
        throw new IllegalStateException("Not an array value, but " + kind());
    }

    @Override
    public String toString() {
        return indexValue.toString();
    }
}
