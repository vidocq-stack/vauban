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

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes what the language model shows of the types of a class, with their type annotations, in a syntax close to
 * Java's: {@code @A String @B []}, {@code List<@C ? extends Number>}, {@code @D U extends @C T extends Number}. A
 * class is named by its simple name. A type variable shows its bounds, when it has any, so that the annotations the
 * declaration of a type parameter puts on its bounds show where the variable is used (BUG-20261117-60).
 */
public final class TypeAnnotationsProbe {

    private TypeAnnotationsProbe() {
    }

    /** Every type of {@code type}: its type parameters, supertypes, fields, then its methods by name. */
    public static List<String> typesOf(ClassInfo type) {
        var lines = new ArrayList<String>();
        lines.add("typeParameters " + all(type.typeParameters()));
        lines.add("superClass " + (type.superClass() == null ? "null" : of(type.superClass())));
        lines.add("superInterfaces " + all(type.superInterfaces()));
        type.fields().stream().sorted(Comparator.comparing(FieldInfo::name))
                .forEach(field -> lines.add(field.name() + " " + of(field.type())));
        type.methods().stream().filter(method -> method.declaringClass().name().equals(type.name()))
                .sorted(Comparator.comparing(MethodInfo::name))
                .forEach(method -> lines.add(method.name() + " " + of(method.returnType()) + " "
                        + all(method.parameters().stream().map(ParameterInfo::type).toList()) + " throws "
                        + all(method.throwsTypes())));
        type.constructors().forEach(constructor -> lines.add("constructor "
                + all(constructor.parameters().stream().map(ParameterInfo::type).toList())));
        return lines;
    }

    public static String all(List<? extends Type> types) {
        return types.stream().map(TypeAnnotationsProbe::of).collect(Collectors.joining(", ", "[", "]"));
    }

    /** One type, its annotations first, as Java writes them. */
    public static String of(Type type) {
        if (type == null) {
            return "null";
        }
        return switch (type.kind()) {
            case ARRAY -> array(type);
            case CLASS -> annotations(type) + simple(type.asClass().declaration().name());
            case PARAMETERIZED_TYPE -> annotations(type)
                    + simple(type.asParameterizedType().genericClass().declaration().name())
                    + all(type.asParameterizedType().typeArguments()).replace('[', '<').replace(']', '>');
            case TYPE_VARIABLE -> annotations(type) + type.asTypeVariable().name()
                    + bounds(type.asTypeVariable().bounds());
            case WILDCARD_TYPE -> annotations(type) + "?" + wildcard(type);
            case PRIMITIVE -> annotations(type) + type.asPrimitive().name();
            case VOID -> annotations(type) + "void";
        };
    }

    private static String array(Type type) {
        var dimensions = new StringBuilder();
        var component = type;
        while (component.isArray()) {
            dimensions.append(' ').append(annotations(component)).append("[]");
            component = component.asArray().componentType();
        }
        return of(component) + dimensions;
    }

    private static String bounds(List<Type> bounds) {
        return bounds.isEmpty() ? "" : bounds.stream().map(TypeAnnotationsProbe::of)
                .collect(Collectors.joining(" & ", " extends ", ""));
    }

    private static String wildcard(Type type) {
        var wildcard = type.asWildcardType();
        if (wildcard.upperBound() != null) {
            return " extends " + of(wildcard.upperBound());
        }
        return wildcard.lowerBound() != null ? " super " + of(wildcard.lowerBound()) : "";
    }

    private static String annotations(Type type) {
        return type.annotations().stream().map(AnnotationInfo::name).map(name -> "@" + simple(name) + " ")
                .collect(Collectors.joining());
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
