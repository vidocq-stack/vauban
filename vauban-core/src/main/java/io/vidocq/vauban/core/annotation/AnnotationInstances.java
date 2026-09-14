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

import java.lang.annotation.Annotation;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The annotation instances the container hands to application code — {@code Bean#getQualifiers()},
 * {@code ObserverMethod#getObservedQualifiers()}, {@code Parameters#get} — built from index data.
 *
 * <p>They honour {@link Annotation#equals}, {@link Annotation#hashCode} and {@link Annotation#toString}
 * as the specification defines them, so they compare equal to the instance the JDK builds from the
 * same declaration, and each member returns a value of its declared type: a primitive array as that
 * array, an enum constant, a {@code Class}, a nested annotation, a member left out as its default.
 * {@code @Nonbinding} takes no part: it is a CDI resolution rule, not part of this contract.
 *
 * <p>One instance per annotation, cached by whoever exposes it. Matching never goes through them —
 * it compares {@link AnnotationKey}s — so nothing on the resolution path reads a member back.
 */
public final class AnnotationInstances {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "byte", byte.class, "char", char.class, "short", short.class,
            "int", int.class, "long", long.class, "float", float.class, "double", double.class,
            "void", void.class);

    private AnnotationInstances() {
    }

    /**
     * An instance of {@code type} whose members are those of {@code annotation}, every other member
     * taking the default its declaration gives.
     *
     * @param loader the class loader to resolve the {@code Class} members with; the annotation type's
     *               own loader and the context class loader are tried too
     */
    public static <A extends Annotation> A create(Class<A> type, AnnotationInfo annotation, ClassLoader loader) {
        AnnotationReflection.check("building an instance of", type.getName());
        var handler = new Handler(type, annotation, loader);
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    /**
     * The members {@code annotation} was built from, or empty when it is not an instance this class
     * built — a JDK instance read from a class file, or a literal an application wrote.
     */
    public static Optional<AnnotationInfo> infoOf(Annotation annotation) {
        return handlerOf(annotation).map(handler -> handler.annotation);
    }

    /**
     * The annotation type named {@code name}, loaded through {@code loader}, the context class loader
     * or vauban-core's own, or empty when none of them can see it.
     */
    public static Optional<Class<? extends Annotation>> typeNamed(String name, ClassLoader loader) {
        for (var candidate : loaders(loader)) {
            try {
                var type = Class.forName(name, false, candidate);
                if (type.isAnnotation()) {
                    return Optional.of(type.asSubclass(Annotation.class));
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // The next loader may see it.
            }
        }
        return Optional.empty();
    }

    private static Optional<Handler> handlerOf(Object annotation) {
        if (annotation != null && Proxy.isProxyClass(annotation.getClass())
                && Proxy.getInvocationHandler(annotation) instanceof Handler handler) {
            return Optional.of(handler);
        }
        return Optional.empty();
    }

    private static final class Handler implements InvocationHandler {

        /** A member with neither a written value nor a default: {@link ConcurrentHashMap} takes no null. */
        private static final Object ABSENT = new Object();

        private final Class<? extends Annotation> type;
        private final AnnotationInfo annotation;
        private final ClassLoader loader;
        private final Map<String, Object> values = new ConcurrentHashMap<>();
        private volatile List<Method> members;
        private volatile Integer hash;

        Handler(Class<? extends Annotation> type, AnnotationInfo annotation, ClassLoader loader) {
            this.type = Objects.requireNonNull(type);
            this.annotation = Objects.requireNonNull(annotation);
            this.loader = loader;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (args != null && args.length == 1 && "equals".equals(method.getName())) {
                return equalsImpl(proxy, args[0]);
            }
            return switch (method.getName()) {
                case "hashCode" -> hashCodeImpl();
                case "toString" -> toStringImpl();
                case "annotationType" -> type;
                default -> {
                    var value = memberValue(method);
                    if (value == null) {
                        throw new IncompleteAnnotationException(type, method.getName());
                    }
                    yield copyOf(value);
                }
            };
        }

        /** The value of {@code member}, or {@code null} when it has neither a written value nor a default. */
        private Object memberValue(Method member) {
            var value = values.computeIfAbsent(member.getName(), name -> {
                var written = annotation.members().get(name);
                if (written != null) {
                    return convert(written, member.getReturnType(), loader);
                }
                var fallback = member.getDefaultValue();
                return fallback != null ? fallback : ABSENT;
            });
            return value == ABSENT ? null : value;
        }

        private List<Method> members() {
            var declared = members;
            if (declared == null) {
                declared = AnnotationValues.members(type);
                members = declared;
            }
            return declared;
        }

        private boolean equalsImpl(Object proxy, Object other) {
            if (proxy == other) return true;
            if (!type.isInstance(other)) return false;
            var otherHandler = handlerOf(other);
            for (var member : members()) {
                var mine = memberValue(member);
                Object theirs;
                if (otherHandler.isPresent()) {
                    theirs = otherHandler.get().memberValue(member);
                } else {
                    try {
                        theirs = read(member, other);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // A member this module may not invoke — a non-public annotation type in a
                        // package that opens nothing. The other side reads us instead; the JDK's own
                        // handler and AnnotationLiteral both do it with privileged access.
                        return other.equals(proxy);
                    }
                }
                if (!memberValueEquals(mine, theirs)) return false;
            }
            return true;
        }

        private static Object read(Method member, Object annotation) throws ReflectiveOperationException {
            member.trySetAccessible();
            try {
                return member.invoke(annotation);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("cannot read " + member, e.getCause());
            }
        }

        private int hashCodeImpl() {
            var computed = hash;
            if (computed == null) {
                int sum = 0;
                for (var member : members()) {
                    var value = memberValue(member);
                    if (value != null) {
                        sum += (127 * member.getName().hashCode()) ^ memberValueHashCode(value);
                    }
                }
                computed = sum;
                hash = computed;
            }
            return computed;
        }

        private String toStringImpl() {
            var rendered = new ArrayList<String>();
            for (var member : members().stream().sorted(java.util.Comparator.comparing(Method::getName)).toList()) {
                var value = memberValue(member);
                if (value != null) {
                    rendered.add(member.getName() + "=" + render(value));
                }
            }
            var name = type.getCanonicalName() != null ? type.getCanonicalName() : type.getName();
            return "@" + name + "(" + String.join(", ", rendered) + ")";
        }
    }

    // ---- member values ----

    /** The value an index member takes as the Java value of a member declared {@code target}. */
    private static Object convert(AnnotationValue value, Class<?> target, ClassLoader loader) {
        if (target.isArray()) {
            var component = target.getComponentType();
            var items = value instanceof AnnotationValue.ArrayVal array ? array.values() : List.of(value);
            var result = Array.newInstance(component, items.size());
            for (int i = 0; i < items.size(); i++) {
                Array.set(result, i, convert(items.get(i), component, loader));
            }
            return result;
        }
        return switch (value) {
            // An extension can write a class or an enum constant as a string (BUG-20260914-16), and a
            // number wider or narrower than the member declares; the declared type decides.
            case AnnotationValue.StringVal v -> string(v.value(), target, loader);
            case AnnotationValue.BooleanVal v -> v.value();
            case AnnotationValue.ByteVal v -> number(v.value(), target);
            case AnnotationValue.CharVal v -> v.value();
            case AnnotationValue.ShortVal v -> number(v.value(), target);
            case AnnotationValue.IntVal v -> number(v.value(), target);
            case AnnotationValue.LongVal v -> number(v.value(), target);
            case AnnotationValue.FloatVal v -> number(v.value(), target);
            case AnnotationValue.DoubleVal v -> number(v.value(), target);
            case AnnotationValue.ClassVal v -> classOf(v.className().value(), loader);
            case AnnotationValue.EnumVal v -> enumOf(target, v.constantName());
            case AnnotationValue.AnnotationVal v -> nested(target, v.annotation(), loader);
            // An array value for a member that is not an array: keep the first item.
            case AnnotationValue.ArrayVal v -> v.values().isEmpty() ? null : convert(v.values().getFirst(), target, loader);
        };
    }

    private static Object string(String value, Class<?> target, ClassLoader loader) {
        if (target == Class.class) return classOf(value, loader);
        if (target.isEnum()) return enumOf(target, value);
        return value;
    }

    private static Object number(Number value, Class<?> target) {
        if (target == byte.class || target == Byte.class) return value.byteValue();
        if (target == short.class || target == Short.class) return value.shortValue();
        if (target == int.class || target == Integer.class) return value.intValue();
        if (target == long.class || target == Long.class) return value.longValue();
        if (target == float.class || target == Float.class) return value.floatValue();
        if (target == double.class || target == Double.class) return value.doubleValue();
        if (target == char.class || target == Character.class) return (char) value.intValue();
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(Class<?> target, String constant) {
        if (!target.isEnum()) {
            throw new IllegalStateException(target.getName() + " is not an enum type, so it has no constant " + constant);
        }
        return Enum.valueOf((Class) target, constant);
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Class<?> target, AnnotationInfo annotation, ClassLoader loader) {
        if (!target.isAnnotation()) {
            throw new IllegalStateException(target.getName() + " is not an annotation type");
        }
        return create((Class<? extends Annotation>) target, annotation, loader);
    }

    /** {@code Class.forName} over the loaders that can see the type, as {@code Class#getName} spells it. */
    private static Class<?> classOf(String name, ClassLoader loader) {
        var primitive = PRIMITIVES.get(name);
        if (primitive != null) return primitive;
        for (var candidate : loaders(loader)) {
            try {
                return Class.forName(name, false, candidate);
            } catch (ClassNotFoundException | LinkageError e) {
                // The next loader may see it.
            }
        }
        throw new TypeNotPresentException(name, null);
    }

    private static List<ClassLoader> loaders(ClassLoader loader) {
        return java.util.stream.Stream
                .of(loader, Thread.currentThread().getContextClassLoader(), AnnotationInstances.class.getClassLoader())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Annotation members hand out a copy of their array, so a caller cannot change the instance. */
    private static Object copyOf(Object value) {
        return switch (value) {
            case boolean[] array -> array.clone();
            case byte[] array -> array.clone();
            case char[] array -> array.clone();
            case short[] array -> array.clone();
            case int[] array -> array.clone();
            case long[] array -> array.clone();
            case float[] array -> array.clone();
            case double[] array -> array.clone();
            case Object[] array -> array.clone();
            default -> value;
        };
    }

    /** {@code Annotation#equals}: arrays compare element by element, floats and doubles by their bits. */
    private static boolean memberValueEquals(Object mine, Object theirs) {
        if (mine == null || theirs == null) return mine == theirs;
        return switch (mine) {
            case boolean[] array -> theirs instanceof boolean[] other && Arrays.equals(array, other);
            case byte[] array -> theirs instanceof byte[] other && Arrays.equals(array, other);
            case char[] array -> theirs instanceof char[] other && Arrays.equals(array, other);
            case short[] array -> theirs instanceof short[] other && Arrays.equals(array, other);
            case int[] array -> theirs instanceof int[] other && Arrays.equals(array, other);
            case long[] array -> theirs instanceof long[] other && Arrays.equals(array, other);
            case float[] array -> theirs instanceof float[] other && Arrays.equals(array, other);
            case double[] array -> theirs instanceof double[] other && Arrays.equals(array, other);
            case Object[] array -> theirs instanceof Object[] other && Arrays.equals(array, other);
            default -> mine.equals(theirs);
        };
    }

    private static int memberValueHashCode(Object value) {
        return switch (value) {
            case boolean[] array -> Arrays.hashCode(array);
            case byte[] array -> Arrays.hashCode(array);
            case char[] array -> Arrays.hashCode(array);
            case short[] array -> Arrays.hashCode(array);
            case int[] array -> Arrays.hashCode(array);
            case long[] array -> Arrays.hashCode(array);
            case float[] array -> Arrays.hashCode(array);
            case double[] array -> Arrays.hashCode(array);
            case Object[] array -> Arrays.hashCode(array);
            default -> value.hashCode();
        };
    }

    private static String render(Object value) {
        return switch (value) {
            case String text -> "\"" + text + "\"";
            case Class<?> type -> type.getName() + ".class";
            case Object[] array -> Arrays.stream(array).map(AnnotationInstances::render)
                    .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
            default -> {
                if (value.getClass().isArray()) {
                    var items = new ArrayList<String>();
                    for (int i = 0; i < Array.getLength(value); i++) {
                        items.add(render(Array.get(value, i)));
                    }
                    yield items.stream().collect(java.util.stream.Collectors.joining(", ", "{", "}"));
                }
                yield String.valueOf(value);
            }
        };
    }
}
