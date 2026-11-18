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

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.util.List;
import java.util.Set;

/**
 * The method an invoker calls, by names: the class of its bean, the class that declares it, its name, and the
 * erasures of its parameter types as {@link Class#getName()} writes them. The build describes so a method of a class
 * it compiles, which has no class file to load yet, and records the invoker with these names; the start loads the
 * classes and finds the method (BUG-20261117-48), or loads the class the build generated to call it
 * (BUG-20261118-37).
 */
public record InvokerTarget(String beanClass, String declaringClass, String name, List<String> parameterTypes) {

    private static final String OBJECT = "java.lang.Object";

    public InvokerTarget {
        parameterTypes = List.copyOf(parameterTypes);
    }

    /** The target of an invoker of {@code method}, read from the language model, of a bean of {@code beanClass}. */
    static InvokerTarget of(String beanClass, MethodInfo method) {
        return new InvokerTarget(beanClass, method.declaringClass().name(), method.name(),
                method.parameters().stream().map(parameter -> erasedName(parameter.type())).toList());
    }

    /** Whether the method is one {@code Object} declares. */
    boolean isObjectMethod() {
        return OBJECT.equals(declaringClass);
    }

    /**
     * {@code type} erased, as {@link Class#getName()} writes it: {@code int}, {@code java.lang.String},
     * {@code [Ljava.lang.String;}, {@code [[I}; a type variable is the erasure of its first bound.
     */
    static String erasedName(Type type) {
        return switch (type) {
            case ArrayType array -> "[" + descriptor(array.componentType());
            default -> elementName(type);
        };
    }

    private static String descriptor(Type type) {
        return switch (type) {
            case ArrayType array -> "[" + descriptor(array.componentType());
            case PrimitiveType primitive -> primitiveDescriptor(primitive.primitiveKind());
            default -> "L" + elementName(type) + ";";
        };
    }

    private static String elementName(Type type) {
        return switch (type) {
            case PrimitiveType primitive -> primitive.name();
            case ClassType classType -> classType.declaration().name();
            case ParameterizedType parameterized -> parameterized.genericClass().declaration().name();
            case TypeVariable variable -> variable.bounds().isEmpty() ? OBJECT
                    : erasedName(variable.bounds().getFirst());
            default -> OBJECT;
        };
    }

    private static String primitiveDescriptor(PrimitiveType.PrimitiveKind kind) {
        return switch (kind) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case CHAR -> "C";
        };
    }

    /**
     * An invoker the build made of a method of a class it compiles: it has no method object yet, and is recorded by
     * {@link SyntheticParamCodec} as a {@link VaubanInvoker} is, for the start to resolve.
     *
     * @param shape how a generated class calls the method, {@code null} when the invoker was not built from the
     *              language model
     */
    record Described(InvokerTarget target, boolean instanceLookup, Set<Integer> argumentLookups, InvokerShape shape)
            implements InvokerInfo {

        Described {
            argumentLookups = Set.copyOf(argumentLookups);
        }
    }
}
