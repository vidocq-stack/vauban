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

import io.vidocq.vauban.core.annotation.AnnotationInstances;
import io.vidocq.vauban.core.annotation.AnnotationValues;
import io.vidocq.vauban.core.langmodel.LangModelAnnotations;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * The text form of a synthetic bean or observer parameter, written at build time to
 * {@code META-INF/vauban-synthetic-metadata.properties} and read back at boot.
 *
 * <p>Every value CDI 4.1 Lite lets {@code withParam} take is written, and read back as the type the
 * creator looks it up with: a {@code ClassInfo} as a {@code Class}, an {@code AnnotationInfo} as an
 * {@code Annotation}, an {@code InvokerInfo} as an invoker. A value of any other type is refused, never
 * dropped (BUG-20261008-02).
 *
 * <h2>Format</h2>
 * A one-character tag, a colon, then the value:
 * <ul>
 *   <li>{@code S:} String, {@code B:} boolean, {@code I:} int, {@code L:} long, {@code D:} double,
 *       {@code C:} class (binary name), {@code E:type:constant} enum constant;</li>
 *   <li>{@code A:} annotation, {@code V:} invoker, {@code [:} array — each a sequence of
 *       <em>frames</em>.</li>
 * </ul>
 * A frame is a length, a colon and that many characters, so a value may hold any character. An array
 * is the frame of its component code ({@code B I L D S C A V}, {@code E} or {@code E:type}) then one
 * frame per element. An annotation is the frame of its type, then a name frame and a value frame per
 * member; member values carry their own lower-case tags.
 */
public final class SyntheticParamCodec {

    private SyntheticParamCodec() {
    }

    // ---- Encode ----

    /**
     * The text form of {@code value}, or {@code null} for {@code null}.
     *
     * @throws IllegalArgumentException when {@code value} is of no type {@code withParam} accepts
     */
    public static String encode(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> "S:" + s;
            case Boolean b -> "B:" + b;
            case Integer i -> "I:" + i;
            case Long l -> "L:" + l;
            case Double d -> "D:" + d;
            case Class<?> c -> "C:" + c.getName();
            case ClassInfo c -> "C:" + c.name();
            // getDeclaringClass: a constant with a body is an instance of an anonymous subclass
            case Enum<?> e -> "E:" + e.getDeclaringClass().getName() + ":" + e.name();
            case Annotation a -> "A:" + annotation(AnnotationValues.infoOf(a));
            case jakarta.enterprise.lang.model.AnnotationInfo a -> "A:" + annotation(LangModelAnnotations.toIndex(a));
            case VaubanInvoker v -> "V:" + invoker(v);
            default -> {
                if (value.getClass().isArray()) {
                    yield "[:" + array(value);
                }
                throw new IllegalArgumentException("a parameter of type " + value.getClass().getName()
                        + " cannot be recorded at build time");
            }
        };
    }

    private static String array(Object array) {
        var component = array.getClass().getComponentType();
        var out = new StringBuilder(frame(componentCode(component)));
        for (int i = 0; i < Array.getLength(array); i++) {
            out.append(frame(encode(Array.get(array, i))));
        }
        return out.toString();
    }

    private static String componentCode(Class<?> component) {
        if (component == boolean.class) return "B";
        if (component == int.class) return "I";
        if (component == long.class) return "L";
        if (component == double.class) return "D";
        if (component == String.class) return "S";
        if (component == Class.class || ClassInfo.class.isAssignableFrom(component)) return "C";
        if (component == Enum.class) return "E";
        if (component.isEnum()) return "E:" + component.getName();
        if (component == Annotation.class || component.isAnnotation()
                || jakarta.enterprise.lang.model.AnnotationInfo.class.isAssignableFrom(component)) return "A";
        if (InvokerInfo.class.isAssignableFrom(component)) return "V";
        throw new IllegalArgumentException("a parameter of type " + component.getName()
                + "[] cannot be recorded at build time");
    }

    private static String invoker(VaubanInvoker invoker) {
        var method = invoker.method();
        var out = new StringBuilder()
                .append(frame(invoker.beanClass().getName()))
                .append(frame(method.getDeclaringClass().getName()))
                .append(frame(method.getName()))
                .append(frame(String.valueOf(invoker.instanceLookup())));
        var lookups = new StringBuilder();
        for (var position : new java.util.TreeSet<>(invoker.argumentLookups())) {
            lookups.append(frame(String.valueOf(position)));
        }
        out.append(frame(lookups.toString()));
        for (var parameter : method.getParameterTypes()) {
            out.append(frame(parameter.getName()));
        }
        return out.toString();
    }

    private static String annotation(AnnotationInfo info) {
        var out = new StringBuilder(frame(info.name().value()));
        for (var member : new TreeMap<>(info.members()).entrySet()) {
            out.append(frame(member.getKey())).append(frame(member(member.getValue())));
        }
        return out.toString();
    }

    private static String member(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.StringVal v -> "s:" + v.value();
            case AnnotationValue.BooleanVal v -> "z:" + v.value();
            case AnnotationValue.ByteVal v -> "b:" + v.value();
            case AnnotationValue.CharVal v -> "c:" + (int) v.value();
            case AnnotationValue.ShortVal v -> "h:" + v.value();
            case AnnotationValue.IntVal v -> "i:" + v.value();
            case AnnotationValue.LongVal v -> "j:" + v.value();
            case AnnotationValue.FloatVal v -> "f:" + v.value();
            case AnnotationValue.DoubleVal v -> "d:" + v.value();
            case AnnotationValue.ClassVal v -> "k:" + v.className().value();
            case AnnotationValue.EnumVal v -> "e:" + frame(v.enumType().value()) + frame(v.constantName());
            case AnnotationValue.AnnotationVal v -> "@:" + annotation(v.annotation());
            case AnnotationValue.ArrayVal v -> {
                var out = new StringBuilder("[:");
                v.values().forEach(element -> out.append(frame(member(element))));
                yield out.toString();
            }
        };
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
    }

    // ---- Decode ----

    /**
     * The value {@code encoded} stands for, its classes loaded through {@code loader}.
     *
     * @throws IllegalArgumentException when {@code encoded} names a class {@code loader} cannot load,
     *                                  or is not a text {@link #encode} writes
     */
    public static Object decode(String encoded, ClassLoader loader) {
        if (encoded == null || encoded.length() < 2 || encoded.charAt(1) != ':') {
            throw new IllegalArgumentException("not a synthetic parameter: " + encoded);
        }
        var value = encoded.substring(2);
        return switch (encoded.charAt(0)) {
            case 'S' -> value;
            case 'B' -> Boolean.parseBoolean(value);
            case 'I' -> Integer.parseInt(value);
            case 'L' -> Long.parseLong(value);
            case 'D' -> Double.parseDouble(value);
            case 'C' -> load(value, loader);
            case 'E' -> {
                int colon = value.lastIndexOf(':');
                yield constant(load(value.substring(0, colon), loader), value.substring(colon + 1));
            }
            case 'A' -> {
                var info = readAnnotation(new Frames(value));
                var type = AnnotationInstances.typeNamed(info.name().value(), loader)
                        .orElseThrow(() -> missing(info.name().value()));
                yield AnnotationInstances.create(type, info, loader);
            }
            case 'V' -> readInvoker(new Frames(value), loader);
            case '[' -> readArray(new Frames(value), loader);
            default -> throw new IllegalArgumentException("not a synthetic parameter: " + encoded);
        };
    }

    private static Object readArray(Frames frames, ClassLoader loader) {
        var code = frames.next();
        var elements = new ArrayList<Object>();
        while (frames.hasNext()) {
            elements.add(decode(frames.next(), loader));
        }
        Class<?> component = switch (code) {
            case "B" -> boolean.class;
            case "I" -> int.class;
            case "L" -> long.class;
            case "D" -> double.class;
            case "S" -> String.class;
            case "C" -> Class.class;
            case "E" -> Enum.class;
            case "A" -> Annotation.class;
            case "V" -> InvokerInfo.class;
            default -> {
                if (code.startsWith("E:")) {
                    yield load(code.substring(2), loader);
                }
                throw new IllegalArgumentException("not a synthetic parameter array: " + code);
            }
        };
        var array = Array.newInstance(component, elements.size());
        for (int i = 0; i < elements.size(); i++) {
            Array.set(array, i, elements.get(i));
        }
        return array;
    }

    private static VaubanInvoker readInvoker(Frames frames, ClassLoader loader) {
        var beanClass = load(frames.next(), loader);
        var declaringClass = load(frames.next(), loader);
        var name = frames.next();
        var instanceLookup = Boolean.parseBoolean(frames.next());
        var argumentLookups = new HashSet<Integer>();
        var lookups = new Frames(frames.next());
        while (lookups.hasNext()) {
            argumentLookups.add(Integer.parseInt(lookups.next()));
        }
        var parameters = new ArrayList<Class<?>>();
        while (frames.hasNext()) {
            parameters.add(load(frames.next(), loader));
        }
        try {
            var method = declaringClass.getDeclaredMethod(name, parameters.toArray(Class<?>[]::new));
            return new VaubanInvoker(method, beanClass, instanceLookup, argumentLookups);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("the invoked method " + declaringClass.getName() + "." + name
                    + parameters + " no longer exists", e);
        }
    }

    private static AnnotationInfo readAnnotation(Frames frames) {
        var name = frames.next();
        var members = new java.util.LinkedHashMap<String, AnnotationValue>();
        while (frames.hasNext()) {
            members.put(frames.next(), readMember(frames.next()));
        }
        return new AnnotationInfo(DotName.of(name), members);
    }

    private static AnnotationValue readMember(String encoded) {
        var value = encoded.substring(2);
        return switch (encoded.charAt(0)) {
            case 's' -> new AnnotationValue.StringVal(value);
            case 'z' -> new AnnotationValue.BooleanVal(Boolean.parseBoolean(value));
            case 'b' -> new AnnotationValue.ByteVal(Byte.parseByte(value));
            case 'c' -> new AnnotationValue.CharVal((char) Integer.parseInt(value));
            case 'h' -> new AnnotationValue.ShortVal(Short.parseShort(value));
            case 'i' -> new AnnotationValue.IntVal(Integer.parseInt(value));
            case 'j' -> new AnnotationValue.LongVal(Long.parseLong(value));
            case 'f' -> new AnnotationValue.FloatVal(Float.parseFloat(value));
            case 'd' -> new AnnotationValue.DoubleVal(Double.parseDouble(value));
            case 'k' -> new AnnotationValue.ClassVal(DotName.of(value));
            case 'e' -> {
                var frames = new Frames(value);
                yield new AnnotationValue.EnumVal(DotName.of(frames.next()), frames.next());
            }
            case '@' -> new AnnotationValue.AnnotationVal(readAnnotation(new Frames(value)));
            case '[' -> {
                var frames = new Frames(value);
                var elements = new ArrayList<AnnotationValue>();
                while (frames.hasNext()) {
                    elements.add(readMember(frames.next()));
                }
                yield new AnnotationValue.ArrayVal(elements);
            }
            default -> throw new IllegalArgumentException("not an annotation member: " + encoded);
        };
    }

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "byte", byte.class, "char", char.class, "short", short.class,
            "int", int.class, "long", long.class, "float", float.class, "double", double.class);

    private static Class<?> load(String name, ClassLoader loader) {
        var primitive = PRIMITIVES.get(name);
        if (primitive != null) {
            return primitive;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw missing(name);
        }
    }

    private static IllegalArgumentException missing(String name) {
        return new IllegalArgumentException("class " + name + " cannot be loaded");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> constant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    /** Reads the frames of a value one after the other. */
    private static final class Frames {
        private final String text;
        private int position;

        Frames(String text) {
            this.text = text;
        }

        boolean hasNext() {
            return position < text.length();
        }

        String next() {
            int colon = text.indexOf(':', position);
            if (colon < 0) {
                throw new IllegalArgumentException("truncated synthetic parameter: " + text);
            }
            int start = colon + 1;
            int end = start + Integer.parseInt(text.substring(position, colon));
            position = end;
            return text.substring(start, end);
        }
    }
}
