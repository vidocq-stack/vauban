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
package io.vidocq.vauban.naming;

import io.vidocq.vauban.api.AnnotationInstanceFactory;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.literal.NamedLiteral;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a bean written as text: a type, then qualifiers, each {@code @name} with members between parentheses.
 * A string or char literal may hold any character, so the reading tracks quotes.
 */
final class BeanDeclarations {

    private final String declaration;
    private final ClassLoader loader;
    private int at;

    private BeanDeclarations(String declaration, ClassLoader loader) {
        this.declaration = declaration;
        this.loader = loader;
    }

    static JndiTarget.Bean parse(String declaration, ClassLoader loader) {
        return new BeanDeclarations(declaration, loader).read();
    }

    private JndiTarget.Bean read() {
        skipSpaces();
        if (at == declaration.length()) {
            throw refused("the declaration is empty");
        }
        Class<?> type = load(name(), false);
        Set<Annotation> qualifiers = new LinkedHashSet<>();
        skipSpaces();
        while (at < declaration.length()) {
            if (declaration.charAt(at) != '@') {
                throw refused("expected @ before a qualifier at '" + declaration.substring(at) + "'");
            }
            at++;
            String annotation = name();
            skipSpaces();
            Map<String, String> members = at < declaration.length() && declaration.charAt(at) == '(' ? members()
                    : Map.of();
            qualifiers.add(qualifier(annotation, members));
            skipSpaces();
        }
        return new JndiTarget.Bean(type, qualifiers, declaration.strip());
    }

    private Annotation qualifier(String annotation, Map<String, String> members) {
        switch (annotation) {
            case "Named" -> {
                String name = members.get("value");
                if (name == null || members.size() != 1) {
                    throw refused("@Named takes a name, such as @Named(\"orders\")");
                }
                return NamedLiteral.of(unquoted(name));
            }
            case "Default" -> {
                return Default.Literal.INSTANCE;
            }
            case "Any" -> {
                return Any.Literal.INSTANCE;
            }
            default -> {
                if (annotation.indexOf('.') < 0) {
                    throw refused("@" + annotation + ": a qualifier other than @Named, @Default and @Any is written "
                            + "with its fully qualified name");
                }
                return instance(load(annotation, true).asSubclass(Annotation.class), members);
            }
        }
    }

    private Annotation instance(Class<? extends Annotation> type, Map<String, String> members) {
        AnnotationInstanceFactory factory = AnnotationInstanceFactory.find().orElseThrow(() -> refused("@"
                + type.getName() + " needs Vauban's annotation instances (vauban-core) on the module path"));
        try {
            return factory.create(type, members, loader);
        } catch (IllegalArgumentException wrong) {
            throw refused(wrong.getMessage(), wrong);
        }
    }

    /** The type named as in source, a nested class as Outer.Inner. */
    private Class<?> load(String name, boolean annotation) {
        String binary = name;
        while (true) {
            try {
                Class<?> type = Class.forName(binary, false, loader);
                if (annotation && !type.isAnnotation()) {
                    throw refused("no annotation " + name + ": it is a class");
                }
                return type;
            } catch (ClassNotFoundException | LinkageError absent) {
                int dot = binary.lastIndexOf('.');
                if (dot < 0) {
                    throw refused("no " + (annotation ? "annotation " : "class ") + name, absent);
                }
                binary = binary.substring(0, dot) + "$" + binary.substring(dot + 1);
            }
        }
    }

    private String name() {
        int start = at;
        while (at < declaration.length()) {
            char c = declaration.charAt(at);
            if (!Character.isJavaIdentifierPart(c) && c != '.') {
                break;
            }
            at++;
        }
        if (start == at) {
            throw refused("expected a name at '" + declaration.substring(start) + "'");
        }
        return declaration.substring(start, at);
    }

    /** The members between parentheses: one literal for value, or name = literal pairs. */
    private Map<String, String> members() {
        int open = at++;
        List<String> parts = new ArrayList<>();
        int start = at;
        char quote = 0;
        for (; at < declaration.length(); at++) {
            char c = declaration.charAt(at);
            if (quote != 0) {
                if (c == '\\') {
                    at++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                throw refused("arrays are not supported in a member");
            } else if (c == ',') {
                parts.add(declaration.substring(start, at));
                start = at + 1;
            } else if (c == ')') {
                parts.add(declaration.substring(start, at));
                at++;
                return named(parts);
            }
        }
        throw refused("unbalanced quote or parenthesis after '" + declaration.substring(0, open + 1) + "'");
    }

    private Map<String, String> named(List<String> parts) {
        Map<String, String> members = new LinkedHashMap<>();
        if (parts.size() == 1 && parts.getFirst().isBlank()) {
            return members;
        }
        for (String part : parts) {
            int equals = assignment(part);
            if (equals < 0) {
                if (parts.size() != 1) {
                    throw refused("several members are written name = value");
                }
                members.put("value", part.strip());
            } else {
                members.put(part.substring(0, equals).strip(), part.substring(equals + 1).strip());
            }
        }
        return members;
    }

    /** The index of the = of name = literal, or -1 for a single literal: an = inside quotes does not count. */
    private static int assignment(String part) {
        char quote = 0;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    private String unquoted(String literal) {
        if (literal.length() < 2 || !literal.startsWith("\"") || !literal.endsWith("\"")) {
            throw refused("a name is written between double quotes: " + literal);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < literal.length() - 1; i++) {
            char c = literal.charAt(i);
            if (c != '\\') {
                text.append(c);
                continue;
            }
            char escaped = ++i < literal.length() - 1 ? literal.charAt(i) : '\\';
            text.append(switch (escaped) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case '0' -> '\0';
                case '\\', '"', '\'' -> escaped;
                default -> throw refused("unknown escape \\" + escaped + " in " + literal);
            });
        }
        return text.toString();
    }

    private void skipSpaces() {
        while (at < declaration.length() && Character.isWhitespace(declaration.charAt(at))) {
            at++;
        }
    }

    private IllegalArgumentException refused(String reason) {
        return new IllegalArgumentException("Invalid bean declaration '" + declaration.strip() + "': " + reason);
    }

    private IllegalArgumentException refused(String reason, Throwable cause) {
        var failure = refused(reason);
        failure.initCause(cause);
        return failure;
    }
}
