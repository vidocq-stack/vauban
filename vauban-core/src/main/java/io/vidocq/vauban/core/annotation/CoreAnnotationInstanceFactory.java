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

import io.vidocq.vauban.api.AnnotationInstanceFactory;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Vauban's {@link AnnotationInstanceFactory}: each literal becomes the Java value its member declares, then
 * {@link AnnotationInstances#create} builds the instance, as for an annotation read from the index.
 */
public final class CoreAnnotationInstanceFactory implements AnnotationInstanceFactory {

    @Override
    public <A extends Annotation> A create(Class<A> type, Map<String, String> members, ClassLoader loader) {
        var unknown = new HashSet<>(members.keySet());
        var values = new HashMap<String, AnnotationValue>();
        for (Method member : AnnotationValues.members(type)) {
            unknown.remove(member.getName());
            var literal = members.get(member.getName());
            if (literal == null) {
                if (member.getDefaultValue() == null) {
                    throw refused(type, member.getName(), "has no default and is left out");
                }
                continue;
            }
            values.put(member.getName(), AnnotationValues.of(valueOf(type, member, literal.strip(), loader)));
        }
        if (!unknown.isEmpty()) {
            throw refused(type, String.join(", ", unknown), "is not a member");
        }
        return AnnotationInstances.create(type, new AnnotationInfo(DotName.of(type.getName()), values), loader);
    }

    private static Object valueOf(Class<?> annotation, Method member, String literal, ClassLoader loader) {
        Class<?> target = member.getReturnType();
        if (target.isArray() || target.isAnnotation()) {
            throw refused(annotation, member.getName(), "is an " + (target.isArray() ? "array" : "annotation")
                    + ", which a literal cannot be written for");
        }
        try {
            if (target == String.class) return string(literal);
            if (target == boolean.class) return bool(literal);
            if (target == char.class) return character(literal);
            if (target == Class.class) return type(literal, loader);
            if (target.isEnum()) return constant(target, literal);
            return number(target, literal);
        } catch (IllegalArgumentException wrong) {
            var failure = refused(annotation, member.getName(),
                    "takes a " + target.getSimpleName() + ", not " + literal);
            failure.initCause(wrong);
            throw failure;
        }
    }

    private static String string(String literal) {
        if (literal.length() < 2 || !literal.startsWith("\"") || !literal.endsWith("\"")) {
            throw new IllegalArgumentException("a string is written between double quotes");
        }
        return unescape(literal.substring(1, literal.length() - 1));
    }

    private static boolean bool(String literal) {
        return switch (literal) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("a boolean is true or false");
        };
    }

    private static char character(String literal) {
        if (literal.length() < 3 || !literal.startsWith("'") || !literal.endsWith("'")) {
            throw new IllegalArgumentException("a char is written between single quotes");
        }
        var text = unescape(literal.substring(1, literal.length() - 1));
        if (text.length() != 1) {
            throw new IllegalArgumentException("a char is one character");
        }
        return text.charAt(0);
    }

    private static Class<?> type(String literal, ClassLoader loader) {
        if (!literal.endsWith(".class")) {
            throw new IllegalArgumentException("a class is written Name.class");
        }
        // A nested class is written Outer.Inner in source and named Outer$Inner: try each dot from the right.
        var name = literal.substring(0, literal.length() - ".class".length());
        while (true) {
            try {
                return AnnotationInstances.classOf(name, loader);
            } catch (TypeNotPresentException absent) {
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    throw new IllegalArgumentException("no class " + literal, absent);
                }
                name = name.substring(0, dot) + "$" + name.substring(dot + 1);
            }
        }
    }

    private static Object constant(Class<?> target, String literal) {
        int dot = literal.lastIndexOf('.');
        if (dot >= 0) {
            var owner = literal.substring(0, dot);
            if (!owner.equals(target.getName()) && !owner.equals(target.getCanonicalName())
                    && !owner.equals(target.getSimpleName())) {
                throw new IllegalArgumentException(owner + " is not " + target.getName());
            }
        }
        var name = literal.substring(dot + 1);
        for (Object constant : target.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(target.getName() + " has no constant " + name);
    }

    private static Object number(Class<?> target, String literal) {
        var digits = literal.replace("_", "");
        if (target == long.class) return Long.decode(withoutSuffix(digits, "lL"));
        if (target == int.class) return Integer.decode(digits);
        if (target == short.class) return Short.decode(digits);
        if (target == byte.class) return Byte.decode(digits);
        if (target == double.class) return Double.parseDouble(withoutSuffix(digits, "dD"));
        if (target == float.class) return Float.parseFloat(withoutSuffix(digits, "fF"));
        throw new IllegalArgumentException("unsupported member type " + target.getName());
    }

    private static String withoutSuffix(String digits, String suffixes) {
        return !digits.isEmpty() && suffixes.indexOf(digits.charAt(digits.length() - 1)) >= 0
                ? digits.substring(0, digits.length() - 1) : digits;
    }

    private static String unescape(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i == text.length()) {
                throw new IllegalArgumentException("a backslash ends the literal");
            }
            char escaped = text.charAt(i);
            out.append(switch (escaped) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case '0' -> '\0';
                case '\\', '"', '\'' -> escaped;
                default -> throw new IllegalArgumentException("unknown escape \\" + escaped);
            });
        }
        return out.toString();
    }

    private static IllegalArgumentException refused(Class<?> annotation, String member, String reason) {
        return new IllegalArgumentException("@" + annotation.getSimpleName() + ": " + member + " " + reason);
    }
}
