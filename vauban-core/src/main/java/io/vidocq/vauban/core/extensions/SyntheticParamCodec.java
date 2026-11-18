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
import java.util.Objects;
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
            case VaubanInvoker v -> invoker(v, v.target(), v.instanceLookup(), v.argumentLookups());
            case InvokerTarget.Described d -> invoker(d, d.target(), d.instanceLookup(), d.argumentLookups());
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

    /**
     * An invoker: by the class the build generated for it, {@code G:}, when the metadata being written has one
     * (BUG-20261118-37), with its static flag and what each looked-up parameter is; by names only, {@code V:}, when
     * not. Both end with the names, so the start can still call the method itself. An invoker with no generated class
     * whose looked-up parameter a config of the bean class qualified is a {@code G:} with an empty class frame: its
     * annotations are recorded, reflection would read those the method declares (BUG-20261118-55, review round 3);
     * its type frame is empty when the notation cannot write it, the start reads it from the method.
     */
    private static String invoker(InvokerInfo info, InvokerTarget target, boolean instanceLookup,
                                  java.util.Set<Integer> lookups) {
        var names = invoker(target, instanceLookup, lookups);
        var generated = InvokerClasses.generatedFor(info);
        var shape = InvokerClasses.shapeOf(info);
        if (generated == null && (shape == null
                || lookups.stream().noneMatch(position -> shape.parameters().get(position).configured()))) {
            return "V:" + names;
        }
        var points = new StringBuilder();
        for (var position : new java.util.TreeSet<>(lookups)) {
            var parameter = shape.parameters().get(position);
            points.append(frame(String.valueOf(position)))
                    .append(frame(Objects.requireNonNullElse(parameter.type(), "")))
                    .append(frame(encodeAnnotations(parameter.annotations())));
        }
        return "G:" + frame(Objects.requireNonNullElse(generated, "")) + frame(String.valueOf(shape.isStatic()))
                + frame(points.toString()) + names;
    }

    /**
     * An invoker by names: what the start needs to find its method, which the build can describe of a class it
     * compiles as of a class it loads (BUG-20261117-48).
     */
    private static String invoker(InvokerTarget target, boolean instanceLookup, java.util.Set<Integer> lookups) {
        var out = new StringBuilder()
                .append(frame(target.beanClass()))
                .append(frame(target.declaringClass()))
                .append(frame(target.name()))
                .append(frame(String.valueOf(instanceLookup)));
        var positions = new StringBuilder();
        for (var position : new java.util.TreeSet<>(lookups)) {
            positions.append(frame(String.valueOf(position)));
        }
        out.append(frame(positions.toString()));
        target.parameterTypes().forEach(parameter -> out.append(frame(parameter)));
        return out.toString();
    }

    /**
     * The text form of an annotation in index form, every member kept; {@link #decodeAnnotation} reads
     * it back. Shared with the frozen {@code @Enhancement} patch (BUG-20261008-05).
     */
    public static String encodeAnnotation(AnnotationInfo info) {
        return annotation(info);
    }

    /** The annotation {@link #encodeAnnotation} wrote, in index form. */
    public static AnnotationInfo decodeAnnotation(String encoded) {
        return readAnnotation(new Frames(encoded));
    }

    /**
     * The text form of several annotations, one frame of {@link #encodeAnnotation} each;
     * {@link #decodeAnnotations} reads it back. The form of the frozen {@code @Enhancement} patch and of
     * the qualifiers of a synthetic bean or observer (BUG-20261111-01).
     */
    public static String encodeAnnotations(Iterable<AnnotationInfo> annotations) {
        var out = new StringBuilder();
        annotations.forEach(info -> out.append(frame(annotation(info))));
        return out.toString();
    }

    /**
     * The annotations {@link #encodeAnnotations} wrote. The form earlier versions wrote, annotation names
     * separated by commas, still reads, each annotation with no member: a frame starts with a digit, a
     * name never does.
     */
    public static java.util.List<AnnotationInfo> decodeAnnotations(String encoded) {
        var out = new ArrayList<AnnotationInfo>();
        var text = encoded == null ? "" : encoded.strip();
        if (text.isEmpty()) {
            return out;
        }
        if (!Character.isDigit(text.charAt(0))) {
            for (var part : text.split(",")) {
                var name = part.strip();
                if (!name.isEmpty()) {
                    out.add(new AnnotationInfo(DotName.of(name), Map.of()));
                }
            }
            return out;
        }
        var frames = new Frames(text);
        while (frames.hasNext()) {
            out.add(readAnnotation(new Frames(frames.next())));
        }
        return out;
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
            case AnnotationValue.StringVal(var text) -> "s:" + text;
            case AnnotationValue.BooleanVal(var flag) -> "z:" + flag;
            case AnnotationValue.ByteVal(var number) -> "b:" + number;
            case AnnotationValue.CharVal(var character) -> "c:" + (int) character;
            case AnnotationValue.ShortVal(var number) -> "h:" + number;
            case AnnotationValue.IntVal(var number) -> "i:" + number;
            case AnnotationValue.LongVal(var number) -> "j:" + number;
            case AnnotationValue.FloatVal(var number) -> "f:" + number;
            case AnnotationValue.DoubleVal(var number) -> "d:" + number;
            case AnnotationValue.ClassVal(DotName(var className)) -> "k:" + className;
            case AnnotationValue.EnumVal(DotName(var enumType), var constantName) ->
                    "e:" + frame(enumType) + frame(constantName);
            case AnnotationValue.AnnotationVal(var nested) -> "@:" + annotation(nested);
            case AnnotationValue.ArrayVal(var elements) -> {
                var out = new StringBuilder("[:");
                elements.forEach(element -> out.append(frame(member(element))));
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
            case 'G' -> readGeneratedInvoker(new Frames(value), loader);
            case '[' -> readArray(new Frames(value), loader);
            default -> throw unknownKind(encoded);
        };
    }

    /**
     * A value of a kind this version does not know. An upper-case letter is how {@code encode} names a kind: one this
     * version does not read was written by a newer Vauban build, as the {@code G:} invoker of BUG-20261118-37 is for an
     * older one; the message says so, so that the deployment names the version skew.
     */
    private static IllegalArgumentException unknownKind(String encoded) {
        var kind = encoded.charAt(0);
        if (kind >= 'A' && kind <= 'Z') {
            return new IllegalArgumentException("a synthetic parameter of kind '" + kind + "' that this Vauban version"
                    + " cannot read: a newer Vauban build wrote the record; build and run the application with the same"
                    + " Vauban version (" + encoded + ")");
        }
        return new IllegalArgumentException("not a synthetic parameter: " + encoded);
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
        var recorded = RecordedInvoker.read(frames);
        return new VaubanInvoker(VaubanInvoker.resolve(recorded.target(), loader),
                load(recorded.target().beanClass(), loader), recorded.instanceLookup(), recorded.argumentLookups());
    }

    /**
     * An invoker that calls its method through the class the build generated, and resolves no method; or, its class
     * frame empty, one that resolves its method by names and calls it itself, its looked-up parameters as recorded
     * (BUG-20261118-55, review round 3).
     */
    private static VaubanInvoker readGeneratedInvoker(Frames frames, ClassLoader loader) {
        var generatedName = frames.next();
        var isStatic = Boolean.parseBoolean(frames.next());
        var recordedPoints = new Frames(frames.next());
        var recorded = RecordedInvoker.read(frames);
        var target = recorded.target();
        var beanClass = load(target.beanClass(), loader);
        var positions = new ArrayList<Integer>();
        var notations = new ArrayList<String>();
        var encodedAnnotations = new ArrayList<String>();
        while (recordedPoints.hasNext()) {
            positions.add(Integer.parseInt(recordedPoints.next()));
            notations.add(recordedPoints.next());
            encodedAnnotations.add(recordedPoints.next());
        }
        // The method, when the invoker calls it itself or a type frame is empty: the type is then the method's
        // (review round 5)
        var method = generatedName.isEmpty() || notations.stream().anyMatch(String::isEmpty)
                ? VaubanInvoker.resolve(target, loader) : null;
        var points = new java.util.HashMap<Integer, VaubanInvoker.LookupPoint>();
        for (int i = 0; i < positions.size(); i++) {
            var position = positions.get(i);
            var type = notations.get(i).isEmpty() ? Objects.requireNonNull(method).getGenericParameterTypes()[position]
                    : io.vidocq.vauban.core.event.VaubanObserverMethod.reflectTypeOf(
                            ObservedTypeNotation.read(notations.get(i)), loader);
            points.put(position, new VaubanInvoker.LookupPoint(type,
                    annotations(decodeAnnotations(encodedAnnotations.get(i)), loader)));
        }
        if (generatedName.isEmpty()) {
            return new VaubanInvoker(method, beanClass, recorded.instanceLookup(), points);
        }
        return new VaubanInvoker(target, beanClass, load(target.declaringClass(), loader), isStatic,
                recorded.instanceLookup(), points, load(generatedName, loader));
    }

    /** The annotations of {@code infos} whose type {@code loader} loads, as instances. */
    static java.util.List<Annotation> annotations(java.util.List<AnnotationInfo> infos, ClassLoader loader) {
        var annotations = new ArrayList<Annotation>();
        for (var info : infos) {
            AnnotationInstances.typeNamed(info.name().value(), loader)
                    .ifPresent(type -> annotations.add(AnnotationInstances.create(type, info, loader)));
        }
        return annotations;
    }

    /** An invoker by names, as {@code invoker} writes it. */
    private record RecordedInvoker(InvokerTarget target, boolean instanceLookup,
                                   java.util.Set<Integer> argumentLookups) {

        static RecordedInvoker read(Frames frames) {
            var beanClass = frames.next();
            var declaringClass = frames.next();
            var name = frames.next();
            var instanceLookup = Boolean.parseBoolean(frames.next());
            var argumentLookups = new HashSet<Integer>();
            var lookups = new Frames(frames.next());
            while (lookups.hasNext()) {
                argumentLookups.add(Integer.parseInt(lookups.next()));
            }
            var parameters = new ArrayList<String>();
            while (frames.hasNext()) {
                parameters.add(frames.next());
            }
            return new RecordedInvoker(new InvokerTarget(beanClass, declaringClass, name, parameters), instanceLookup,
                    argumentLookups);
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

    static Class<?> load(String name, ClassLoader loader) {
        var primitive = PRIMITIVES.get(name);
        if (primitive != null) {
            return primitive;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError _) {
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
