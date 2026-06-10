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

import io.vidocq.vauban.core.langmodel.BuiltAnnotationInfo;
import io.vidocq.vauban.core.langmodel.BuiltAnnotationMember;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class VaubanAnnotationBuilder implements AnnotationBuilder {

    private final Class<? extends Annotation> annotationType;
    private final Map<String, AnnotationMember> members = new LinkedHashMap<>();

    VaubanAnnotationBuilder(Class<? extends Annotation> annotationType) {
        this.annotationType = annotationType;
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationMember value) {
        members.put(name, value);
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, boolean value) {
        members.put(name, BuiltAnnotationMember.ofBoolean(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, boolean[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (boolean v : values) list.add(BuiltAnnotationMember.ofBoolean(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, byte value) {
        members.put(name, BuiltAnnotationMember.ofByte(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, byte[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (byte v : values) list.add(BuiltAnnotationMember.ofByte(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, short value) {
        members.put(name, BuiltAnnotationMember.ofShort(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, short[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (short v : values) list.add(BuiltAnnotationMember.ofShort(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, int value) {
        members.put(name, BuiltAnnotationMember.ofInt(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, int[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (int v : values) list.add(BuiltAnnotationMember.ofInt(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, long value) {
        members.put(name, BuiltAnnotationMember.ofLong(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, long[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (long v : values) list.add(BuiltAnnotationMember.ofLong(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, float value) {
        members.put(name, BuiltAnnotationMember.ofFloat(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, float[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (float v : values) list.add(BuiltAnnotationMember.ofFloat(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, double value) {
        members.put(name, BuiltAnnotationMember.ofDouble(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, double[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (double v : values) list.add(BuiltAnnotationMember.ofDouble(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, char value) {
        members.put(name, BuiltAnnotationMember.ofChar(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, char[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (char v : values) list.add(BuiltAnnotationMember.ofChar(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, String value) {
        members.put(name, BuiltAnnotationMember.ofString(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, String[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (String v : values) list.add(BuiltAnnotationMember.ofString(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Enum<?> value) {
        members.put(name, BuiltAnnotationMember.ofEnum(value.getDeclaringClass().getName(), value.name()));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Enum<?>[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (Enum<?> v : values) list.add(BuiltAnnotationMember.ofEnum(v.getDeclaringClass().getName(), v.name()));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Class<? extends Enum<?>> enumType, String enumValue) {
        members.put(name, BuiltAnnotationMember.ofEnum(enumType.getName(), enumValue));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Class<? extends Enum<?>> enumType, String[] enumValues) {
        List<AnnotationMember> list = new ArrayList<>(enumValues.length);
        for (String v : enumValues) list.add(BuiltAnnotationMember.ofEnum(enumType.getName(), v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo enumType, String enumValue) {
        members.put(name, BuiltAnnotationMember.ofEnum(enumType.name(), enumValue));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo enumType, String[] enumValues) {
        List<AnnotationMember> list = new ArrayList<>(enumValues.length);
        for (String v : enumValues) list.add(BuiltAnnotationMember.ofEnum(enumType.name(), v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Class<?> value) {
        members.put(name, BuiltAnnotationMember.ofString(value.getName()));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Class<?>[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (Class<?> v : values) list.add(BuiltAnnotationMember.ofString(v.getName()));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo value) {
        members.put(name, BuiltAnnotationMember.ofString(value.name()));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, ClassInfo[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (ClassInfo v : values) list.add(BuiltAnnotationMember.ofString(v.name()));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Type value) {
        members.put(name, BuiltAnnotationMember.ofClass(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Type[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (Type v : values) list.add(BuiltAnnotationMember.ofClass(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationInfo value) {
        members.put(name, BuiltAnnotationMember.ofNestedAnnotation(value));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, AnnotationInfo[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (AnnotationInfo v : values) list.add(BuiltAnnotationMember.ofNestedAnnotation(v));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Annotation value) {
        members.put(name, BuiltAnnotationMember.ofNestedAnnotation(convertAnnotation(value)));
        return this;
    }

    @Override
    public AnnotationBuilder member(String name, Annotation[] values) {
        List<AnnotationMember> list = new ArrayList<>(values.length);
        for (Annotation v : values) list.add(BuiltAnnotationMember.ofNestedAnnotation(convertAnnotation(v)));
        members.put(name, BuiltAnnotationMember.ofArray(list));
        return this;
    }

    @Override
    public AnnotationInfo build() {
        Map<String, AnnotationMember> allMembers = new LinkedHashMap<>(members);

        for (Method method : annotationType.getDeclaredMethods()) {
            if (!allMembers.containsKey(method.getName())) {
                Object defaultValue = method.getDefaultValue();
                if (defaultValue == null) {
                    throw new IllegalStateException(
                            "No value defined for annotation member '" + method.getName()
                                    + "' of @" + annotationType.getName() + " and no default value exists");
                }
            }
        }

        return new BuiltAnnotationInfo(annotationType, allMembers);
    }

    @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
    private AnnotationInfo convertAnnotation(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        var builder = new VaubanAnnotationBuilder(type);
        for (Method method : type.getDeclaredMethods()) {
            try {
                Object val = method.invoke(annotation);
                String memberName = method.getName();
                addMemberValue(builder, memberName, val);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException("Failed to read annotation member: " + method.getName(), e);
            }
        }
        return new BuiltAnnotationInfo(type, builder.members);
    }

    @SuppressWarnings("unchecked")
    private static void addMemberValue(VaubanAnnotationBuilder builder, String name, Object val) {
        switch (val) {
            case Boolean v -> builder.member(name, v.booleanValue());
            case Byte v -> builder.member(name, v.byteValue());
            case Short v -> builder.member(name, v.shortValue());
            case Integer v -> builder.member(name, v.intValue());
            case Long v -> builder.member(name, v.longValue());
            case Float v -> builder.member(name, v.floatValue());
            case Double v -> builder.member(name, v.doubleValue());
            case Character v -> builder.member(name, v.charValue());
            case String v -> builder.member(name, v);
            case Annotation v -> builder.member(name, v);
            case Enum<?> v -> builder.member(name, v);
            case Class<?> v -> builder.member(name, v);
            case boolean[] v -> builder.member(name, v);
            case byte[] v -> builder.member(name, v);
            case short[] v -> builder.member(name, v);
            case int[] v -> builder.member(name, v);
            case long[] v -> builder.member(name, v);
            case float[] v -> builder.member(name, v);
            case double[] v -> builder.member(name, v);
            case char[] v -> builder.member(name, v);
            case String[] v -> builder.member(name, v);
            case Annotation[] v -> builder.member(name, v);
            // Enum<?>[] and Class<?>[] are not valid case labels (javac cannot parse a
            // wildcard followed by array dims there), so they fall back to instanceof
            default -> {
                if (val instanceof Enum<?>[] v) builder.member(name, v);
                else if (val instanceof Class<?>[] v) builder.member(name, v);
                else throw new IllegalArgumentException("Unsupported annotation member type: " + val.getClass());
            }
        }
    }
}
