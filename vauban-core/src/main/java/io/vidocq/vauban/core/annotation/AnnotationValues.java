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
package io.vidocq.vauban.core.annotation;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Converts the member values of a live annotation into the index model, as the bytecode scan records
 * them: binary type names, enum constants by their enum type, arrays and nested annotations element by
 * element, every value kind kept.
 *
 * <p>Reading a live annotation invokes its members. It serves the annotations the index does not
 * describe — an {@code @Inherited} annotation found on a superclass, the default of an annotation type
 * read by reflection — never the matching itself, which compares index data.
 */
public final class AnnotationValues {

    private AnnotationValues() {
    }

    /**
     * Every member of {@code annotation}, defaults included. A member that cannot be read is left out,
     * as the scan of inherited annotations always did: its annotation type may sit in a package this
     * module cannot access, or its value may name a class missing at run time — and the annotation need
     * not even be a qualifier.
     */
    public static AnnotationInfo toAnnotationInfo(Annotation annotation) {
        var type = annotation.annotationType();
        AnnotationReflection.check("reading the members of", type.getName());
        var members = new LinkedHashMap<String, AnnotationValue>();
        for (var member : members(type)) {
            read(member, annotation).ifPresent(value -> members.put(member.getName(), of(value)));
        }
        return new AnnotationInfo(DotName.of(type.getName()), members);
    }

    /**
     * The members of an annotation, whoever built it: the index data a container-built instance
     * carries, or a read of a live one.
     */
    public static AnnotationInfo infoOf(Annotation annotation) {
        return AnnotationInstances.infoOf(annotation).orElseGet(() -> toAnnotationInfo(annotation));
    }

    /** One member value, as the bytecode scan records it. */
    public static AnnotationValue of(Object value) {
        return switch (value) {
            case String s -> new AnnotationValue.StringVal(s);
            case Boolean b -> new AnnotationValue.BooleanVal(b);
            case Byte b -> new AnnotationValue.ByteVal(b);
            case Character c -> new AnnotationValue.CharVal(c);
            case Short s -> new AnnotationValue.ShortVal(s);
            case Integer i -> new AnnotationValue.IntVal(i);
            case Long l -> new AnnotationValue.LongVal(l);
            case Float f -> new AnnotationValue.FloatVal(f);
            case Double d -> new AnnotationValue.DoubleVal(d);
            // Class#getName is the binary name, "int" for a primitive, "[Ljava.lang.String;" for an array
            case Class<?> c -> new AnnotationValue.ClassVal(DotName.of(c.getName()));
            // getDeclaringClass: a constant with a body is an instance of an anonymous subclass
            case Enum<?> e -> new AnnotationValue.EnumVal(DotName.of(e.getDeclaringClass().getName()), e.name());
            case Annotation a -> new AnnotationValue.AnnotationVal(toAnnotationInfo(a));
            case boolean[] a -> arrayOf(a.length, i -> new AnnotationValue.BooleanVal(a[i]));
            case byte[] a -> arrayOf(a.length, i -> new AnnotationValue.ByteVal(a[i]));
            case char[] a -> arrayOf(a.length, i -> new AnnotationValue.CharVal(a[i]));
            case short[] a -> arrayOf(a.length, i -> new AnnotationValue.ShortVal(a[i]));
            case int[] a -> arrayOf(a.length, i -> new AnnotationValue.IntVal(a[i]));
            case long[] a -> arrayOf(a.length, i -> new AnnotationValue.LongVal(a[i]));
            case float[] a -> arrayOf(a.length, i -> new AnnotationValue.FloatVal(a[i]));
            case double[] a -> arrayOf(a.length, i -> new AnnotationValue.DoubleVal(a[i]));
            case Object[] a -> arrayOf(a.length, i -> of(a[i]));
            default -> throw new IllegalArgumentException("not an annotation member value: " + value.getClass().getName());
        };
    }

    /** The members an annotation type declares. */
    static List<Method> members(Class<?> annotationType) {
        return Arrays.stream(annotationType.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                        && !method.isSynthetic())
                .toList();
    }

    private static AnnotationValue.ArrayVal arrayOf(int length, IntFunction<AnnotationValue> element) {
        return new AnnotationValue.ArrayVal(IntStream.range(0, length).mapToObj(element).toList());
    }

    private static Optional<Object> read(Method member, Annotation annotation) {
        try {
            member.trySetAccessible();
            return Optional.of(member.invoke(annotation));
        } catch (IllegalAccessException | InvocationTargetException e) {
            return Optional.empty();
        }
    }
}
